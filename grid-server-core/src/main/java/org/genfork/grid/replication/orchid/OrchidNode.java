/*
 * Copyright 2024-2026 GenCloud
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.genfork.grid.replication.orchid;

import org.genfork.grid.replication.OrchidNotSyncedException;
import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.orchid.OrchidTransport.OrchidCommitMessage;
import org.genfork.grid.replication.orchid.OrchidTransport.OrchidNackMessage;
import org.genfork.grid.replication.orchid.OrchidTransport.OrchidPhaseBatchMessage;
import org.genfork.grid.replication.orchid.OrchidTransport.OrchidPhaseDigest;
import org.genfork.grid.replication.orchid.OrchidTransport.OrchidPhaseMessage;
import org.genfork.grid.replication.orchid.OrchidTransport.OrchidProposeMessage;
import org.genfork.grid.threading.SerialTaskQueue;
import org.genfork.grid.threading.ThreadService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * Kuramoto-inspired consensus: phase sync + digest quorum + phase-ranked proposer.
 * Not Raft — no term/vote/leader election.
 * <p>
 * Multi-DC (hierarchical): local DC owns Kuramoto R + local digest quorum; optional remote
 * digest/commit voters (WAN timeout) when {@link OrchidMultiDcConfig} lists them. Remote peers
 * join phase/R only when {@code phaseCoupling=true}.
 * <p>
 * Pipelined propose ({@code maxProposeInFlight &gt; 1}) may broadcast commits out of order;
 * peers buffer via holdback and apply strictly contiguous — pipeline ≠ unordered apply.
 * <p>
 * Observability metrics (lock-free / no monitors — safe from Netty EL and VT):
 * {@link #orderParameterR()}, {@link #getPhaseRankedProposerId()}, {@link #pendingProposeAgeMs()}.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public class OrchidNode {
	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OrchidNode.class);
	/**
	 * Default pipelined proposes (contiguous prevOpSeq chain); not Semaphore(1).
	 */
	public static final int DEFAULT_MAX_PROPOSE_IN_FLIGHT = 64;
	private static final int MIN_PROPOSE_IN_FLIGHT = 1;
	private final String nodeId;
	private final double coupling;
	private final double omega;
	private final double orderThreshold;
	private final long tickMs;
	private final DigestQuorum digestQuorum;
	private final OrchidTransport transport;
	/**
	 * Configured same-DC peers (local Kuramoto / local digest quorum); mutable on add/removePeer.
	 */
	private final List<String> peerIds;
	/**
	 * Remote digest voters (+ phaseCoupling / WAN timeout); updatable via {@link #applyRemoteVoters}.
	 */
	private final AtomicReference<OrchidMultiDcConfig> multiDcRef;
	private final FileDurableOrchidStore durableStore;
	private final ReentrantLock lock = new ReentrantLock();
	private final AtomicBoolean running = new AtomicBoolean();
	private final Map<String, PeerView> peers = new ConcurrentHashMap<>();
	private final Map<Long, PendingPropose> pending = new ConcurrentHashMap<>();
	/**
	 * Peer-side expected next seq for in-flight remote proposes (proposeId → inflight).
	 * Enables pipelined prevOpSeq accept without holding VT/Netty monitors.
	 * Cleared on {@link #forgetPeer} for that proposer's entries.
	 */
	private final Map<Long, RemoteInflight> remoteInflightExpected = new ConcurrentHashMap<>();
	/**
	 * Recently applied proposeIds (bounded) — late propose after commit must not NACK a healthy chain.
	 */
	private final Set<Long> recentlyCommittedProposeIds = ConcurrentHashMap.newKeySet();
	private static final int RECENT_COMMITTED_PROPOSE_CAP = 4096;
	/**
	 * Single-level primary commit drain (avoids recursive {@code forEach → doCommit → forEach}).
	 */
	private final AtomicBoolean commitDrainActive = new AtomicBoolean();
	/**
	 * Out-of-order commit holdback (opSeq → message). Cap =
	 * {@link #maxProposeInFlight} × {@link #COMMIT_HOLDBACK_CAP_FACTOR}.
	 * Pipeline propose may broadcast commits concurrently; apply stays contiguous.
	 */
	private final ConcurrentHashMap<Long, OrchidCommitMessage> commitHoldback = new ConcurrentHashMap<>();
	private static final int COMMIT_HOLDBACK_CAP_FACTOR = 4;
	/**
	 * FIFO propose wire queue: concurrent {@link #appendAndAdmit} must not reorder
	 * {@code sendPropose} on the Netty channel (else peers NACK prevOpSeq gaps).
	 */
	private final ConcurrentLinkedQueue<OrchidProposeMessage> proposeSendQueue = new ConcurrentLinkedQueue<>();
	private final AtomicBoolean proposeSendPump = new AtomicBoolean();
	/**
	 * Serial mailbox: phase / propose / digest (off Netty EL).
	 */
	private final SerialTaskQueue orchidRpcQueue = new SerialTaskQueue();
	/**
	 * Serial mailbox: contiguous holdback drain + fireApply (no VT-pool interleave).
	 */
	private final SerialTaskQueue commitApplyQueue = new SerialTaskQueue();
	private final List<Consumer<ReplicationOp>> applyListeners = new CopyOnWriteArrayList<>();
	private final Set<String> voterEligible = ConcurrentHashMap.newKeySet();
	/**
	 * Peers held down for partition simulation / operator isolate — ignore HELLO until heal.
	 */
	private final Set<String> isolatedPeers = ConcurrentHashMap.newKeySet();
	private final AtomicLong proposeSeq = new AtomicLong();
	/**
	 * Wall-clock ms when the current in-flight propose was admitted; 0 when idle.
	 * Updated with CAS / plain set — never under a monitor.
	 */
	private final AtomicLong pendingProposeStartedAtMs = new AtomicLong(0L);
	/**
	 * Pipelined propose admission (contiguous prevOpSeq chain; release after commit/nack).
	 */
	private final Semaphore proposeFlight;
	private final int maxProposeInFlight;
	/**
	 * Next propose's {@code prevOpSeq} tip (equals {@link #lastCommittedSeq} when idle).
	 * Advanced under {@link #lock} at admit; rewound on nack/stop.
	 */
	private long proposeChainPrev;
	private volatile double phase;
	private volatile long lastCommittedSeq;
	private volatile Thread tickThread;

	public OrchidNode(String nodeId, double coupling, double naturalFreqHz, double orderThreshold, long tickMs, DigestQuorum digestQuorum, OrchidTransport transport, List<String> peerIds) {
		this(nodeId, coupling, naturalFreqHz, orderThreshold, tickMs, digestQuorum, transport, peerIds, null, false, OrchidMultiDcConfig.NONE);
	}

	public OrchidNode(String nodeId, double coupling, double naturalFreqHz, double orderThreshold, long tickMs, DigestQuorum digestQuorum, OrchidTransport transport, List<String> peerIds, Path orchidDir, boolean fsync) {
		this(nodeId, coupling, naturalFreqHz, orderThreshold, tickMs, digestQuorum, transport, peerIds, orchidDir, fsync, OrchidMultiDcConfig.NONE);
	}

	public OrchidNode(String nodeId, double coupling, double naturalFreqHz, double orderThreshold, long tickMs, DigestQuorum digestQuorum, OrchidTransport transport, List<String> localPeerIds, Path orchidDir, boolean fsync, OrchidMultiDcConfig multiDc) {
		this(nodeId, coupling, naturalFreqHz, orderThreshold, tickMs, digestQuorum, transport, localPeerIds, orchidDir, fsync, multiDc, DEFAULT_MAX_PROPOSE_IN_FLIGHT);
	}

	public OrchidNode(String nodeId, double coupling, double naturalFreqHz, double orderThreshold, long tickMs, DigestQuorum digestQuorum, OrchidTransport transport, List<String> localPeerIds, Path orchidDir, boolean fsync, OrchidMultiDcConfig multiDc, int maxProposeInFlight) {
		this.nodeId = Objects.requireNonNull(nodeId);
		this.coupling = coupling;
		this.omega = naturalFreqHz * 2.0 * Math.PI;
		this.orderThreshold = orderThreshold;
		this.tickMs = Math.max(1L, tickMs);
		this.digestQuorum = digestQuorum == null ? DigestQuorum.MAJORITY : digestQuorum;
		this.transport = Objects.requireNonNull(transport);
		this.peerIds = localPeerIds == null ? new CopyOnWriteArrayList<>() : new CopyOnWriteArrayList<>(localPeerIds);
		this.multiDcRef = new AtomicReference<>(multiDc == null ? OrchidMultiDcConfig.NONE : multiDc);
		this.maxProposeInFlight = Math.max(MIN_PROPOSE_IN_FLIGHT, maxProposeInFlight);
		this.proposeFlight = new Semaphore(this.maxProposeInFlight, true);
		this.phase = ThreadLocalRandom.current().nextDouble(0, 2 * Math.PI);
		FileDurableOrchidStore store = null;
		if (orchidDir != null) {
			try {
				store = new FileDurableOrchidStore(orchidDir, fsync);
				this.lastCommittedSeq = store.loadLastCommittedSeq();
			} catch (Exception ex) {
				log.warn("Failed to open orchid durable store at {}: {}", orchidDir, ex.toString());
			}
		}
		this.durableStore = store;
		this.proposeChainPrev = this.lastCommittedSeq;
	}

	public void start() {
		if (!running.compareAndSet(false, true)) {
			return;
		}
		tickThread = Thread.ofPlatform().name("orchid-tick-" + nodeId).daemon(true).start(this::tickLoop);
		final double freePerTick = omega * (tickMs / 1000.0);
		if (freePerTick > 0.35) {
			log.warn("OrchidNode free advance {} rad/tick (tickMs={}); delayed phase sync may never reach R>={}. Prefer natural-freq-hz so that 2*pi*f*tickMs/1000 << 1.", String.format("%.3f", freePerTick), tickMs, orderThreshold);
		}
		final OrchidMultiDcConfig mdc = multiDc();
		log.info("OrchidNode started node={} localPeers={} remoteVoters={} phaseCoupling={} threshold={} coupling={} omegaRadPerTick={}", nodeId, peerIds.size(), mdc.remoteVoterIds().size(), mdc.phaseCoupling(), orderThreshold, coupling, String.format("%.4f", freePerTick));
	}

	public void stop() {
		running.set(false);
		if (tickThread != null) {
			tickThread.interrupt();
		}
		pending.values().forEach(p -> p.future.completeExceptionally(new IllegalStateException("orchid stopped")));
		pending.clear();
		remoteInflightExpected.clear();
		recentlyCommittedProposeIds.clear();
		commitHoldback.clear();
		clearPendingProposeTimestamp();
		lock.lock();
		try {
			proposeChainPrev = lastCommittedSeq;
		} finally {
			lock.unlock();
		}
		if (durableStore != null) {
			try {
				durableStore.close();
			} catch (Exception ignored) {
			}
		}
	}

	public void addApplyListener(Consumer<ReplicationOp> listener) {
		applyListeners.add(listener);
	}

	public boolean isRunning() {
		return running.get();
	}

	/**
	 * Configured local-DC cluster size including self (YAML same-DC peers + this node).
	 */
	public int configuredVoterCount() {
		return peerIds.size() + 1;
	}

	public OrchidMultiDcConfig multiDcConfig() {
		return multiDc();
	}

	private OrchidMultiDcConfig multiDc() {
		return multiDcRef.get();
	}

	/**
	 * Operator/coordinator gate: replace remote digest voter ids only (local Kuramoto quorum unchanged).
	 * Preserves phaseCoupling and remoteVoterTimeoutMs from the current config.
	 */
	public void applyRemoteVoters(Collection<String> remoteVoterIds) {
		final OrchidMultiDcConfig cur = multiDc();
		final OrchidMultiDcConfig next = OrchidMultiDcConfig.of(remoteVoterIds, cur.phaseCoupling(), cur.remoteVoterTimeoutMs());
		multiDcRef.set(next);
		log.info("OrchidNode applyRemoteVoters node={} remoteVoters={}", nodeId, next.remoteVoterIds());
	}

	public void onPeerAvailable(String peerId) {
		if (peerId == null || peerId.equals(nodeId) || isolatedPeers.contains(peerId)) {
			return;
		}
		final PeerView view = peers.computeIfAbsent(peerId, PeerView::new);
		view.seen = true; // HELLO / channel up (may be remote voter without phase coupling)
		if (isPhaseCoupledPeer(peerId) || isLocalPeer(peerId)) {
			broadcastOwnPhase(0L, 0L);
		}
	}

	/**
	 * Intentional membership shrink (operator removePeer): drop from configured local quorum.
	 * Distinct from {@link #forgetPeer} which only clears the live view (anti-solo after partition).
	 */
	public void unconfigurePeer(String peerId) {
		if (peerId == null || peerId.equals(nodeId)) {
			return;
		}
		isolatedPeers.remove(peerId);
		peerIds.remove(peerId);
		forgetPeer(peerId);
	}

	/**
	 * Intentional membership add for a same-DC peer (YAML/dynamic).
	 */
	public void configureLocalPeer(String peerId) {
		if (peerId == null || peerId.equals(nodeId)) {
			return;
		}
		isolatedPeers.remove(peerId);
		if (!peerIds.contains(peerId)) {
			peerIds.add(peerId);
		}
		onPeerAvailable(peerId);
	}

	/**
	 * Hold peer down (partition): clear live view and ignore inbound HELLO until {@link #healPeer}.
	 * Configured quorum unchanged — anti-solo.
	 */
	public void isolatePeer(String peerId) {
		if (peerId == null || peerId.equals(nodeId)) {
			return;
		}
		isolatedPeers.add(peerId);
		forgetPeer(peerId);
	}

	/**
	 * Clear isolate hold and accept live view again.
	 */
	public void healPeer(String peerId) {
		if (peerId == null || peerId.equals(nodeId)) {
			return;
		}
		isolatedPeers.remove(peerId);
	}

	/**
	 * Drop peer from phase/R view after disconnect; does not shrink configured quorum.
	 */
	public void forgetPeer(String peerId) {
		if (peerId == null || peerId.equals(nodeId)) {
			return;
		}
		peers.remove(peerId);
		voterEligible.remove(peerId);
		clearRemoteInflightForProposer(peerId);
		log.debug("Orchid forgot peer={}", peerId);
	}

	/**
	 * Drop pipeline accept tips registered for a disconnected proposer.
	 */
	private void clearRemoteInflightForProposer(String proposerId) {
		if (proposerId == null) {
			return;
		}
		remoteInflightExpected.entrySet().removeIf(e -> {
			final RemoteInflight inflight = e.getValue();
			return inflight != null && proposerId.equals(inflight.proposerId());
		});
	}

	public void markVoterEligible(String peerId) {
		if (peerId == null || peerId.equals(nodeId)) {
			return;
		}
		voterEligible.add(peerId);
	}

	public boolean isVoterEligible(String peerId) {
		return peerId != null && voterEligible.contains(peerId);
	}

	/**
	 * Kuramoto order parameter R over self + seen phase-coupled peers (best-effort snapshot).
	 * Lock-free: reads volatile phase fields only — do not call under Netty EL wait paths.
	 */
	public double orderParameterR() {
		return computeR();
	}

	/**
	 * Solo (R:=1) only when no local peers were configured (local DC N=1).
	 * With configured local peers, missing live local views never grants write admission.
	 * Remote-only learners do not block local sync; remote voters gate commit separately.
	 */
	public boolean isSynced() {
		if (peerIds.isEmpty()) {
			if (!multiDc().phaseCoupling() || multiDc().remoteVoterIds().isEmpty()) {
				return true;
			}
			if (livePhasePeerCount() == 0) {
				return false;
			}
			return orderParameterR() >= orderThreshold;
		}
		if (liveLocalPeerCount() == 0) {
			return false;
		}
		return orderParameterR() >= orderThreshold;
	}

	/**
	 * Phase-ranked proposer: lexicographically smallest nodeId among self and seen phase peers.
	 * Requires phase sync when local peers (or coupled remotes) are configured.
	 */
	public boolean isPhaseRankedProposer() {
		if (peerIds.isEmpty() && !(multiDc().phaseCoupling() && multiDc().hasRemoteVoters())) {
			return true;
		}
		if (!isSynced()) {
			return false;
		}
		String best = nodeId;
		for (PeerView peer : peers.values()) {
			if (peer.seen && countsForProposerRank(peer.id) && peer.id.compareTo(best) < 0) {
				best = peer.id;
			}
		}
		return nodeId.equals(best);
	}

	/**
	 * Current phase-ranked proposer id (self or seen peer); null if not synced.
	 * Lock-free metric / admission helper (no monitors).
	 */
	public String phaseRankedProposerId() {
		if (peerIds.isEmpty() && !(multiDc().phaseCoupling() && multiDc().hasRemoteVoters())) {
			return nodeId;
		}
		if (!isSynced()) {
			return null;
		}
		String best = nodeId;
		for (PeerView peer : peers.values()) {
			if (peer.seen && countsForProposerRank(peer.id) && peer.id.compareTo(best) < 0) {
				best = peer.id;
			}
		}
		return best;
	}

	/**
	 * JavaBean-style alias for {@link #phaseRankedProposerId()} (metrics / status JSON).
	 * Lock-free — no monitors.
	 */
	public String getPhaseRankedProposerId() {
		return phaseRankedProposerId();
	}

	/**
	 * Age in milliseconds of the current in-flight propose, or {@code 0} when idle.
	 * Backed by {@link #pendingProposeStartedAtMs}; lock-free — no monitors.
	 */
	public long pendingProposeAgeMs() {
		final long startedAt = pendingProposeStartedAtMs.get();
		if (startedAt <= 0L) {
			return 0L;
		}
		return Math.max(0L, System.currentTimeMillis() - startedAt);
	}

	/**
	 * Admit a propose onto the contiguous {@code prevOpSeq} chain and return the future + expected
	 * committed opSeq ({@code prevOpSeq + 1}). Callers use expected seq for OpLog holdback ordering.
	 */
	public AdmittedPropose appendAndAdmit(ReplicationOp op) {
		return appendAndAdmit(op, null);
	}

	/**
	 * Like {@link #appendAndAdmit(ReplicationOp)} but invokes {@code onExpectedOpSeq} under the
	 * admit lock before {@code requestCommitDrain} — required so OpLog holdback is registered before a
	 * solo/sync commit can complete and a pipelined successor can publish.
	 */
	public AdmittedPropose appendAndAdmit(ReplicationOp op, LongConsumer onExpectedOpSeq) {
		if (!running.get()) {
			return AdmittedPropose.failed(new IllegalStateException("OrchidNode not started"));
		}
		if (!isSynced()) {
			return AdmittedPropose.failed(new OrchidNotSyncedException("ORCHID R below threshold or configured peers unseen; cluster not phase-synced"));
		}
		if (!isPhaseRankedProposer()) {
			return AdmittedPropose.failed(new OrchidNotSyncedException("not phase-ranked proposer; active=" + phaseRankedProposerId()));
		}
		final long peerTip = maxSeenPeerCommittedSeq();
		final long localTip = lastCommittedSeq;
		if (localTip == 0L && peerTip > 0L) {
			return AdmittedPropose.failed(new OrchidNotSyncedException(OrchidTipPersistSupport.tipBehindMessage(localTip, peerTip)));
		}
		if (peerTip > localTip + maxProposeInFlight) {
			return AdmittedPropose.failed(new OrchidNotSyncedException(OrchidTipPersistSupport.tipBehindMessage(localTip, peerTip)));
		}
		try {
			proposeFlight.acquire();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return AdmittedPropose.failed(e);
		}
		boolean flightHandedOff = false;
		PendingPropose pendingPropose = null;
		long expectedOpSeq = 0L;
		try {
			final long prevOpSeq;
			final long proposeId;
			final long digest = digestOf(op);
			lock.lock();
			try {
				prevOpSeq = proposeChainPrev;
				proposeChainPrev = prevOpSeq + 1L;
				expectedOpSeq = prevOpSeq + 1L;
				proposeId = proposeSeq.incrementAndGet();
				pendingPropose = new PendingPropose(proposeId, digest, prevOpSeq, op, new CompletableFuture<>(), proposeFlight::release);
				pendingProposeStartedAtMs.compareAndSet(0L, System.currentTimeMillis());
				pending.put(proposeId, pendingPropose);
				pendingPropose.acks.put(nodeId, digest);
				// Enqueue under lock so FIFO matches contiguous prevOpSeq admit order.
				proposeSendQueue.offer(new OrchidProposeMessage(nodeId, proposeId, digest, prevOpSeq, op));
				// Holdback registration MUST happen before requestCommitDrain (solo path may complete
				// this future before appendAndAdmit returns); callers pass onExpectedOpSeq.
				if (onExpectedOpSeq != null && expectedOpSeq > 0L) {
					onExpectedOpSeq.accept(expectedOpSeq);
				}
			} finally {
				lock.unlock();
			}
			pumpProposeSendQueue();
			if (!peerIds.isEmpty() || (multiDc().phaseCoupling() && multiDc().hasRemoteVoters())) {
				broadcastOwnPhase(proposeId, digest);
			}
			requestCommitDrain();
			flightHandedOff = true;
			return new AdmittedPropose(pendingPropose.future, expectedOpSeq);
		} catch (RuntimeException ex) {
			if (pendingPropose != null) {
				failProposeChain(pendingPropose, ex);
				return new AdmittedPropose(pendingPropose.future, expectedOpSeq);
			}
			proposeFlight.release();
			return AdmittedPropose.failed(ex);
		} finally {
			if (!flightHandedOff && pendingPropose == null) {
				proposeFlight.release();
			}
		}
	}

	public CompletableFuture<Long> appendAndWaitCommit(ReplicationOp op) {
		return appendAndAdmit(op).future();
	}


	/**
	 * Propose admission result: commit future + expected durable opSeq for OpLog holdback.
	 *
	 * @author: GenCloud
	 * @date: 2026/03
	 * @since: 1.0
	 */
	public record AdmittedPropose(CompletableFuture<Long> future, long expectedOpSeq) {
		private static AdmittedPropose failed(Throwable err) {
			return new AdmittedPropose(CompletableFuture.failedFuture(err), 0L);
		}
	}

	/**
	 * Pipelined batch: admit all proposes without waiting between them, then await all commits.
	 * Contiguous prevOpSeq chain; one group wait for the caller.
	 */
	public CompletableFuture<long[]> appendAndWaitCommitBatch(List<ReplicationOp> ops) {
		if (ops == null || ops.isEmpty()) {
			return CompletableFuture.completedFuture(new long[0]);
		}
		@SuppressWarnings("unchecked")
		final CompletableFuture<Long>[] futures = new CompletableFuture[ops.size()];
		for (int i = 0; i < ops.size(); i++) {
			futures[i] = appendAndWaitCommit(ops.get(i));
		}
		return CompletableFuture.allOf(futures).thenApply(ignored -> {
			final long[] seqs = new long[futures.length];
			for (int i = 0; i < futures.length; i++) {
				seqs[i] = futures[i].join();
			}
			return seqs;
		});
	}

	public void onPhase(OrchidPhaseMessage msg) {
		if (msg == null || msg.nodeId().equals(nodeId) || isolatedPeers.contains(msg.nodeId())) {
			return;
		}
		// Digest ACK + commit drain off EL (may fireApply); preserve receive order via mailbox.
		orchidRpcQueue.submit(ThreadService.getLogicExecutor(), () -> {
			final PeerView view = peers.computeIfAbsent(msg.nodeId(), PeerView::new);
			view.phase = msg.phase();
			view.omega = msg.omega();
			view.lastCommittedSeq = msg.lastCommittedSeq();
			view.proposeId = msg.proposeId();
			view.digest = msg.digest();
			view.seen = true;
			if (msg.proposeId() > 0) {
				recordDigestAck(msg.nodeId(), msg.proposeId(), msg.digest());
			}
		});
	}

	private void recordDigestAck(String fromNodeId, long proposeId, long digest) {
		final PendingPropose p = pending.get(proposeId);
		if (p != null) {
			p.acks.put(fromNodeId, digest);
			requestCommitDrain();
		}
	}

	/**
	 * Seen peers (any DC) — connectivity / HELLO.
	 */
	public int livePeerCount() {
		int n = 0;
		for (PeerView peer : peers.values()) {
			if (peer.seen) {
				n++;
			}
		}
		return n;
	}

	/**
	 * Seen same-DC peers (local Kuramoto membership).
	 */
	public int liveLocalPeerCount() {
		int n = 0;
		for (String id : peerIds) {
			final PeerView peer = peers.get(id);
			if (peer != null && peer.seen) {
				n++;
			}
		}
		return n;
	}

	public void onPropose(OrchidProposeMessage msg) {
		if (msg == null || msg.proposerId().equals(nodeId)) {
			return;
		}
		// Must run before peer onCommit can advance lastCommittedSeq (same TCP order:
		// propose then commit). Off-EL mailbox raced commitApplyQueue → false prevOpSeq NACKs.
		peers.putIfAbsent(msg.proposerId(), new PeerView(msg.proposerId()));
		final long prev = msg.prevOpSeq();
		if (prev < lastCommittedSeq) {
			// Already accepted or already applied — late/reordered propose; do not fail proposer.
			if (remoteInflightExpected.containsKey(msg.proposeId()) || recentlyCommittedProposeIds.contains(msg.proposeId())) {
				return;
			}
			// Lagging new propose after tip advanced (e.g. crash mid-quorum) — fail-fast NACK.
			nackPrevOpSeq(msg, lastCommittedSeq, prev);
			return;
		}
		if (!isAcceptablePrevOpSeq(prev)) {
			// Re-check under lock: commitApplyQueue may have advanced tip since the first read
			// (TOCTOU produced bogus NACKs with expected==got under pipeline load).
			lock.lock();
			try {
				if (prev < lastCommittedSeq) {
					if (remoteInflightExpected.containsKey(msg.proposeId()) || recentlyCommittedProposeIds.contains(msg.proposeId())) {
						return;
					}
					nackPrevOpSeq(msg, lastCommittedSeq, prev);
					return;
				}
				if (!isAcceptablePrevOpSeq(prev)) {
					nackPrevOpSeq(msg, lastCommittedSeq, prev);
					return;
				}
			} finally {
				lock.unlock();
			}
		}
		final boolean crossDcPropose = !isLocalPeer(msg.proposerId());
		// Same-DC: phase-ranked gate. Cross-DC: hierarchical digest vote only (no local phase rank).
		if (!crossDcPropose) {
			final String ranked = phaseRankedProposerId();
			if (ranked != null && !ranked.equals(msg.proposerId())) {
				transport.sendNack(msg.proposerId(), new OrchidNackMessage(nodeId, msg.proposeId(), "not phase-ranked proposer; active=" + ranked));
				return;
			}
		}
		final long digest = digestOf(msg.op());
		if (digest != msg.digest()) {
			transport.sendNack(msg.proposerId(), new OrchidNackMessage(nodeId, msg.proposeId(), "digest mismatch"));
			return;
		}
		remoteInflightExpected.put(msg.proposeId(), new RemoteInflight(msg.proposerId(), prev + 1L, prev, msg.proposeId(), digest, msg.op()));
		// Single encode fan-out: proposer + local peers (and coupled remotes) see the digest ACK once.
		if (!crossDcPropose || multiDc().phaseCoupling()) {
			broadcastOwnPhase(msg.proposeId(), digest);
		} else {
			transport.sendPhase(msg.proposerId(), new OrchidPhaseMessage(nodeId, phase, omega, lastCommittedSeq, msg.proposeId(), digest));
		}
	}

	/**
	 * Highest {@code lastCommittedSeq} advertised by seen peers (phase / HELLO path).
	 */
	public long maxSeenPeerCommittedSeq() {
		long max = 0L;
		for (PeerView peer : peers.values()) {
			if (peer != null && peer.seen && peer.lastCommittedSeq > max) {
				max = peer.lastCommittedSeq;
			}
		}
		return max;
	}

	/**
	 * After region claim: locally commit contiguous cross-DC proposes we digested but never
	 * received a commit for (former Active crashed mid-broadcast). Prevents tip zeroing /
	 * silent drop of ACKed ops. Call from logic VT (claim path), never Netty EL.
	 *
	 * @return number of proposes sealed into the local tip
	 */
	public int sealBufferedCrossDcProposes() {
		if (!running.get()) {
			return 0;
		}
		return sealBufferedCrossDcProposesSerial();
	}

	private int sealBufferedCrossDcProposesSerial() {
		int sealed = 0;
		for (; ; ) {
			final RemoteInflight next = findContiguousBufferedInflight();
			if (next == null || next.op() == null) {
				return sealed;
			}
			final long opSeq = next.expectedOpSeq();
			final ReplicationOp committed = OpLogCodec.withChecksum(new ReplicationOp(next.op().domainType(), next.op().shard(), opSeq, next.op().type(), next.op().key(), next.op().value(), next.op().schemaEpoch(), next.op().checksum()));
			final OrchidCommitMessage synthetic = new OrchidCommitMessage(next.proposerId(), next.proposeId(), next.digest(), next.prevOpSeq(), opSeq, committed);
			final List<OrchidCommitMessage> toApply = new ArrayList<>(2);
			lock.lock();
			try {
				if (synthetic.opSeq() != lastCommittedSeq + 1L || synthetic.prevOpSeq() != lastCommittedSeq) {
					return sealed;
				}
				lastCommittedSeq = synthetic.opSeq();
				proposeChainPrev = Math.max(proposeChainPrev, lastCommittedSeq);
				toApply.add(synthetic);
				drainCommitHoldback(toApply);
			} finally {
				lock.unlock();
			}
			for (OrchidCommitMessage commit : toApply) {
				finishCommitApply(commit);
			}
			sealed += toApply.size();
		}
	}

	private RemoteInflight findContiguousBufferedInflight() {
		final long need = lastCommittedSeq + 1L;
		for (RemoteInflight inflight : remoteInflightExpected.values()) {
			if (inflight != null && inflight.expectedOpSeq() == need && inflight.op() != null) {
				return inflight;
			}
		}
		return null;
	}

	private void nackPrevOpSeq(OrchidProposeMessage msg, long expectedPrev, long gotPrev) {
		transport.sendNack(msg.proposerId(), new OrchidNackMessage(nodeId, msg.proposeId(), "prevOpSeq mismatch expected=" + expectedPrev + " got=" + gotPrev));
	}

	/**
	 * Peer commit entry: enqueued on logic VT mailbox so holdback drain + apply stay serial.
	 * Safe to call from Netty EL (decode then enqueue only).
	 */
	public void onCommit(OrchidCommitMessage msg) {
		if (msg == null) {
			return;
		}
		commitApplyQueue.submit(ThreadService.getLogicExecutor(), () -> onCommitSerial(msg));
	}

	/**
	 * Contiguous commit apply with holdback for pipelined reorder.
	 * Must run only via {@link #commitApplyQueue}.
	 */
	private void onCommitSerial(OrchidCommitMessage msg) {
		final List<OrchidCommitMessage> toApply = new ArrayList<>(4);
		lock.lock();
		try {
			if (msg.opSeq() <= lastCommittedSeq) {
				return;
			}
			if (msg.opSeq() != lastCommittedSeq + 1L) {
				bufferFutureCommit(msg);
				return;
			}
			if (msg.prevOpSeq() != lastCommittedSeq) {
				log.warn("ORCHID reject commit prevOpSeq mismatch expected={} got={}", lastCommittedSeq, msg.prevOpSeq());
				return;
			}
			lastCommittedSeq = msg.opSeq();
			toApply.add(msg);
			drainCommitHoldback(toApply);
		} finally {
			lock.unlock();
		}
		for (OrchidCommitMessage commit : toApply) {
			finishCommitApply(commit);
		}
	}

	/**
	 * Buffer a future commit when opSeq &gt; lastCommittedSeq+1 (pipelined broadcast reorder).
	 * Overflow / gap beyond holdback cap → fail-closed drop (repair catch-up).
	 */
	private void bufferFutureCommit(OrchidCommitMessage msg) {
		final int holdbackCap = Math.max(MIN_PROPOSE_IN_FLIGHT, maxProposeInFlight * COMMIT_HOLDBACK_CAP_FACTOR);
		final long gap = msg.opSeq() - lastCommittedSeq;
		if (gap > holdbackCap || commitHoldback.size() >= holdbackCap) {
			log.warn("ORCHID commit holdback overflow/gap expected={} got={} holdbackSize={} cap={}", lastCommittedSeq + 1L, msg.opSeq(), commitHoldback.size(), holdbackCap);
			return;
		}
		final OrchidCommitMessage prev = commitHoldback.putIfAbsent(msg.opSeq(), msg);
		if (prev == null) {
			log.debug("ORCHID buffered out-of-order commit opSeq={} waitingFor={}", msg.opSeq(), lastCommittedSeq + 1L);
		}
	}

	/**
	 * Caller holds {@link #lock}. Appends contiguous held commits to {@code toApply}.
	 */
	private void drainCommitHoldback(List<OrchidCommitMessage> toApply) {
		while (true) {
			final long next = lastCommittedSeq + 1L;
			final OrchidCommitMessage held = commitHoldback.remove(next);
			if (held == null) {
				return;
			}
			if (held.prevOpSeq() != lastCommittedSeq) {
				log.warn("ORCHID held commit prevOpSeq mismatch expected={} got={} opSeq={}", lastCommittedSeq, held.prevOpSeq(), held.opSeq());
				commitHoldback.put(next, held);
				return;
			}
			lastCommittedSeq = held.opSeq();
			toApply.add(held);
		}
	}

	private void finishCommitApply(OrchidCommitMessage msg) {
		remoteInflightExpected.remove(msg.proposeId());
		rememberCommittedPropose(msg.proposeId());
		final PendingPropose local = pending.remove(msg.proposeId());
		fireApply(msg.op());
		if (local != null) {
			local.future.complete(msg.opSeq());
			if (pending.isEmpty()) {
				clearPendingProposeTimestamp();
			}
			local.releaseFlight();
		}
	}

	private void rememberCommittedPropose(long proposeId) {
		if (proposeId <= 0L) {
			return;
		}
		recentlyCommittedProposeIds.add(proposeId);
		if (recentlyCommittedProposeIds.size() > RECENT_COMMITTED_PROPOSE_CAP) {
			recentlyCommittedProposeIds.clear();
			recentlyCommittedProposeIds.add(proposeId);
		}
	}

	public void onNack(OrchidNackMessage msg) {
		if (msg == null) {
			return;
		}
		remoteInflightExpected.remove(msg.proposeId());
		final PendingPropose p = pending.remove(msg.proposeId());
		if (p != null && !p.future.isDone()) {
			failProposeChain(p, new OrchidNotSyncedException("ORCHID NACK from " + msg.fromNodeId() + ": " + msg.reason()));
		}
	}

	private void tickLoop() {
		while (running.get()) {
			LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(tickMs));
			if (Thread.interrupted()) {
				return;
			}
			lock.lock();
			try {
				kuramotoStep(tickMs / 1000.0);
			} finally {
				lock.unlock();
			}
			broadcastOwnPhaseDigests();
			requestCommitDrain();
		}
	}

	private void kuramotoStep(double dt) {
		int live = 0;
		double sum = 0;
		for (PeerView peer : peers.values()) {
			if (!peer.seen || !countsForPhase(peer.id)) {
				continue;
			}
			live++;
			sum += Math.sin(peer.phase - phase);
		}
		if (live == 0) {
			phase = wrap(phase + omega * dt);
			return;
		}
		final double freeStep = omega * dt;
		final double pullStep = (coupling / live) * sum * dt;
		phase = wrap(phase + freeStep + pullStep);
	}

	private double computeR() {
		double sx = Math.cos(phase);
		double sy = Math.sin(phase);
		int n = 1;
		for (PeerView peer : peers.values()) {
			if (!peer.seen || !countsForPhase(peer.id)) {
				continue;
			}
			sx += Math.cos(peer.phase);
			sy += Math.sin(peer.phase);
			n++;
		}
		return Math.hypot(sx / n, sy / n);
	}

	/**
	 * Iterative contiguous commit drain (one stack frame). Replaces recursive
	 * {@code forEach(maybeCommit)} which StackOverflow'd under pipeline + sync fireApply.
	 */
	private void requestCommitDrain() {
		for (; ; ) {
			if (!commitDrainActive.compareAndSet(false, true)) {
				return;
			}
			try {
				while (true) {
					final PendingPropose next = findReadyContiguousPropose();
					if (next == null) {
						break;
					}
					commitOne(next);
				}
			} finally {
				commitDrainActive.set(false);
			}
			// Race: ACK arrived while drain flag was held and CAS failed on peer thread.
			if (findReadyContiguousPropose() == null) {
				return;
			}
		}
	}

	private PendingPropose findReadyContiguousPropose() {
		if (!isSynced() || !isPhaseRankedProposer()) {
			return null;
		}
		final long tip = lastCommittedSeq;
		PendingPropose ready = null;
		for (PendingPropose pendingPropose : pending.values()) {
			if (pendingPropose == null || pendingPropose.future.isDone()) {
				continue;
			}
			if (pendingPropose.prevOpSeq != tip) {
				continue;
			}
			if (!digestQuorumMet(pendingPropose)) {
				continue;
			}
			if (!remoteVoterDigestsMet(pendingPropose)) {
				scheduleRemoteVoterTimeout(pendingPropose);
				continue;
			}
			ready = pendingPropose;
			break;
		}
		return ready;
	}

	/**
	 * Commit one propose at tip. Does not recurse into successor drain.
	 * Solo / high-rate path: tickLoop and propose thread may both race;
	 * only one thread may advance lastCommittedSeq for this proposeId.
	 */
	private void commitOne(PendingPropose p) {
		if (!p.commitGate.compareAndSet(false, true)) {
			return;
		}
		final long opSeq;
		lock.lock();
		try {
			if (p.future.isDone()) {
				return;
			}
			if (p.prevOpSeq != lastCommittedSeq) {
				p.commitGate.set(false);
				return;
			}
			opSeq = lastCommittedSeq + 1;
			lastCommittedSeq = opSeq;
		} finally {
			lock.unlock();
		}
		final ReplicationOp committed = new ReplicationOp(p.op.domainType(), p.op.shard(), opSeq, p.op.type(), p.op.key(), p.op.value(), p.op.schemaEpoch(), p.op.checksum());
		final ReplicationOp withCs = OpLogCodec.withChecksum(committed);
		pending.remove(p.proposeId);
		remoteInflightExpected.remove(p.proposeId);
		rememberCommittedPropose(p.proposeId);
		if (pending.isEmpty()) {
			clearPendingProposeTimestamp();
		}
		// Local DDL already applied in SqlDdlExecutor before publishDdl; re-enter via
		// applyReplicatedDdl → execute on the same session mailbox deadlocks the join.
		// Peers still apply DDL through onCommit → finishCommitApply → fireApply.
		if (withCs.type() != ReplicationOpType.DDL) {
			fireApply(withCs);
		}
		p.future.complete(opSeq);
		transport.broadcastCommit(new OrchidCommitMessage(nodeId, p.proposeId, p.digest, p.prevOpSeq, opSeq, withCs));
		p.releaseFlight();
	}

	/**
	 * Accept contiguous tip or pipelined prev that matches an in-flight expected commit seq.
	 * Depth follows registered remote proposes (not a fixed window from lastCommitted) so a
	 * slow commit-apply on the peer does not NACK a healthy propose pipeline.
	 */
	private boolean isAcceptablePrevOpSeq(long prevOpSeq) {
		final long committed = lastCommittedSeq;
		if (prevOpSeq == committed) {
			return true;
		}
		if (prevOpSeq < committed) {
			return false;
		}
		for (PendingPropose pendingPropose : pending.values()) {
			if (pendingPropose != null && pendingPropose.prevOpSeq + 1L == prevOpSeq) {
				return true;
			}
		}
		for (RemoteInflight inflight : remoteInflightExpected.values()) {
			if (inflight != null && inflight.expectedOpSeq() == prevOpSeq) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Fail {@code failed} and every later link in the propose chain; rewind {@link #proposeChainPrev}.
	 */
	private void failProposeChain(PendingPropose failed, Throwable cause) {
		if (failed == null) {
			return;
		}
		final List<PendingPropose> toFail = new ArrayList<>();
		toFail.add(failed);
		for (PendingPropose pendingPropose : pending.values()) {
			if (pendingPropose != null && pendingPropose.proposeId != failed.proposeId && pendingPropose.prevOpSeq >= failed.prevOpSeq + 1L) {
				toFail.add(pendingPropose);
			}
		}
		toFail.sort((a, b) -> Long.compare(a.prevOpSeq, b.prevOpSeq));
		for (PendingPropose pendingPropose : toFail) {
			pending.remove(pendingPropose.proposeId, pendingPropose);
			remoteInflightExpected.remove(pendingPropose.proposeId);
			if (!pendingPropose.future.isDone()) {
				pendingPropose.future.completeExceptionally(cause);
			}
			pendingPropose.releaseFlight();
		}
		lock.lock();
		try {
			proposeChainPrev = lastCommittedSeq;
			for (PendingPropose pendingPropose : pending.values()) {
				if (pendingPropose != null) {
					final long tip = pendingPropose.prevOpSeq + 1L;
					if (tip > proposeChainPrev) {
						proposeChainPrev = tip;
					}
				}
			}
		} finally {
			lock.unlock();
		}
		if (pending.isEmpty()) {
			clearPendingProposeTimestamp();
		}
	}

	private void clearPendingProposeTimestamp() {
		pendingProposeStartedAtMs.set(0L);
	}

	private boolean digestQuorumMet(PendingPropose p) {
		int matched = 0;
		for (Map.Entry<String, Long> e : p.acks.entrySet()) {
			if (!isLocalPeer(e.getKey()) && !e.getKey().equals(nodeId)) {
				continue;
			}
			if (Objects.equals(e.getValue(), p.digest)) {
				matched++;
			}
		}
		final int clusterSize = Math.max(1, configuredVoterCount());
		return switch (digestQuorum) {
			case ALL_CONNECTED -> matched >= clusterSize;
			case MAJORITY -> matched > clusterSize / 2;
		};
	}

	private boolean remoteVoterDigestsMet(PendingPropose p) {
		if (!multiDc().hasRemoteVoters()) {
			return true;
		}
		for (String voterId : multiDc().remoteVoterIds()) {
			final Long ack = p.acks.get(voterId);
			if (!Objects.equals(ack, p.digest)) {
				return false;
			}
		}
		return true;
	}

	private void scheduleRemoteVoterTimeout(PendingPropose p) {
		if (p.remoteTimeoutScheduled.compareAndSet(false, true)) {
			final long proposeId = p.proposeId;
			ThreadService.getScheduledExecutor().schedule(() -> {
				final PendingPropose cur = pending.get(proposeId);
				if (cur == null || cur.future.isDone()) {
					return;
				}
				if (remoteVoterDigestsMet(cur)) {
					requestCommitDrain();
					return;
				}
				failProposeChain(cur, new OrchidNotSyncedException("remote voter digest timeout after " + multiDc().remoteVoterTimeoutMs() + "ms voters=" + multiDc().remoteVoterIds()));
			}, multiDc().remoteVoterTimeoutMs(), TimeUnit.MILLISECONDS);
		}
	}

	private void dispatchPropose(OrchidProposeMessage message) {
		final LinkedHashSet<String> targets = new LinkedHashSet<>(peerIds.size() + 8);
		targets.addAll(peerIds);
		targets.addAll(multiDc().remoteVoterIds());
		// Learners / other remote-DC peers must also buffer proposes for claim-time seal
		// (digest voters alone are not enough when a learner claims Active).
		for (PeerView peer : peers.values()) {
			if (peer != null && peer.seen && peer.id != null && !peer.id.equals(nodeId)) {
				targets.add(peer.id);
			}
		}
		for (String peerId : targets) {
			transport.sendPropose(peerId, message);
		}
	}

	/**
	 * Drain FIFO propose sends; CAS pump keeps wire order across concurrent admitters.
	 */
	private void pumpProposeSendQueue() {
		if (!proposeSendPump.compareAndSet(false, true)) {
			return;
		}
		try {
			for (; ; ) {
				OrchidProposeMessage msg;
				while ((msg = proposeSendQueue.poll()) != null) {
					dispatchPropose(msg);
				}
				proposeSendPump.set(false);
				if (proposeSendQueue.isEmpty()) {
					return;
				}
				if (!proposeSendPump.compareAndSet(false, true)) {
					return;
				}
			}
		} catch (RuntimeException ex) {
			proposeSendPump.set(false);
			throw ex;
		}
	}

	private void broadcastOwnPhase(long proposeId, long digest) {
		final List<OrchidPhaseDigest> digests = new ArrayList<>(1);
		digests.add(new OrchidPhaseDigest(proposeId, digest));
		sendPhaseDigests(digests);
	}

	/**
	 * Tick path: piggyback in-flight propose digests on one phase batch frame when &gt;1.
	 */
	private void broadcastOwnPhaseDigests() {
		final List<OrchidPhaseDigest> digests = new ArrayList<>();
		for (PendingPropose pendingPropose : pending.values()) {
			if (pendingPropose != null && !pendingPropose.future.isDone()) {
				digests.add(new OrchidPhaseDigest(pendingPropose.proposeId, pendingPropose.digest));
			}
		}
		if (digests.isEmpty()) {
			digests.add(new OrchidPhaseDigest(0L, 0L));
		}
		sendPhaseDigests(digests);
	}

	private void sendPhaseDigests(List<OrchidPhaseDigest> digests) {
		final OrchidPhaseBatchMessage batch = new OrchidPhaseBatchMessage(nodeId, phase, omega, lastCommittedSeq, digests);
		final List<String> targets = new ArrayList<>(peerIds.size() + (multiDc().phaseCoupling() ? multiDc().remoteVoterIds().size() : 0));
		targets.addAll(peerIds);
		if (multiDc().phaseCoupling()) {
			targets.addAll(multiDc().remoteVoterIds());
		}
		transport.sendPhaseDigestsToMany(targets, batch);
	}

	private boolean isLocalPeer(String peerId) {
		return peerId != null && (peerId.equals(nodeId) || peerIds.contains(peerId));
	}

	private boolean isPhaseCoupledPeer(String peerId) {
		return multiDc().phaseCoupling() && multiDc().remoteVoterIds().contains(peerId);
	}

	private boolean countsForPhase(String peerId) {
		return isLocalPeer(peerId) || isPhaseCoupledPeer(peerId);
	}

	private boolean countsForProposerRank(String peerId) {
		return countsForPhase(peerId);
	}

	private int livePhasePeerCount() {
		int n = 0;
		for (PeerView peer : peers.values()) {
			if (peer.seen && countsForPhase(peer.id)) {
				n++;
			}
		}
		return n;
	}

	private void persistCommitted(long seq) {
		OrchidTipPersistSupport.persistCommitted(durableStore, seq, log);
	}

	public void confirmPersisted(long seq) {
		persistCommitted(seq);
	}

	/**
	 * Advance consensus tip after catch-up / segment install (not a live commit broadcast).
	 * Without this, a late joiner keeps {@code lastCommittedSeq=0} and NACKs every propose
	 * while map/OpLog already hold higher seqs via {@link #confirmPersisted} alone.
	 */
	public void advanceCommittedTip(long seq) {
		if (seq <= 0L) {
			return;
		}
		lock.lock();
		try {
			if (seq > lastCommittedSeq) {
				lastCommittedSeq = seq;
				proposeChainPrev = Math.max(proposeChainPrev, seq);
			}
		} finally {
			lock.unlock();
		}
		persistCommitted(seq);
	}

	private void fireApply(ReplicationOp op) {
		for (Consumer<ReplicationOp> listener : applyListeners) {
			try {
				listener.accept(op);
			} catch (RuntimeException ex) {
				log.warn("Orchid apply listener failed: {}", ex.toString());
			}
		}
	}

	public static long digestOf(ReplicationOp op) {
		return OpLogCodec.checksumOf(op);
	}

	private static double wrap(double th) {
		final double twoPi = 2 * Math.PI;
		double x = th % twoPi;
		if (x < 0) {
			x += twoPi;
		}
		return x;
	}


	/**
	 * Peer-side pipeline tip for one remote propose (op retained for claim-time seal).
	 *
	 * @author: GenCloud
	 * @date: 2026/03
	 * @since: 1.0
	 */
	private record RemoteInflight(String proposerId, long expectedOpSeq, long prevOpSeq, long proposeId, long digest, ReplicationOp op) {
	}


	private static final class PeerView {
		final String id;
		volatile double phase;
		volatile double omega;
		volatile long lastCommittedSeq;
		volatile long proposeId;
		volatile long digest;
		volatile boolean seen;

		PeerView(String id) {
			this.id = id;
		}
	}


	private static final class PendingPropose {
		final long proposeId;
		final long digest;
		final long prevOpSeq;
		final ReplicationOp op;
		final CompletableFuture<Long> future;
		final Map<String, Long> acks = new ConcurrentHashMap<>();
		private final Runnable flightUnlock;
		private final AtomicBoolean flightReleased = new AtomicBoolean();
		private final AtomicBoolean remoteTimeoutScheduled = new AtomicBoolean();
		private final AtomicBoolean commitGate = new AtomicBoolean();

		PendingPropose(long proposeId, long digest, long prevOpSeq, ReplicationOp op, CompletableFuture<Long> future, Runnable flightUnlock) {
			this.proposeId = proposeId;
			this.digest = digest;
			this.prevOpSeq = prevOpSeq;
			this.op = op;
			this.future = future;
			this.flightUnlock = flightUnlock;
		}

		void releaseFlight() {
			if (flightUnlock != null && flightReleased.compareAndSet(false, true)) {
				flightUnlock.run();
			}
		}
	}

	public String getNodeId() {
		return this.nodeId;
	}

	public int getMaxProposeInFlight() {
		return this.maxProposeInFlight;
	}

	public long getLastCommittedSeq() {
		return this.lastCommittedSeq;
	}
}
