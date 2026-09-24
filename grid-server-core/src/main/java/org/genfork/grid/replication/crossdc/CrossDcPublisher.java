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
package org.genfork.grid.replication.crossdc;

import com.google.common.annotations.VisibleForTesting;
import org.genfork.grid.replication.ReplicationNodeState;
import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.OpLogSegment;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.swarm.AdaptiveReplicaSwarm;
import org.genfork.grid.replication.transport.ReplicationPeer;
import org.genfork.grid.replication.tx.TxEnvelopeCoordinator;
import org.genfork.grid.replication.tx.TxUnitShardBuffer;
import org.genfork.grid.threading.ThreadService;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Ships op batches to peers in other DCs over Netty.
 * <p>
 * {@link CrossDcMode#ASYNC_SHIP}: ships committed ops to remote {@code learners} when configured,
 * otherwise all remote-DC peers (async).
 * {@link CrossDcMode#SYNC_VOTERS_ACROSS_DC}: ships to configured remote <em>voters</em> (and async to
 * {@code learners}); ORCHID commit already waited for remote digests — this path delivers OpLog.
 * <p>
 * TX-aware: buffers through open {@code TX_BEGIN}…{@code TX_COMMIT}/{@code TX_ABORT} units and only
 * ships complete prefixes (fail-closed — never mid-unit dirty). Multi-stream envelopes
 * ({@link TxEnvelopeCoordinator}) ship only after all participating shards commit.
 *
 * @author: GenCloud
 * @date: 2026/06
 * @since: 1.0
 */
public class CrossDcPublisher {
	private final ReplicationNodeState nodeState;
	private final CrossDcMode mode;
	private final int batchMaxOps;
	private final long batchMaxWaitMs;
	private final Set<String> learnerIds;
	private final Set<String> voterIds;
	private final CrossDcMetrics metrics = new CrossDcMetrics();
	private final boolean requireRemoteAck;
	private final long remoteAckTimeoutMs;

	private final List<ReplicationPeer> remotePeers = new CopyOnWriteArrayList<>();
	private final Map<String, TxUnitShardBuffer> buffers = new ConcurrentHashMap<>();
	private final Map<String, CompletableFuture<Void>> remoteAckWaiters = new ConcurrentHashMap<>();
	private BiConsumer<ReplicationPeer, OpLogSegment> shipper;
	private volatile AdaptiveReplicaSwarm swarm;
	private volatile TxEnvelopeCoordinator envelopeCoordinator = new TxEnvelopeCoordinator();
	private volatile boolean running;

	public CrossDcPublisher(ReplicationNodeState nodeState,
	                        CrossDcMode mode,
	                        int batchMaxOps,
	                        long batchMaxWaitMs,
	                        boolean requireRemoteAck,
	                        List<String> learnerIds) {
		this(nodeState, mode, batchMaxOps, batchMaxWaitMs, requireRemoteAck, 5_000L, learnerIds, List.of());
	}

	public CrossDcPublisher(ReplicationNodeState nodeState,
	                        CrossDcMode mode,
	                        int batchMaxOps,
	                        long batchMaxWaitMs,
	                        boolean requireRemoteAck,
	                        long remoteAckTimeoutMs,
	                        List<String> learnerIds,
	                        List<String> voterIds) {
		this.nodeState = nodeState;
		this.mode = mode;
		this.batchMaxOps = Math.max(1, batchMaxOps);
		this.batchMaxWaitMs = Math.max(1, batchMaxWaitMs);
		this.requireRemoteAck = requireRemoteAck;
		this.remoteAckTimeoutMs = Math.max(1L, remoteAckTimeoutMs);
		this.learnerIds = learnerIds == null || learnerIds.isEmpty()
				? Set.of()
				: Set.copyOf(learnerIds);
		this.voterIds = voterIds == null || voterIds.isEmpty()
				? Set.of()
				: Set.copyOf(voterIds);
	}

	public CrossDcMetrics getMetrics() {
		return metrics;
	}

	public boolean isRequireRemoteAck() {
		return requireRemoteAck;
	}

	public long getRemoteAckTimeoutMs() {
		return remoteAckTimeoutMs;
	}

	public void setRemotePeers(List<ReplicationPeer> peers) {
		remotePeers.clear();
		for (ReplicationPeer peer : peers) {
			if (peer.dc() != null && !peer.dc().equals(nodeState.getLocalDc())) {
				remotePeers.add(peer);
			}
		}
	}

	public void setShipper(BiConsumer<ReplicationPeer, OpLogSegment> shipper) {
		this.shipper = shipper;
	}

	public void setSwarm(AdaptiveReplicaSwarm swarm) {
		this.swarm = swarm;
	}

	public void setEnvelopeCoordinator(TxEnvelopeCoordinator envelopeCoordinator) {
		this.envelopeCoordinator = envelopeCoordinator == null
				? new TxEnvelopeCoordinator()
				: envelopeCoordinator;
	}

	public TxEnvelopeCoordinator getEnvelopeCoordinator() {
		return envelopeCoordinator;
	}

	public void start() {
		running = true;
		ThreadService.getScheduledExecutor().schedule(this::flushDue, effectiveWaitMs(), MILLISECONDS);
	}

	public void stop() {
		running = false;
	}

	public void onAppended(ReplicationOp op) {
		if (shipTargets().isEmpty()) {
			return;
		}
		final String key = op.domainType() + "#" + op.shard();
		final TxUnitShardBuffer buffer = buffers.computeIfAbsent(key, _ -> new TxUnitShardBuffer(true));
		buffer.offer(op);
		final ReplicationOpType type = op.type();
		final boolean unitBoundary = type == ReplicationOpType.TX_COMMIT
				|| type == ReplicationOpType.TX_ABORT;
		// Hold mid-unit; flush on unit close, sync modes, or batch pressure of complete ops.
		if (requireRemoteAck
				|| mode == CrossDcMode.SYNC_VOTERS_ACROSS_DC
				|| unitBoundary
				|| buffer.size() >= batchMaxOps) {
			flushBuffer(buffer);
		}
	}

	/** Test helper: ops currently held waiting for TX unit completion. */
	@VisibleForTesting
	public int bufferedOpCount(String domainType, int shard) {
		final TxUnitShardBuffer buffer = buffers.get(domainType + "#" + shard);
		return buffer == null ? 0 : buffer.size();
	}

	/**
	 * Who should receive Cross-DC ship:
	 * ASYNC_SHIP → configured {@code learners} when non-empty, else all remote-DC peers;
	 * SYNC_VOTERS_ACROSS_DC → voters ∪ learners (learners stay async catch-up).
	 */
	private List<ReplicationPeer> shipTargets() {
		if (remotePeers.isEmpty()) {
			return List.of();
		}
		if (mode == CrossDcMode.ASYNC_SHIP) {
			if (learnerIds.isEmpty()) {
				return remotePeers;
			}
			final List<ReplicationPeer> targets = new ArrayList<>();
			for (ReplicationPeer peer : remotePeers) {
				if (learnerIds.contains(peer.id())) {
					targets.add(peer);
				}
			}
			return targets;
		}
		final Set<String> syncVoters = resolvedVoterIds();
		final List<ReplicationPeer> targets = new ArrayList<>();
		for (ReplicationPeer peer : remotePeers) {
			if (syncVoters.contains(peer.id()) || learnerIds.contains(peer.id())) {
				targets.add(peer);
			}
		}
		return targets;
	}

	/** Resolved remote sync voter ids (config voters, or remotes minus learners). */
	public Set<String> resolvedVoterIds() {
		if (mode != CrossDcMode.SYNC_VOTERS_ACROSS_DC) {
			return Set.of();
		}
		if (!voterIds.isEmpty()) {
			final Set<String> resolved = new HashSet<>();
			for (ReplicationPeer peer : remotePeers) {
				if (voterIds.contains(peer.id())) {
					resolved.add(peer.id());
				}
			}
			return resolved;
		}
		final Set<String> resolved = new HashSet<>();
		for (ReplicationPeer peer : remotePeers) {
			if (!learnerIds.contains(peer.id())) {
				resolved.add(peer.id());
			}
		}
		return resolved;
	}

	private long effectiveWaitMs() {
		final AdaptiveReplicaSwarm s = swarm;
		if (s == null) {
			return batchMaxWaitMs;
		}
		final double urgency = Math.max(0.5, s.shipUrgencyMultiplier());
		return Math.max(1L, (long) (batchMaxWaitMs / urgency));
	}

	private void flushDue() {
		try {
			final long now = System.currentTimeMillis();
			final long waitMs = effectiveWaitMs();
			for (Map.Entry<String, TxUnitShardBuffer> e : buffers.entrySet()) {
				if (e.getValue().shouldFlushByTime(now, waitMs)) {
					flushBuffer(e.getValue());
				}
			}
		} finally {
			if (running) {
				ThreadService.getScheduledExecutor().schedule(this::flushDue, effectiveWaitMs(), MILLISECONDS);
			}
		}
	}

	private void flushBuffer(TxUnitShardBuffer buffer) {
		final List<ReplicationOp> drained = buffer.drainCompleteUnits();
		if (drained.isEmpty() || shipper == null) {
			return;
		}
		final List<ReplicationOp> batch = envelopeCoordinator.takeShippable(drained);
		if (batch.isEmpty()) {
			return;
		}
		final List<ReplicationPeer> targets = shipTargets();
		if (targets.isEmpty()) {
			return;
		}
		// Envelope release may span shards — ship one segment per stream.
		for (Map.Entry<String, List<ReplicationOp>> group
				: TxEnvelopeCoordinator.groupByStream(batch).entrySet()) {
			final List<ReplicationOp> streamOps = group.getValue();
			if (streamOps.isEmpty()) {
				continue;
			}
			final ReplicationOp first = streamOps.getFirst();
			final OpLogSegment segment = new OpLogSegment(
					first.domainType(),
					first.shard(),
					first.opSeq(),
					streamOps.getLast().opSeq(),
					streamOps,
					OpLogCodec.segmentChecksum(streamOps)
			);
			for (ReplicationPeer peer : targets) {
				try {
					shipper.accept(peer, segment);
					metrics.recordShipSuccess();
					final long behind = Math.max(0, streamOps.getLast().opSeq()
							- nodeState.appliedWatermark(first.domainType(), first.shard()));
					metrics.recordLag(0, behind);
				} catch (RuntimeException ex) {
					metrics.recordShipFailure();
				}
			}
		}
	}

	public void onRemoteAck(String domainType, int shard, long opSeq, long latencyMs) {
		nodeState.advancePeerAck("remote", domainType, shard, opSeq);
		metrics.recordRemoteAckLatency(latencyMs);
		metrics.recordLag(latencyMs, 0);
		final String waitKey = domainType + "#" + shard + "@" + opSeq;
		final CompletableFuture<Void> waiter = remoteAckWaiters.remove(waitKey);
		if (waiter != null) {
			waiter.complete(null);
		}
		// Also complete any waiters for lower seq on same stream
		remoteAckWaiters.entrySet().removeIf(e -> {
			final String k = e.getKey();
			final String prefix = domainType + "#" + shard + "@";
			if (!k.startsWith(prefix)) {
				return false;
			}
			final long needed = Long.parseLong(k.substring(prefix.length()));
			if (needed <= opSeq) {
				e.getValue().complete(null);
				return true;
			}
			return false;
		});
	}

	/**
	 * When {@code requireRemoteAck}, block until a remote DC APPLY_ACK for this seq (or timeout).
	 * Independent of ORCHID remote digest voting ({@link CrossDcMode#SYNC_VOTERS_ACROSS_DC}).
	 */
	public void awaitRemoteAck(String domainType, int shard, long opSeq, long timeoutMs) {
		if (!requireRemoteAck) {
			return;
		}
		if (shipTargets().isEmpty()) {
			return;
		}
		final String waitKey = domainType + "#" + shard + "@" + opSeq;
		final CompletableFuture<Void> future = remoteAckWaiters.computeIfAbsent(waitKey, _ -> new CompletableFuture<>());
		try {
			future.get(Math.max(1L, timeoutMs > 0 ? timeoutMs : remoteAckTimeoutMs), TimeUnit.MILLISECONDS);
		} catch (Exception ex) {
			remoteAckWaiters.remove(waitKey, future);
			throw new IllegalStateException(
					"Cross-DC remote ACK timeout domain=" + domainType + " shard=" + shard + " seq=" + opSeq, ex);
		}
	}
}
