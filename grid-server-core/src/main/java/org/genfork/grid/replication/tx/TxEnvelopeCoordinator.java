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
package org.genfork.grid.replication.tx;

import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.google.common.annotations.VisibleForTesting;

/**
 * Minimal Cross-DC / multi-stream TX envelope coordinator (not external XA).
 * <p>
 * Ship-hold and apply-staging are independent maps so Cross-DC release on the
 * proposer does not discard local/remote apply staging for the same {@code txId}.
 * <p>
 * <b>FOR UPDATE lock-phase (Phase 3 v1):</b> {@link #registerLockPhase} /
 * {@link #notePeerLockAck} track peer row-lock ACKs for an open TX.
 * Durable commit still uses existing multi-stream ship/apply markers.
 * <p>
 * <b>FOR UPDATE prepare-phase:</b> {@link #registerPreparePhase} /
 * {@link #notePeerPrepareAck} / {@link #recordCommitDecision} track peer prepare
 * votes; Netty ships {@code FOR_UPDATE_PREPARE_*} / {@code FOR_UPDATE_COMMIT_DEC}
 * (product path; not external XA).
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public final class TxEnvelopeCoordinator {

	public record StagedMutation(ReplicationOpType type, byte[] key, byte[] value, int shard) {
	}

	private static final class ShipEnvelope {
		private final Set<TxEnvelopeCodec.StreamRef> expected;
		private final Map<TxEnvelopeCodec.StreamRef, List<ReplicationOp>> held =
				new ConcurrentHashMap<>();

		private ShipEnvelope(Set<TxEnvelopeCodec.StreamRef> expected) {
			this.expected = Set.copyOf(expected);
		}
	}

	private static final class ApplyEnvelope {
		private final Set<TxEnvelopeCodec.StreamRef> expected;
		private final Set<TxEnvelopeCodec.StreamRef> committed = ConcurrentHashMap.newKeySet();
		private final Map<TxEnvelopeCodec.StreamRef, ConcurrentLinkedQueue<StagedMutation>> staging =
				new ConcurrentHashMap<>();
		private volatile boolean aborted;

		private ApplyEnvelope(Set<TxEnvelopeCodec.StreamRef> expected) {
			this.expected = Set.copyOf(expected);
		}
	}

	/**
	 * Peer lock ACK set for distributed FOR UPDATE (scaffold only).
	 */
	private static final class LockPhase {
		private final int expectedPeers;
		private final Set<Integer> ackedPeers = ConcurrentHashMap.newKeySet();

		private LockPhase(int expectedPeers) {
			this.expectedPeers = expectedPeers;
		}
	}

	/**
	 * Peer prepare votes + commit decision for distributed FOR UPDATE (E2 scaffold).
	 */
	private static final class PreparePhase {
		private final int expectedPeers;
		private final Set<Integer> preparedPeers = ConcurrentHashMap.newKeySet();
		private volatile Boolean commitDecision;

		private PreparePhase(int expectedPeers) {
			this.expectedPeers = expectedPeers;
		}
	}

	private final Map<Long, ShipEnvelope> shipOpen = new ConcurrentHashMap<>();
	private final Map<Long, ApplyEnvelope> applyOpen = new ConcurrentHashMap<>();
	private final Map<Long, LockPhase> lockPhases = new ConcurrentHashMap<>();
	private final Map<Long, PreparePhase> preparePhases = new ConcurrentHashMap<>();
	/** Survives {@link #discardPreparePhase} until {@link #discardLockPhase}. */
	private final Map<Long, Boolean> commitDecisions = new ConcurrentHashMap<>();

	/**
	 * Register expected peer lock ACKs for {@code txId} (FOR UPDATE 2PC-lite).
	 * Idempotent while open.
	 */
	public void registerLockPhase(long txId, int expectedPeers) {
		if (expectedPeers <= 0) {
			return;
		}
		lockPhases.compute(txId, (id, existing) -> {
			if (existing != null) {
				return existing;
			}
			return new LockPhase(expectedPeers);
		});
	}

	/** Record that peer {@code peerIndex} held row locks for {@code txId}. */
	public void notePeerLockAck(long txId, int peerIndex) {
		final LockPhase phase = lockPhases.get(txId);
		if (phase == null) {
			return;
		}
		phase.ackedPeers.add(peerIndex);
	}

	/** {@code true} when every expected peer has ACKed locks for {@code txId}. */
	public boolean allPeerLocksAcked(long txId) {
		final LockPhase phase = lockPhases.get(txId);
		if (phase == null) {
			return false;
		}
		return phase.ackedPeers.size() >= phase.expectedPeers;
	}

	/** Drop lock-phase + commit decision (COMMIT/ROLLBACK / failure). */
	public void discardLockPhase(long txId) {
		lockPhases.remove(txId);
		commitDecisions.remove(txId);
	}

	@VisibleForTesting
	public boolean hasOpenLockPhase(long txId) {
		return lockPhases.containsKey(txId);
	}

	/**
	 * Register expected peer prepare ACKs for {@code txId} (FOR UPDATE prepare votes).
	 */
	public void registerPreparePhase(long txId, int expectedPeers) {
		if (expectedPeers <= 0) {
			return;
		}
		preparePhases.compute(txId, (id, existing) -> {
			if (existing != null) {
				return existing;
			}
			return new PreparePhase(expectedPeers);
		});
	}

	/** Record that peer {@code peerIndex} voted prepared for {@code txId}. */
	public void notePeerPrepareAck(long txId, int peerIndex) {
		final PreparePhase phase = preparePhases.get(txId);
		if (phase == null) {
			return;
		}
		phase.preparedPeers.add(peerIndex);
	}

	/** {@code true} when every expected peer has prepared for {@code txId}. */
	public boolean allPeersPrepared(long txId) {
		final PreparePhase phase = preparePhases.get(txId);
		if (phase == null) {
			return false;
		}
		return phase.preparedPeers.size() >= phase.expectedPeers;
	}

	/**
	 * Record coordinator COMMIT/ABORT decision (retained until {@link #discardLockPhase}).
	 */
	public void recordCommitDecision(long txId, boolean commit) {
		commitDecisions.put(txId, commit);
		final PreparePhase phase = preparePhases.get(txId);
		if (phase != null) {
			phase.commitDecision = commit;
		}
	}

	@VisibleForTesting
	public Boolean commitDecision(long txId) {
		return commitDecisions.get(txId);
	}

	/** Drop prepare-phase votes (COMMIT/ROLLBACK / failure). Decision retained until lock discard. */
	public void discardPreparePhase(long txId) {
		preparePhases.remove(txId);
	}

	@VisibleForTesting
	public boolean hasOpenPreparePhase(long txId) {
		return preparePhases.containsKey(txId);
	}

	public void registerApply(TxEnvelopeCodec.Membership membership) {
		Objects.requireNonNull(membership, "membership");
		if (!membership.isMultiStream()) {
			return;
		}
		applyOpen.compute(membership.txId(), (id, existing) -> {
			if (existing != null) {
				return existing;
			}
			return new ApplyEnvelope(membership.streams());
		});
	}

	public boolean isMultiApplyOpen(long txId) {
		final ApplyEnvelope env = applyOpen.get(txId);
		return env != null && !env.aborted;
	}

	public void stageApply(long txId, TxEnvelopeCodec.StreamRef stream, StagedMutation mutation) {
		final ApplyEnvelope env = applyOpen.get(txId);
		if (env == null || env.aborted) {
			return;
		}
		env.staging.computeIfAbsent(stream, s -> new ConcurrentLinkedQueue<>()).add(mutation);
	}

	/**
	 * Record stream COMMIT for apply path.
	 *
	 * @return {@code true} when envelope is fully committed (caller must {@link #takeApplyStaging})
	 */
	public boolean noteApplyCommit(long txId, TxEnvelopeCodec.StreamRef stream) {
		final ApplyEnvelope env = applyOpen.get(txId);
		if (env == null || env.aborted) {
			return false;
		}
		env.committed.add(stream);
		return env.committed.containsAll(env.expected);
	}

	public List<StagedMutation> takeApplyStaging(long txId) {
		final ApplyEnvelope env = applyOpen.remove(txId);
		if (env == null) {
			return List.of();
		}
		final List<StagedMutation> all = new ArrayList<>();
		for (ConcurrentLinkedQueue<StagedMutation> part : env.staging.values()) {
			all.addAll(part);
		}
		env.staging.clear();
		return List.copyOf(all);
	}

	public void discardApply(long txId) {
		final ApplyEnvelope env = applyOpen.remove(txId);
		if (env != null) {
			env.aborted = true;
			env.staging.clear();
		}
	}

	/** Discard every open apply envelope (crash / abort-all). */
	public void discardAllApply() {
		for (Long txId : List.copyOf(applyOpen.keySet())) {
			discardApply(txId);
		}
	}

	/**
	 * Filter a complete per-stream OpLog prefix into Cross-DC shippable ops.
	 * Multi-stream envelope units are held until every stream commits; abort discards.
	 * Lock scope is per-{@link ShipEnvelope} only (not the whole coordinator).
	 */
	public List<ReplicationOp> takeShippable(List<ReplicationOp> completePrefix) {
		if (completePrefix == null || completePrefix.isEmpty()) {
			return List.of();
		}
		final List<ReplicationOp> out = new ArrayList<>();
		int i = 0;
		while (i < completePrefix.size()) {
			final ReplicationOp op = completePrefix.get(i);
			if (op.type() != ReplicationOpType.TX_BEGIN) {
				out.add(op);
				i++;
				continue;
			}
			int end = i + 1;
			while (end < completePrefix.size()) {
				final ReplicationOpType t = completePrefix.get(end).type();
				if (t == ReplicationOpType.TX_COMMIT || t == ReplicationOpType.TX_ABORT) {
					break;
				}
				end++;
			}
			if (end >= completePrefix.size()) {
				break;
			}
			final List<ReplicationOp> unit = new ArrayList<>(completePrefix.subList(i, end + 1));
			final ReplicationOp begin = unit.getFirst();
			final ReplicationOp boundary = unit.getLast();
			final long txId = TxEnvelopeCodec.txIdFromKey(begin.key());
			final TxEnvelopeCodec.Membership membership =
					TxEnvelopeCodec.decode(txId, begin.value());
			if (membership == null || !membership.isMultiStream()) {
				out.addAll(unit);
				i = end + 1;
				continue;
			}
			final TxEnvelopeCodec.StreamRef stream =
					new TxEnvelopeCodec.StreamRef(begin.domainType(), begin.shard());
			if (boundary.type() == ReplicationOpType.TX_ABORT) {
				shipOpen.remove(txId);
				i = end + 1;
				continue;
			}
			final ShipEnvelope env = shipOpen.computeIfAbsent(
					txId, id -> new ShipEnvelope(membership.streams()));
			final List<ReplicationOp> released;
			synchronized (env) {
				env.held.put(stream, List.copyOf(unit));
				if (!env.held.keySet().containsAll(env.expected)) {
					released = null;
				} else {
					released = new ArrayList<>();
					for (TxEnvelopeCodec.StreamRef s : env.expected) {
						final List<ReplicationOp> held = env.held.get(s);
						if (held != null) {
							released.addAll(held);
						}
					}
					shipOpen.remove(txId);
				}
			}
			if (released != null) {
				out.addAll(released);
			}
			i = end + 1;
		}
		return List.copyOf(out);
	}

	public int heldShipOpCount(long txId) {
		final ShipEnvelope env = shipOpen.get(txId);
		if (env == null) {
			return 0;
		}
		int n = 0;
		for (List<ReplicationOp> u : env.held.values()) {
			n += u.size();
		}
		return n;
	}

	public int applyStagedCount(long txId) {
		final ApplyEnvelope env = applyOpen.get(txId);
		if (env == null) {
			return 0;
		}
		int n = 0;
		for (ConcurrentLinkedQueue<StagedMutation> u : env.staging.values()) {
			n += u.size();
		}
		return n;
	}

	public boolean hasOpenApplyEnvelope(long txId) {
		return applyOpen.containsKey(txId);
	}

	public boolean hasOpenShipEnvelope(long txId) {
		return shipOpen.containsKey(txId);
	}

	public static Map<String, List<ReplicationOp>> groupByStream(List<ReplicationOp> ops) {
		final Map<String, List<ReplicationOp>> grouped = new LinkedHashMap<>();
		if (ops == null) {
			return grouped;
		}
		for (ReplicationOp op : ops) {
			final String key = op.domainType() + "#" + op.shard();
			grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(op);
		}
		return grouped;
	}
}
