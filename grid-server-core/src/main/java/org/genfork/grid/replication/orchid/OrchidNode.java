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

import com.google.common.annotations.VisibleForTesting;
import org.genfork.grid.replication.OrchidNotSyncedException;
import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.diag.VisibilityDiag;
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
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
import java.util.function.BiConsumer;
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
	 * Product YAML keeps {@code max-propose-in-flight: 64} — this default matches that canon.
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
	 * On {@link #forgetPeer}: seal contiguous entries from that proposer first, then clear leftovers.
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
	 * At most one tick-enqueued drain on {@link #commitApplyQueue} (keeps Kuramoto tick free of fireApply/fsync).
	 */
	private final AtomicBoolean tickDrainEnqueued = new AtomicBoolean();
	/**
	 * Out-of-order commit holdback (opSeq → message). Cap =
	 * {@link #maxProposeInFlight} × {@link #COMMIT_HOLDBACK_CAP_FACTOR}.
	 * Pipeline propose may broadcast commits concurrently; apply stays contiguous.
	 */
	private final ConcurrentHashMap<Long, OrchidCommitMessage> commitHoldback = new ConcurrentHashMap<>();
	private static final int COMMIT_HOLDBACK_CAP_FACTOR = 4;
	/**
	 * Arm reason when tip could not advance after a successful apply (serial invariant break).
	 */
	private static final String TIP_ADVANCE_RACE_REASON = "tipAdvanceRace";
	/**
	 * Fail-closed local digest wait: without this, a mid-failover flap can leave an admitted
	 * propose wedged forever ({@code future.join} never completes — Elle-unsafe hang).
	 */
	private static final long LOCAL_PROPOSE_DIGEST_TIMEOUT_MS = 5_000L;
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
	private final List<BiConsumer<ReplicationOp, Boolean>> applyListeners = new CopyOnWriteArrayList<>();
	private final Set<String> voterEligible = ConcurrentHashMap.newKeySet();
	/**
	 * Peers held down for partition simulation / operator isolate — ignore HELLO until heal.
	 */
	private final Set<String> isolatedPeers = ConcurrentHashMap.newKeySet();
	private final AtomicLong proposeSeq = new AtomicLong();
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
	/**
	 * Armed when {@link #advanceCommittedTip} jumps tip without a live contiguous commit apply.
	 * Cleared only after map install / marker persist covers the armed tip (not newer live tip).
	 * RegionClaim fences writerEligible while set (GHA 37311157765 cell I tip-ok empty-map).
	 */
	private final AtomicBoolean installCatchUpRequired = new AtomicBoolean(false);
	/**
	 * Tip value at arm time; catch-up clear requires install progress through this seq.
	 */
	private final AtomicLong installCatchUpArmedTip = new AtomicLong(0L);
	/**
	 * Max opSeq installed (UPSERT/DELETE/marker) at or below {@link #installCatchUpArmedTip} while armed.
	 */
	private final AtomicLong installCatchUpMaxInstalled = new AtomicLong(0L);
	/**
	 * High-water of peer-advertised tips; survives {@link #forgetPeer} so ASYNC Hold
	 * cannot clear tip-catch-up after Active DC disconnect (Elle G2 / silent RPO read).
	 */
	private final AtomicLong maxObservedPeerCommittedSeq = new AtomicLong(0L);
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
		if (VisibilityDiag.enabled() && lastCommittedSeq > 0L) {
			VisibilityDiag.debugf("orchid.tipBoot",
					"localTip=%d proposeChainPrev=%d node=%s",
					lastCommittedSeq, proposeChainPrev, nodeId);
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

	/**
	 * Apply listener without remote-journal hint (tests / simple observers).
	 * Product path uses {@link #addApplyListener(BiConsumer)}.
	 */
	public void addApplyListener(Consumer<ReplicationOp> listener) {
		Objects.requireNonNull(listener, "listener");
		applyListeners.add((op, _) -> listener.accept(op));
	}

	/**
	 * @param listener second arg {@code journalRemote}: true for peer commit apply
	 *                 ({@link #applyContiguousAndConfirm}), false for local {@link #commitOne} (MutationRecorder journals)
	 */
	public void addApplyListener(BiConsumer<ReplicationOp, Boolean> listener) {
		applyListeners.add(Objects.requireNonNull(listener, "listener"));
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
		// Tip not yet known from this peer — writerEligible / admit stay false until phase tip
		// (unclean heal: lex-smaller lagging node was eligible with stale maxSeen → Elle G-single).
		view.tipAdvertised = false;
		if (VisibilityDiag.enabled()) {
			VisibilityDiag.debugf("orchid.tipAdvertised",
					"event=HELLO_CLEAR peer=%s awaitsTip=%s", peerId, awaitsPeerTipAdvertisement());
		}
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
	 * <p>
	 * Digested proposes from that peer are sealed on {@link #commitApplyQueue} before the
	 * buffer is cleared — otherwise unclean kill / isolate races wipe quorum-acked ops that
	 * never saw a commit broadcast (Elle G-single / list-append token loss).
	 */
	public void forgetPeer(String peerId) {
		if (peerId == null || peerId.equals(nodeId)) {
			return;
		}
		peers.remove(peerId);
		voterEligible.remove(peerId);
		scheduleSealBufferedThenClearProposer(peerId);
		log.debug("Orchid forgot peer={}", peerId);
		if (VisibilityDiag.enabled()) {
			VisibilityDiag.debugf(VisibilityDiag.WHERE_ORCHID_FORGET_PEER,
					"peer=%s localTip=%d maxSeenPeerTip=%d inflight=%d holdback=%d node=%s",
					peerId,
					lastCommittedSeq,
					maxSeenPeerCommittedSeq(),
					remoteInflightExpected.size(),
					commitHoldback.size(),
					nodeId);
		}
	}

	/**
	 * Seal contiguous buffered proposes <em>from</em> {@code proposerId} on the commit-apply
	 * mailbox, then drop leftovers for that proposer. Must not seal another live proposer's
	 * tip when a non-proposer peer merely flaps ({@code forgetPeer} of a follower).
	 * Safe from Netty EL (enqueue only).
	 */
	private void scheduleSealBufferedThenClearProposer(String proposerId) {
		if (!running.get()) {
			clearStaleRemoteInflightForProposer(proposerId);
			return;
		}
		commitApplyQueue.submit(ThreadService.getLogicExecutor(), () -> {
			sealBufferedProposesFromProposerSerial(proposerId);
			// Keep ahead-of-tip digested inflight — clearing them forged Elle G-single after unclean kill.
			clearStaleRemoteInflightForProposer(proposerId);
		});
	}

	/**
	 * Drop only stale (at/behind tip) pipeline tips for a disconnected proposer.
	 * Ahead-of-tip digested proposes stay until {@link #sealBufferedProposesSerial()} can advance tip
	 * contiguously (admit / claim drain). Must run on {@link #commitApplyQueue} or when stopped.
	 */
	private void clearStaleRemoteInflightForProposer(String proposerId) {
		if (proposerId == null) {
			return;
		}
		final long tip = lastCommittedSeq;
		remoteInflightExpected.entrySet().removeIf(e -> {
			final RemoteInflight inflight = e.getValue();
			if (inflight == null || !proposerId.equals(inflight.proposerId())) {
				return false;
			}
			return inflight.expectedOpSeq() <= tip;
		});
	}

	/**
	 * Test hook: count remote inflight proposes retained for {@code proposerId}.
	 */
	@VisibleForTesting
	public int testingRemoteInflightCountForProposer(String proposerId) {
		if (proposerId == null) {
			return 0;
		}
		int n = 0;
		for (RemoteInflight inflight : remoteInflightExpected.values()) {
			if (inflight != null && proposerId.equals(inflight.proposerId())) {
				n++;
			}
		}
		return n;
	}

	public void markVoterEligible(String peerId) {
		if (peerId == null || peerId.equals(nodeId)) {
			return;
		}
		voterEligible.add(peerId);
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
	 * <p>
	 * Also requires a majority-sized live cluster view so asymmetric {@code seen} after a kill
	 * cannot mint two concurrent proposers (Elle list-append dual-writer / G0).
	 */
	public boolean isPhaseRankedProposer() {
		if (peerIds.isEmpty() && !(multiDc().phaseCoupling() && multiDc().hasRemoteVoters())) {
			return true;
		}
		if (!isSynced()) {
			return false;
		}
		if (!hasMajorityLiveClusterView()) {
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
	 * {@code self + seen local peers} must cover a majority of the configured local cluster.
	 * Prevents split-brain phase-rank when each survivor briefly sees a disjoint peer set.
	 */
	private boolean hasMajorityLiveClusterView() {
		if (peerIds.isEmpty()) {
			return true;
		}

		final int configuredClusterSize = peerIds.size() + 1;
		final int majority = configuredClusterSize / 2 + 1;
		final int liveClusterSize = 1 + liveLocalPeerCount();
		return liveClusterSize >= majority;
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
		if (!hasMajorityLiveClusterView()) {
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
	 * Age in milliseconds of the oldest unfinished local propose, or {@code 0} when idle.
	 * Per-propose admit clock — lock-free; safe from Netty EL / VT.
	 */
	public long pendingProposeAgeMs() {
		final PendingPropose oldest = oldestUnfinishedPropose();
		if (oldest == null) {
			return 0L;
		}
		return Math.max(0L, System.currentTimeMillis() - oldest.admittedAtMs);
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
		if (awaitsPeerTipAdvertisement()) {
			return AdmittedPropose.failed(new OrchidNotSyncedException(
					"awaiting peer tip advertisement after reconnect; refuse admit until phase tip"));
		}
		// Failover-only: seal quorum-digested buffer before minting a new tip. Healthy path is O(1).
		if (!remoteInflightExpected.isEmpty()) {
			drainBufferedProposesBeforeAdmit();
		}
		final long peerTip = maxSeenPeerCommittedSeq();
		final long localTip = lastCommittedSeq;
		// Strict tip fence: never propose while any seen peer advertises a higher committed seq.
		// maxProposeInFlight remains the pipeline semaphore only — not a tip-behind allowance.
		if (peerTip > localTip) {
			return AdmittedPropose.failed(new OrchidNotSyncedException(
					OrchidTipPersistSupport.tipBehindMessage(localTip, peerTip)));
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
				pendingPropose = new PendingPropose(
						proposeId, digest, prevOpSeq, op, new CompletableFuture<>(), proposeFlight::release,
						System.currentTimeMillis());
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

	/**
	 * Single phase frame — same mailbox path as {@link #onPhaseBatch(List)}.
	 */
	public void onPhase(OrchidPhaseMessage msg) {
		if (msg == null) {
			return;
		}
		onPhaseBatch(List.of(msg));
	}

	/**
	 * Apply decoded phase frame(s) in one mailbox task, then one contiguous commit drain.
	 * Wire {@code ORCHID_PHASE_BATCH} and single {@code ORCHID_PHASE} share this path.
	 */
	public void onPhaseBatch(List<OrchidPhaseMessage> messages) {
		if (messages == null || messages.isEmpty()) {
			return;
		}
		// Digest ACK + commit drain off EL (may fireApply); preserve receive order via mailbox.
		orchidRpcQueue.submit(ThreadService.getLogicExecutor(), () -> {
			for (int i = 0; i < messages.size(); i++) {
				final OrchidPhaseMessage msg = messages.get(i);
				if (msg == null || msg.nodeId().equals(nodeId) || isolatedPeers.contains(msg.nodeId())) {
					continue;
				}
				applyPhaseMessage(msg);
			}
			requestCommitDrain();
		});
	}

	private void applyPhaseMessage(OrchidPhaseMessage msg) {
		final PeerView view = peers.computeIfAbsent(msg.nodeId(), PeerView::new);
		view.phase = msg.phase();
		view.omega = msg.omega();
		view.lastCommittedSeq = msg.lastCommittedSeq();
		view.proposeId = msg.proposeId();
		view.digest = msg.digest();
		view.seen = true;
		view.tipAdvertised = true;
		if (VisibilityDiag.enabled()) {
			VisibilityDiag.debugf("orchid.tipAdvertised",
					"event=PHASE_SET peer=%s lastCommittedSeq=%d awaitsTip=%s",
					msg.nodeId(), msg.lastCommittedSeq(), awaitsPeerTipAdvertisement());
		}
		noteObservedPeerCommittedSeq(msg.lastCommittedSeq());
		if (msg.proposeId() > 0L) {
			final PendingPropose pendingPropose = pending.get(msg.proposeId());
			if (pendingPropose != null) {
				pendingPropose.acks.put(msg.nodeId(), msg.digest());
			}
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
		boolean peerTipLagBuffer = false;
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
					if (prev > lastCommittedSeq) {
						// Local tip behind propose: buffer for claim-time seal — never NACK.
						// Lag NACK (expected=0 got=N) fails a healthy writer → Elle G1a / dirty-update / dups
						// under Multi-DC ASYNC after DC-link heal (learner tip still 0).
						peerTipLagBuffer = true;
					} else {
						nackPrevOpSeq(msg, lastCommittedSeq, prev);
						return;
					}
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
				transport.sendNack(msg.proposerId(), new OrchidNackMessage(
						nodeId, msg.proposeId(), OrchidNackCode.NOT_PHASE_RANKED, 0L, 0L, ranked));
				return;
			}
		}
		final long digest = digestOf(msg.op());
		if (digest != msg.digest()) {
			transport.sendNack(msg.proposerId(), new OrchidNackMessage(
					nodeId, msg.proposeId(), OrchidNackCode.DIGEST_MISMATCH, 0L, 0L, ""));
			return;
		}
		if (peerTipLagBuffer) {
			final int lagCap = Math.max(MIN_PROPOSE_IN_FLIGHT, maxProposeInFlight * COMMIT_HOLDBACK_CAP_FACTOR);
			if (remoteInflightExpected.size() >= lagCap) {
				log.warn("ORCHID drop ahead-of-tip propose (peer-lag buffer full) tip={} prev={} proposeId={} from={}",
						lastCommittedSeq, prev, msg.proposeId(), msg.proposerId());
				return;
			}
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
	 * Highest peer-advertised tip: live {@code seen} peers plus durable high-water
	 * retained across {@link #forgetPeer} (cross-DC ASYNC Active loss).
	 */
	public long maxSeenPeerCommittedSeq() {
		long max = maxObservedPeerCommittedSeq.get();
		for (PeerView peer : peers.values()) {
			if (peer != null && peer.seen && peer.tipAdvertised && peer.lastCommittedSeq > max) {
				max = peer.lastCommittedSeq;
			}
		}
		return max;
	}

	/**
	 * Highest tip among live tip-advertised peers outside the local peer set (cross-DC).
	 * <p>
	 * Region-claim await clear must use this — not {@link #maxSeenPeerCommittedSeq()}, which
	 * includes same-DC Hold tips and durable high-water. PR13-M / 1630: Hold cleared await when
	 * Active link flickered up while only Hold peer tips matched local tip → sticky {@code :ok}
	 * nil / lost-prefix on ASYNC lag.
	 */
	public long maxSeenLiveRemoteDcPeerCommittedSeq() {
		long max = 0L;
		for (PeerView peer : peers.values()) {
			if (peer == null || !peer.seen || !peer.tipAdvertised) {
				continue;
			}
			if (isLocalPeer(peer.id)) {
				continue;
			}
			if (peer.lastCommittedSeq > max) {
				max = peer.lastCommittedSeq;
			}
		}
		return max;
	}

	/**
	 * True when a live local (or phase-coupled) peer is channel-{@code seen} but has not yet
	 * sent a phase tip after {@link #onPeerAvailable}. Writer admission must fail-closed meanwhile.
	 */
	public boolean awaitsPeerTipAdvertisement() {
		for (PeerView peer : peers.values()) {
			if (peer == null || !peer.seen || peer.tipAdvertised) {
				continue;
			}
			if (isLocalPeer(peer.id) || isPhaseCoupledPeer(peer.id)) {
				return true;
			}
		}
		return false;
	}

	private void noteObservedPeerCommittedSeq(long committedSeq) {
		if (committedSeq <= 0L) {
			return;
		}
		maxObservedPeerCommittedSeq.updateAndGet(prev -> Math.max(prev, committedSeq));
	}

	/**
	 * Test hook: mark a peer seen with an advertised committed tip (tip-behind admit IT).
	 *
	 * @param peerId       peer node id (must be non-null)
	 * @param committedSeq tip to advertise ({@code >} local tip triggers fail-closed admit)
	 */
	@VisibleForTesting
	public void testingNotePeerCommittedSeq(String peerId, long committedSeq) {
		if (peerId == null || peerId.isEmpty() || committedSeq < 0L) {
			return;
		}
		final PeerView view = peers.computeIfAbsent(peerId, PeerView::new);
		view.seen = true;
		view.tipAdvertised = true;
		if (committedSeq > view.lastCommittedSeq) {
			view.lastCommittedSeq = committedSeq;
		}
		noteObservedPeerCommittedSeq(committedSeq);
	}

	/**
	 * Locally commit contiguous proposes we digested but never received a commit for
	 * (proposer crashed mid-broadcast — same-DC unclean kill or cross-DC Active loss).
	 * Prevents tip holes / silent drop of quorum-acked ops. Serializes on
	 * {@link #commitApplyQueue}; call from logic VT / claim path, never Netty EL.
	 *
	 * @return number of proposes sealed into the local tip
	 */
	public int sealBufferedCrossDcProposes() {
		if (!running.get()) {
			return 0;
		}
		final CompletableFuture<Integer> done = new CompletableFuture<>();
		commitApplyQueue.submit(ThreadService.getLogicExecutor(), () -> {
			try {
				done.complete(Integer.valueOf(sealBufferedProposesSerial()));
			} catch (Throwable t) {
				done.completeExceptionally(t);
			}
		});
		try {
			return done.join().intValue();
		} catch (CompletionException ex) {
			final Throwable cause = ex.getCause() == null ? ex : ex.getCause();
			if (cause instanceof RuntimeException re) {
				throw re;
			}
			throw new IllegalStateException("buffered propose seal failed", cause);
		}
	}

	/**
	 * Drain contiguous buffered proposes before admitting a new tip (same-DC unclean path).
	 * Must not run on Netty EL (joins {@link #commitApplyQueue}).
	 */
	private void drainBufferedProposesBeforeAdmit() {
		if (findContiguousBufferedInflight() == null) {
			return;
		}
		final CompletableFuture<Void> done = new CompletableFuture<>();
		commitApplyQueue.submit(ThreadService.getLogicExecutor(), () -> {
			try {
				sealBufferedProposesSerial();
				done.complete(null);
			} catch (Throwable t) {
				done.completeExceptionally(t);
			}
		});
		try {
			done.join();
		} catch (CompletionException ex) {
			final Throwable cause = ex.getCause() == null ? ex : ex.getCause();
			if (cause instanceof RuntimeException re) {
				throw re;
			}
			throw new IllegalStateException("buffered propose seal failed", cause);
		}
	}

	/**
	 * Seal any contiguous buffered propose (claim / admit drain).
	 * Must run only via {@link #commitApplyQueue} (or under its exclusive drain).
	 */
	private int sealBufferedProposesSerial() {
		return sealBufferedProposesFromProposerSerial(null);
	}

	/**
	 * Seal contiguous buffered proposes from {@code proposerId} only.
	 * {@code null} proposerId = any proposer (claim / admit drain).
	 * Must run only via {@link #commitApplyQueue}.
	 */
	private int sealBufferedProposesFromProposerSerial(String proposerId) {
		int sealed = 0;
		final long tipBefore = lastCommittedSeq;
		for (; ; ) {
			final RemoteInflight next = findContiguousBufferedInflight();
			if (next == null || next.op() == null) {
				if (VisibilityDiag.enabled() && sealed > 0) {
					VisibilityDiag.debugf(VisibilityDiag.WHERE_ORCHID_SEAL_BUFFERED,
							"proposerFilter=%s sealed=%d tipFrom=%d tipTo=%d node=%s",
							proposerId == null ? "*" : proposerId,
							sealed,
							tipBefore,
							lastCommittedSeq,
							nodeId);
				}
				return sealed;
			}
			if (proposerId != null && !proposerId.equals(next.proposerId())) {
				// Tip held by another proposer — do not mint their commit because a third peer flapped.
				if (VisibilityDiag.enabled() && sealed > 0) {
					VisibilityDiag.debugf(VisibilityDiag.WHERE_ORCHID_SEAL_BUFFERED,
							"proposerFilter=%s sealed=%d tipFrom=%d tipTo=%d stoppedOtherProposer=%s node=%s",
							proposerId,
							sealed,
							tipBefore,
							lastCommittedSeq,
							next.proposerId(),
							nodeId);
				}
				return sealed;
			}
			final long opSeq = next.expectedOpSeq();
			final ReplicationOp committed = OpLogCodec.withChecksum(new ReplicationOp(
					next.op().domainType(),
					next.op().shard(),
					opSeq,
					next.op().type(),
					next.op().key(),
					next.op().value(),
					next.op().schemaEpoch(),
					next.op().checksum()));
			final OrchidCommitMessage synthetic = new OrchidCommitMessage(
					next.proposerId(), next.proposeId(), next.digest(), next.prevOpSeq(), opSeq, committed);
			final List<OrchidCommitMessage> toApply = new ArrayList<>(2);
			lock.lock();
			try {
				if (synthetic.opSeq() != lastCommittedSeq + 1L || synthetic.prevOpSeq() != lastCommittedSeq) {
					if (VisibilityDiag.enabled() && sealed > 0) {
						VisibilityDiag.debugf(VisibilityDiag.WHERE_ORCHID_SEAL_BUFFERED,
								"proposerFilter=%s sealed=%d tipFrom=%d tipTo=%d stoppedGap opSeq=%d localTip=%d node=%s",
								proposerId == null ? "*" : proposerId,
								sealed,
								tipBefore,
								lastCommittedSeq,
								synthetic.opSeq(),
								lastCommittedSeq,
								nodeId);
					}
					return sealed;
				}
				toApply.add(synthetic);
				peekCommitHoldback(toApply);
			} finally {
				lock.unlock();
			}
			sealed += applyContiguousAndConfirm(toApply, tipBefore);
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
		// Only stale proposes (gotPrev < expectedPrev / tip). Peer tip-behind never reaches here —
		// onPropose buffers ahead-of-tip proposes for claim seal instead of NACKing.
		transport.sendNack(msg.proposerId(), new OrchidNackMessage(
				nodeId, msg.proposeId(), OrchidNackCode.STALE_PREV_OP_SEQ, expectedPrev, gotPrev, ""));
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
	 * Tip advances only after successful {@link #fireApply} (GHA 37346665476).
	 */
	private void onCommitSerial(OrchidCommitMessage msg) {
		final List<OrchidCommitMessage> toApply = new ArrayList<>(4);
		final long tipBefore;
		lock.lock();
		try {
			tipBefore = lastCommittedSeq;
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
			toApply.add(msg);
			peekCommitHoldback(toApply);
		} finally {
			lock.unlock();
		}
		applyContiguousAndConfirm(toApply, tipBefore);
	}

	/**
	 * One tip fsync for a contiguous peer-commit drain (not per-op on apply).
	 * Off {@link #commitApplyQueue}: GroupForceGate park must not stall consensus apply drain.
	 * OpLog bytes are already journaled in apply listeners when {@code journalRemote=true}.
	 */
	private void confirmPersistedAfterDrain(List<OrchidCommitMessage> toApply) {
		if (toApply == null || toApply.isEmpty()) {
			return;
		}
		long maxSeq = 0L;
		for (OrchidCommitMessage commit : toApply) {
			if (commit != null && commit.opSeq() > maxSeq) {
				maxSeq = commit.opSeq();
			}
		}
		if (maxSeq <= 0L) {
			return;
		}
		final long tipSeq = maxSeq;
		try {
			ThreadService.getNetworkExecutor().execute(() -> confirmPersisted(tipSeq));
		} catch (RuntimeException ex) {
			log.warn("ORCHID tip persist enqueue failed seq={}: {}", tipSeq, ex.toString());
			confirmPersisted(tipSeq);
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
	 * Caller holds {@link #lock}. Appends contiguous held commits to {@code toApply} without tip advance
	 * or holdback removal (removal happens after successful apply).
	 */
	private void peekCommitHoldback(List<OrchidCommitMessage> toApply) {
		long prev = toApply.get(toApply.size() - 1).opSeq();
		while (true) {
			final long next = prev + 1L;
			final OrchidCommitMessage held = commitHoldback.get(next);
			if (held == null) {
				return;
			}
			if (held.prevOpSeq() != prev) {
				log.warn("ORCHID held commit prevOpSeq mismatch expected={} got={} opSeq={}", prev, held.prevOpSeq(), held.opSeq());
				return;
			}
			toApply.add(held);
			prev = held.opSeq();
		}
	}

	/**
	 * Apply then tip: each commit {@link #fireApply}s before {@code lastCommittedSeq} advances.
	 * On failure: purge holdback from the failed seq; tip stays honest (no tip-before-apply).
	 *
	 * @return number of commits that advanced tip
	 */
	private int applyContiguousAndConfirm(List<OrchidCommitMessage> toApply, long tipBefore) {
		if (toApply == null || toApply.isEmpty()) {
			return 0;
		}
		final List<OrchidCommitMessage> applied = new ArrayList<>(toApply.size());
		for (OrchidCommitMessage commit : toApply) {
			final PendingPropose local = pending.remove(commit.proposeId());
			try {
				fireApply(commit.op(), true);
			} catch (RuntimeException ex) {
				if (local != null) {
					local.future.completeExceptionally(ex);
					local.releaseFlight();
				}
				noteApplyFailed(commit, ex);
				purgeHoldbackFrom(commit.opSeq());
				// apply-then-tip: tip stays honest; peer tip-behind / repair catch-up fences writers.
				// Do not arm installCatchUp here — missing-domain failures never install that armed tip.
				break;
			}
			if (!advanceTipAfterApply(commit)) {
				if (local != null) {
					local.future.completeExceptionally(new OrchidNotSyncedException(
							"ORCHID tip advance rejected after apply opSeq=" + commit.opSeq()));
					local.releaseFlight();
				}
				purgeHoldbackFrom(commit.opSeq());
				// Only fence when tip stayed behind a successfully applied op (dishonest gap).
				// If tip already covers opSeq, this is a duplicate apply path — do not arm forever.
				if (lastCommittedSeq < commit.opSeq()) {
					armInstallCatchUpRequired(TIP_ADVANCE_RACE_REASON, tipBefore, commit.opSeq());
				}
				break;
			}
			remoteInflightExpected.remove(commit.proposeId());
			rememberCommittedPropose(commit.proposeId());
			if (local != null) {
				// Complete only after tip advanced — else client admit races with dishonest prevOpSeq.
				local.future.complete(commit.opSeq());
				local.releaseFlight();
			}
			applied.add(commit);
			if (VisibilityDiag.enabled()) {
				final ReplicationOp op = commit.op();
				VisibilityDiag.debugf(VisibilityDiag.WHERE_ORCHID_COMMIT_TIP,
						"opSeq=%d tipBefore=%d tipAfter=%d domain=%s shard=%d type=%s node=%s",
						commit.opSeq(),
						tipBefore,
						lastCommittedSeq,
						op == null ? "null" : op.domainType(),
						op == null ? -1 : op.shard(),
						op == null ? "null" : op.type(),
						nodeId);
			}
		}
		confirmPersistedAfterDrain(applied);
		if (VisibilityDiag.enabled() && applied.size() > 1) {
			final OrchidCommitMessage first = applied.get(0);
			VisibilityDiag.debugf("orchid.commitDrain",
					"batch=%d tipFrom=%d tipTo=%d firstType=%s node=%s",
					applied.size(),
					tipBefore,
					lastCommittedSeq,
					first.op() == null ? "null" : first.op().type(),
					nodeId);
		}
		return applied.size();
	}

	private boolean advanceTipAfterApply(OrchidCommitMessage commit) {
		lock.lock();
		try {
			if (commit.opSeq() != lastCommittedSeq + 1L || commit.prevOpSeq() != lastCommittedSeq) {
				log.warn("ORCHID tip advance rejected after apply expectedTip+1={} got={} prevExpected={} prevGot={}",
						lastCommittedSeq + 1L, commit.opSeq(), lastCommittedSeq, commit.prevOpSeq());
				return false;
			}
			lastCommittedSeq = commit.opSeq();
			proposeChainPrev = Math.max(proposeChainPrev, lastCommittedSeq);
			commitHoldback.remove(commit.opSeq());
			return true;
		} finally {
			lock.unlock();
		}
	}

	private void purgeHoldbackFrom(long fromOpSeqInclusive) {
		lock.lock();
		try {
			commitHoldback.entrySet().removeIf(entry -> entry.getKey() >= fromOpSeqInclusive);
		} finally {
			lock.unlock();
		}
	}

	private void noteApplyFailed(OrchidCommitMessage commit, RuntimeException ex) {
		final ReplicationOp op = commit == null ? null : commit.op();
		log.warn("ORCHID apply failed opSeq={} domain={} tip={}: {}",
				commit == null ? -1L : commit.opSeq(),
				op == null ? "null" : op.domainType(),
				lastCommittedSeq,
				ex.toString());
		if (VisibilityDiag.enabled()) {
			VisibilityDiag.debugf(VisibilityDiag.WHERE_ORCHID_APPLY_FAILED,
					"opSeq=%d domain=%s shard=%d tip=%d reason=%s node=%s",
					commit == null ? -1L : commit.opSeq(),
					op == null ? "null" : op.domainType(),
					op == null ? -1 : op.shard(),
					lastCommittedSeq,
					ex.getClass().getSimpleName(),
					nodeId);
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
		if (msg == null || msg.code() == null) {
			return;
		}
		// Every wire NACK code is fail-closed for the proposer. Peer tip-behind is not a NACK
		// (buffered in onPropose) — no string parsing / ignore heuristics on this path.
		remoteInflightExpected.remove(msg.proposeId());
		final PendingPropose p = pending.remove(msg.proposeId());
		if (p != null && !p.future.isDone()) {
			failProposeChain(p, new OrchidNotSyncedException(
					"ORCHID NACK from " + msg.fromNodeId() + ": " + msg.detailMessage()));
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
			expireStaleLocalProposes();
			scheduleCommitDrainFromTick();
		}
	}

	/**
	 * Offload primary commit drain from the Kuramoto tick thread.
	 * <p>
	 * {@link #commitOne} → {@code fireApply} on the tick thread starved phase coupling
	 * under MIX/QG (R dip → formerly wedged tip when drain re-checked {@code isSynced}).
	 * Admit/ACK paths still call {@link #requestCommitDrain()} directly on logic VT.
	 */
	private void scheduleCommitDrainFromTick() {
		if (pending.isEmpty()) {
			return;
		}
		if (!tickDrainEnqueued.compareAndSet(false, true)) {
			return;
		}
		commitApplyQueue.submit(ThreadService.getLogicExecutor(), () -> {
			try {
				requestCommitDrain();
			} finally {
				tickDrainEnqueued.set(false);
			}
		});
	}

	/**
	 * Fail the oldest admitted propose that has not committed within
	 * {@link #LOCAL_PROPOSE_DIGEST_TIMEOUT_MS} (local-DC; remote voters use WAN timeout).
	 * <p>
	 * Age is per-propose admit time. A single busy-window clock (empty→non-empty) is wrong under
	 * pipelined load: {@code pending} stays non-empty for the whole WRITE/MIX window and would
	 * false-fail a young tip head. Elle-unsafe hang if an admitted future never completes.
	 */
	private void expireStaleLocalProposes() {
		final PendingPropose oldest = oldestUnfinishedPropose();
		if (oldest == null) {
			return;
		}
		final long ageMs = Math.max(0L, System.currentTimeMillis() - oldest.admittedAtMs);
		if (ageMs < LOCAL_PROPOSE_DIGEST_TIMEOUT_MS) {
			return;
		}
		failProposeChain(oldest, new OrchidNotSyncedException(
				"local propose digest timeout after " + LOCAL_PROPOSE_DIGEST_TIMEOUT_MS
						+ "ms ageMs=" + ageMs + " proposeId=" + oldest.proposeId));
	}

	/**
	 * Oldest unfinished local propose by {@code proposeId} (tip-chain head under contiguous admit).
	 */
	private PendingPropose oldestUnfinishedPropose() {
		PendingPropose oldest = null;
		for (PendingPropose pendingPropose : pending.values()) {
			if (pendingPropose == null || pendingPropose.future.isDone()) {
				continue;
			}
			if (oldest == null || pendingPropose.proposeId < oldest.proposeId) {
				oldest = pendingPropose;
			}
		}
		return oldest;
	}

	@SuppressWarnings("NonAtomicOperationOnVolatileField")
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
	 * <p>
	 * Order per tip step is fixed for durability/visibility under kill: local apply → join
	 * complete → commit broadcast. Completing/broadcasting before apply widened the unclean
	 * window (Jepsen 1dc-chaos Elle {@code G-single-item-realtime}: append :ok then read nil).
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
		// Do not re-check isPhaseRankedProposer / isSynced here: admit already required both.
		// A post-admit rank flap or brief R dip must not wedge quorum-acked proposes into
		// local propose digest timeout (MIX/QG load evidence: stuck tip → Elle-unsafe :fail storm).
		// New tips stay fail-closed in appendAndAdmit (isSynced + phase-rank + tip fence).
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
	 * Test hook: run contiguous commit drain (post-admit path).
	 */
	@VisibleForTesting
	public void testingRequestCommitDrain() {
		requestCommitDrain();
	}

	/**
	 * Test hook: record a digest ACK without triggering drain (characterization).
	 */
	@VisibleForTesting
	public boolean testingPutDigestAck(String fromNodeId, long proposeId, long digest) {
		final PendingPropose pendingPropose = pending.get(proposeId);
		if (pendingPropose == null) {
			return false;
		}
		pendingPropose.acks.put(fromNodeId, digest);
		return true;
	}

	/**
	 * Test hook: clear live seen bit without isolate (keeps ACK path open).
	 */
	@VisibleForTesting
	public void testingMarkPeerUnseen(String peerId) {
		if (peerId == null) {
			return;
		}
		final PeerView view = peers.get(peerId);
		if (view != null) {
			view.seen = false;
		}
	}

	/**
	 * Test hook: Op digest used for quorum ACKs.
	 */
	@VisibleForTesting
	public long testingDigestOf(ReplicationOp op) {
		return digestOf(op);
	}

	/**
	 * Test hook: backdate admit wall-clock for digest-timeout characterization.
	 */
	@VisibleForTesting
	public boolean testingBackdateAdmit(long proposeId, long admittedAtMs) {
		final PendingPropose pendingPropose = pending.get(proposeId);
		if (pendingPropose == null) {
			return false;
		}
		pendingPropose.admittedAtMs = admittedAtMs;
		return true;
	}

	/**
	 * Test hook: run local digest-timeout expiry (Kuramoto tick path).
	 */
	@VisibleForTesting
	public void testingExpireStaleLocalProposes() {
		expireStaleLocalProposes();
	}

	/**
	 * Test hook: whether a remote propose is buffered (claim-seal / peer-lag path).
	 */
	@VisibleForTesting
	public boolean testingRemoteInflightContains(long proposeId) {
		return remoteInflightExpected.containsKey(proposeId);
	}

	/**
	 * Test hook: oldest unfinished local propose id, or {@code -1} when idle.
	 */
	@VisibleForTesting
	public long testingOldestPendingProposeId() {
		final PendingPropose oldest = oldestUnfinishedPropose();
		return oldest == null ? -1L : oldest.proposeId;
	}

	/**
	 * Commit one propose at tip: apply locally, complete join, broadcast.
	 * Does not recurse into successor local drain ({@link #requestCommitDrain} loop).
	 * <p>
	 * After a successful local tip step, drain peer {@link #commitHoldback} — same contiguous
	 * rule as {@link #onCommitSerial}. GHA 37323133691 cell D: local tip stepped to 17 while
	 * holdback held 18–20 → permanent tip-behind / writer-eligible-timeout.
	 */
	private void commitOne(PendingPropose p) {
		if (!p.commitGate.compareAndSet(false, true)) {
			return;
		}
		final long opSeq;
		final long tipBefore;
		lock.lock();
		try {
			if (p.future.isDone()) {
				return;
			}
			if (p.prevOpSeq != lastCommittedSeq) {
				p.commitGate.set(false);
				return;
			}
			tipBefore = lastCommittedSeq;
			opSeq = lastCommittedSeq + 1;
			// Tip advances only after successful fireApply (or DDL skip).
		} finally {
			lock.unlock();
		}
		final ReplicationOp committed = new ReplicationOp(
				p.op.domainType(), p.op.shard(), opSeq, p.op.type(), p.op.key(), p.op.value(),
				p.op.schemaEpoch(), p.op.checksum());
		final ReplicationOp withCs = OpLogCodec.withChecksum(committed);
		// Local DDL already applied in SqlDdlExecutor before publishDdl; re-enter via
		// applyReplicatedDdl → execute on the same session mailbox deadlocks the join.
		// Peers still apply DDL through onCommit → applyContiguousAndConfirm → fireApply.
		if (withCs.type() != ReplicationOpType.DDL) {
			try {
				fireApply(withCs, false);
			} catch (RuntimeException applyEx) {
				noteApplyFailed(new OrchidCommitMessage(nodeId, p.proposeId, p.digest, p.prevOpSeq, opSeq, withCs), applyEx);
				// Tip not advanced — fail join only; no installCatchUp arm (nothing dishonest to cover).
				p.future.completeExceptionally(applyEx);
				p.releaseFlight();
				p.commitGate.set(false);
				return;
			}
		}
		lock.lock();
		try {
			if (lastCommittedSeq != tipBefore || p.prevOpSeq != tipBefore) {
				p.future.completeExceptionally(new OrchidNotSyncedException(
						"ORCHID tip moved during local apply expected=" + tipBefore + " got=" + lastCommittedSeq));
				p.releaseFlight();
				p.commitGate.set(false);
				return;
			}
			lastCommittedSeq = opSeq;
			proposeChainPrev = Math.max(proposeChainPrev, opSeq);
		} finally {
			lock.unlock();
		}
		pending.remove(p.proposeId);
		remoteInflightExpected.remove(p.proposeId);
		rememberCommittedPropose(p.proposeId);
		if (VisibilityDiag.enabled()) {
			VisibilityDiag.debugf(VisibilityDiag.WHERE_ORCHID_COMMIT_TIP,
					"opSeq=%d tipBefore=%d tipAfter=%d domain=%s shard=%d type=%s node=%s",
					opSeq,
					tipBefore,
					lastCommittedSeq,
					withCs.domainType(),
					withCs.shard(),
					withCs.type(),
					nodeId);
		}
		p.future.complete(opSeq);
		transport.broadcastCommit(new OrchidCommitMessage(nodeId, p.proposeId, p.digest, p.prevOpSeq, opSeq, withCs));
		p.releaseFlight();
		// Peer commits may already sit in holdback (pipeline reorder / peer seal).
		final List<OrchidCommitMessage> holdbackDrain = new ArrayList<>(4);
		lock.lock();
		try {
			final OrchidCommitMessage nextHeld = commitHoldback.get(lastCommittedSeq + 1L);
			if (nextHeld != null && nextHeld.prevOpSeq() == lastCommittedSeq) {
				holdbackDrain.add(nextHeld);
				peekCommitHoldback(holdbackDrain);
			}
		} finally {
			lock.unlock();
		}
		if (!holdbackDrain.isEmpty()) {
			applyContiguousAndConfirm(holdbackDrain, tipBefore);
		}
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
		toFail.sort(Comparator.comparingLong(a -> a.prevOpSeq));
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

	private LinkedHashSet<String> proposeDispatchTargets() {
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
		return targets;
	}

	/**
	 * Drain FIFO propose sends; CAS pump keeps wire order across concurrent admitters.
	 * One coalesce flush per pump drain (avoids per-propose writeAndFlush under pipeline).
	 */
	private void pumpProposeSendQueue() {
		if (!proposeSendPump.compareAndSet(false, true)) {
			return;
		}
		try {
			for (; ; ) {
				final ArrayList<OrchidProposeMessage> batch = new ArrayList<>();
				OrchidProposeMessage msg;
				while ((msg = proposeSendQueue.poll()) != null) {
					batch.add(msg);
				}
				if (!batch.isEmpty()) {
					transport.sendProposeBatchToMany(proposeDispatchTargets(), batch);
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
	 * <p>
	 * Tip jump arms {@link #isInstallCatchUpRequired()} until map install / marker persist
	 * covers tip (or live commit apply clears). Prevents tip-ok empty-map writerEligible.
	 */
	public void advanceCommittedTip(long seq) {
		advanceCommittedTip(seq, "unspecified");
	}

	/**
	 * @param reason diag tag for {@link VisibilityDiag#WHERE_ORCHID_TIP_ADVANCE} (ship / persistAndAck / test)
	 */
	public void advanceCommittedTip(long seq, String reason) {
		if (seq <= 0L) {
			return;
		}
		long fromTip = 0L;
		boolean jumped = false;
		lock.lock();
		try {
			fromTip = lastCommittedSeq;
			if (seq > lastCommittedSeq) {
				lastCommittedSeq = seq;
				proposeChainPrev = Math.max(proposeChainPrev, seq);
				jumped = true;
			}
		} finally {
			lock.unlock();
		}
		persistCommitted(seq);
		if (jumped) {
			armInstallCatchUpRequired(reason == null ? "unspecified" : reason, fromTip, seq);
		}
		if (VisibilityDiag.enabled() && jumped) {
			VisibilityDiag.debugf(VisibilityDiag.WHERE_ORCHID_TIP_ADVANCE,
					"reason=%s from=%d to=%d delta=%d node=%s",
					reason == null ? "unspecified" : reason,
					fromTip,
					seq,
					seq - fromTip,
					nodeId);
		}
	}

	/**
	 * {@code true} when tip was advanced without proven working-set install catch-up.
	 * RegionClaim keeps writerEligible false while armed (cell I tip-ok empty-map).
	 */
	public boolean isInstallCatchUpRequired() {
		return installCatchUpRequired.get();
	}

	/**
	 * Clear fence after map install or marker persist when catch-up covers the armed tip.
	 * Newer live ops ({@code opSeq > armedTip}) must not clear — otherwise tip-ok empty-map
	 * can become writerEligible while the dishonest prefix is still missing.
	 */
	public void noteInstallCatchUpProgress(long opSeq) {
		if (opSeq <= 0L || !installCatchUpRequired.get()) {
			return;
		}
		final long armedTip = installCatchUpArmedTip.get();
		if (armedTip <= 0L || opSeq > armedTip) {
			return;
		}
		final long covered = installCatchUpMaxInstalled.updateAndGet(cur -> Math.max(cur, opSeq));
		if (covered >= armedTip) {
			clearInstallCatchUpRequired("installCovered");
		}
	}

	/**
	 * Test hook: whether install catch-up fence is armed.
	 */
	@VisibleForTesting
	public boolean testingInstallCatchUpRequired() {
		return installCatchUpRequired.get();
	}

	private void armInstallCatchUpRequired(String reason, long fromTip, long toTip) {
		installCatchUpArmedTip.set(toTip);
		installCatchUpMaxInstalled.set(0L);
		installCatchUpRequired.set(true);
		if (VisibilityDiag.enabled()) {
			VisibilityDiag.debugf(VisibilityDiag.WHERE_ORCHID_INSTALL_CATCH_UP,
					"event=arm reason=%s from=%d to=%d node=%s",
					reason,
					fromTip,
					toTip,
					nodeId);
		}
	}

	private void clearInstallCatchUpRequired(String reason) {
		if (!installCatchUpRequired.compareAndSet(true, false)) {
			return;
		}
		installCatchUpArmedTip.set(0L);
		installCatchUpMaxInstalled.set(0L);
		if (VisibilityDiag.enabled()) {
			VisibilityDiag.debugf(VisibilityDiag.WHERE_ORCHID_INSTALL_CATCH_UP,
					"event=clear reason=%s tip=%d node=%s",
					reason,
					lastCommittedSeq,
					nodeId);
		}
	}

	private void fireApply(ReplicationOp op, boolean journalRemote) {
		RuntimeException firstFailure = null;
		for (BiConsumer<ReplicationOp, Boolean> listener : applyListeners) {
			try {
				listener.accept(op, journalRemote);
			} catch (RuntimeException ex) {
				log.warn("Orchid apply listener failed: {}", ex.toString());
				if (firstFailure == null) {
					firstFailure = ex;
				}
			}
		}
		if (firstFailure != null) {
			// Fail-closed: never complete propose join as success after map apply failed
			// (silent swallow forged Jepsen :ok append then nil read / G2-item).
			throw firstFailure;
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
		/**
		 * Set on phase tip (or test tip hook). Cleared on {@link OrchidNode#onPeerAvailable}
		 * so HELLO-only peers cannot clear tip-catch-up / mint writerEligible.
		 */
		volatile boolean tipAdvertised;

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
		/**
		 * Wall-clock admit time for {@link OrchidNode#expireStaleLocalProposes()}.
		 * Tests may backdate via {@link OrchidNode#testingBackdateAdmit(long, long)}.
		 */
		volatile long admittedAtMs;
		private final Runnable flightUnlock;
		private final AtomicBoolean flightReleased = new AtomicBoolean();
		private final AtomicBoolean remoteTimeoutScheduled = new AtomicBoolean();
		private final AtomicBoolean commitGate = new AtomicBoolean();

		PendingPropose(
				long proposeId,
				long digest,
				long prevOpSeq,
				ReplicationOp op,
				CompletableFuture<Long> future,
				Runnable flightUnlock,
				long admittedAtMs
		) {
			this.proposeId = proposeId;
			this.digest = digest;
			this.prevOpSeq = prevOpSeq;
			this.op = op;
			this.future = future;
			this.flightUnlock = flightUnlock;
			this.admittedAtMs = admittedAtMs;
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
