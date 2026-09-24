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
package org.genfork.grid.replication.apply;

import com.google.common.annotations.VisibleForTesting;
import org.genfork.grid.codec.duplex.DuplexBlob;
import org.genfork.grid.codec.duplex.DuplexCodecSupport;
import org.genfork.grid.mem.stage.GridEntriesProcessor;
import org.genfork.grid.replication.ReplicationNodeState;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.log.OpLog;
import org.genfork.grid.replication.orchid.OrchidNode;
import org.genfork.grid.replication.transport.ApplyAckSender;
import org.genfork.grid.replication.tx.TxEnvelopeCodec;
import org.genfork.grid.replication.tx.TxEnvelopeCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Applies committed replication ops using a per-shard monotonic watermark.
 * {@code opSeq} is the global ORCHID sequence; gaps between shard-local applies are normal.
 * <p>
 * TX apply-as-unit: UPSERT/DELETE between {@code TX_BEGIN} and {@code TX_COMMIT} are staged;
 * {@code TX_ABORT} or incomplete open TX discards staging (no partial map visibility).
 * Multi-stream envelopes ({@link TxEnvelopeCoordinator}): map visibility only after every
 * participating stream commits.
 * <p>
 * {@link ReplicationOpType#DDL}: catalog SQL replay via {@link CatalogDdlHandler} (fail-closed);
 * catalog DDL epochs are independent of HELLO duplex epoch — the handler rejects stale catalog epochs.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public class ReplicaApplier {
	private static final Logger log = LoggerFactory.getLogger(ReplicaApplier.class);

	private final ReplicationNodeState nodeState;
	private final OpLog opLog;
	private final Function<Integer, GridEntriesProcessor> processorByShard;
	private final ApplyAckSender ackSender;
	private final boolean applyToLocalMap;

	private final Map<String, Object> shardLocks = new ConcurrentHashMap<>();
	private final Map<String, CompletableFuture<Void>> waiters = new ConcurrentHashMap<>();
	/** Open TX staging buffers keyed by {@code domainType#shard}. */
	private final Map<String, List<StagedMutation>> txStaging = new ConcurrentHashMap<>();
	/** Open TX id per stream (for multi-stream envelope routing). */
	private final Map<String, Long> openTxIdByStream = new ConcurrentHashMap<>();
	private volatile CatalogDdlHandler catalogDdlHandler;
	private volatile TxEnvelopeCoordinator envelopeCoordinator;
	/**
	 * LAZY OpLog hydrate: map-only install + deferred batch index (no async queue / no full reindex).
	 */
	private volatile boolean hydrateMapOnly;
	private final List<GridEntriesProcessor.AddEntry> hydrateIndexAdds = new ArrayList<>();
	private final List<byte[]> hydrateIndexRemoves = new ArrayList<>();

	private record StagedMutation(ReplicationOpType type, byte[] key, byte[] value) {
	}

	public ReplicaApplier(
			ReplicationNodeState nodeState,
			OpLog opLog,
			Function<Integer, GridEntriesProcessor> processorByShard,
			ApplyAckSender ackSender,
			boolean applyToLocalMap
	) {
		this.nodeState = nodeState;
		this.opLog = opLog;
		this.processorByShard = processorByShard;
		this.ackSender = ackSender;
		this.applyToLocalMap = applyToLocalMap;
	}

	/**
	 * Start LAZY OpLog hydrate install mode (map-only + collect index delta).
	 */
	public void beginHydrateMapOnly() {
		hydrateMapOnly = true;
		hydrateIndexAdds.clear();
		hydrateIndexRemoves.clear();
	}

	/**
	 * Flush collected hydrate index delta for one shard (one mailbox batch for upserts).
	 */
	public void flushHydrateIndexDelta(int shard) {
		if (!applyToLocalMap || processorByShard == null) {
			hydrateIndexAdds.clear();
			hydrateIndexRemoves.clear();
			return;
		}
		final GridEntriesProcessor processor = processorByShard.apply(shard);
		if (processor == null) {
			hydrateIndexAdds.clear();
			hydrateIndexRemoves.clear();
			return;
		}
		if (!hydrateIndexAdds.isEmpty()) {
			processor.indexDeltaNow(new ArrayList<>(hydrateIndexAdds));
			hydrateIndexAdds.clear();
		}
		for (byte[] key : hydrateIndexRemoves) {
			processor.indexNowRemove(key);
		}
		hydrateIndexRemoves.clear();
	}

	/**
	 * End LAZY OpLog hydrate install mode (drops any leftover delta without indexing).
	 */
	public void endHydrateMapOnly() {
		hydrateMapOnly = false;
		hydrateIndexAdds.clear();
		hydrateIndexRemoves.clear();
	}

	/**
	 * Applies replicated catalog DDL on a peer (or proposer self-apply).
	 */
	@FunctionalInterface
	public interface CatalogDdlHandler {
		void applyCatalogDdl(String ddlSql, long schemaEpoch);
	}

	public void setCatalogDdlHandler(CatalogDdlHandler catalogDdlHandler) {
		this.catalogDdlHandler = catalogDdlHandler;
	}

	public void setEnvelopeCoordinator(TxEnvelopeCoordinator envelopeCoordinator) {
		this.envelopeCoordinator = envelopeCoordinator;
	}

	public TxEnvelopeCoordinator getEnvelopeCoordinator() {
		return envelopeCoordinator;
	}

	/**
	 * Applies committed ops. {@code opSeq} is the global ORCHID sequence (not per-shard dense).
	 * Per-shard watermark only requires {@code opSeq > applied} (gaps across shards are expected).
	 *
	 * @param fromConsensus true when already durable in local orchid/oplog path (skip re-append)
	 */
	public void apply(ReplicationOp op, boolean fromConsensus) {
		apply(op, fromConsensus, false);
	}

	/**
	 * @param forceInstall when true (repair FETCH_ROW/RESHIP), re-install map bytes even if
	 *                     {@code opSeq <= applied} — watermark can match while value checksum diverged
	 */
	public void apply(ReplicationOp op, boolean fromConsensus, boolean forceInstall) {
		final String lockKey = op.domainType() + "#" + op.shard();
		synchronized (shardLocks.computeIfAbsent(lockKey, _ -> new Object())) {
			final long applied = nodeState.appliedWatermark(op.domainType(), op.shard());
			if (op.opSeq() <= applied) {
				if (!forceInstall) {
					return;
				}
				// Same-seq checksum heal only. Stale REPAIR_REPLY (opSeq < applied) must not
				// clobber a newer primary watermark — ASYNC learners lag and FETCH_ROW/RESHIP
				// can return pre-catch-up row bytes (Elle lost-append / G0 under dc-link heal).
				if (op.opSeq() < applied) {
					return;
				}
				forceReinstall(op);
				return;
			}
			// Global orchid seq: other shards consume intervening numbers; do not require applied+1.
			if (op.type() == ReplicationOpType.DDL) {
				applyCatalogDdl(op, fromConsensus);
				return;
			}

			if (op.schemaEpoch() != nodeState.getSchemaEpoch()) {
				if (ackSender != null) {
					ackSender.sendNack(op, "schemaEpoch mismatch");
				}
				throw new IllegalStateException("schemaEpoch mismatch for opSeq=" + op.opSeq());
			}

			if (op.type() == ReplicationOpType.BARRIER
					|| op.type() == ReplicationOpType.SNAPSHOT_MARKER) {
				persistAndAck(op, fromConsensus);
				return;
			}

			if (op.type() == ReplicationOpType.TX_BEGIN) {
				// Nested / restarted unit: discard prior incomplete staging.
				txStaging.put(lockKey, new ArrayList<>());
				final long txId = TxEnvelopeCodec.txIdFromKey(op.key());
				openTxIdByStream.put(lockKey, txId);
				final TxEnvelopeCoordinator gate = envelopeCoordinator;
				if (gate != null) {
					final TxEnvelopeCodec.Membership membership =
							TxEnvelopeCodec.decode(txId, op.value());
					if (membership != null && membership.isMultiStream()) {
						gate.registerApply(membership);
					}
				}
				persistAndAck(op, fromConsensus);
				return;
			}

			if (op.type() == ReplicationOpType.TX_COMMIT) {
				final long txId = openTxIdByStream.getOrDefault(
						lockKey, TxEnvelopeCodec.txIdFromKey(op.key()));
				final TxEnvelopeCoordinator gate = envelopeCoordinator;
				final TxEnvelopeCodec.StreamRef stream =
						new TxEnvelopeCodec.StreamRef(op.domainType(), op.shard());
				if (gate != null && gate.isMultiApplyOpen(txId)) {
					persistAndAck(op, fromConsensus);
					openTxIdByStream.remove(lockKey);
					txStaging.remove(lockKey);
					if (gate.noteApplyCommit(txId, stream)) {
						flushEnvelopeStaging(gate.takeApplyStaging(txId));
					}
					return;
				}
				flushStaging(lockKey, op.shard());
				txStaging.remove(lockKey);
				openTxIdByStream.remove(lockKey);
				persistAndAck(op, fromConsensus);
				return;
			}

			if (op.type() == ReplicationOpType.TX_ABORT) {
				final long txId = openTxIdByStream.getOrDefault(
						lockKey, TxEnvelopeCodec.txIdFromKey(op.key()));
				final TxEnvelopeCoordinator gate = envelopeCoordinator;
				if (gate != null && gate.isMultiApplyOpen(txId)) {
					gate.discardApply(txId);
				}
				txStaging.remove(lockKey);
				openTxIdByStream.remove(lockKey);
				persistAndAck(op, fromConsensus);
				return;
			}

			byte[] value = op.value();
			if (value != null && DuplexCodecSupport.isActiveForReplication() && DuplexBlob.isWire(value)) {
				try {
					final DuplexBlob blob = DuplexBlob.fromWireBytes(value);
					final DuplexBlob verified = DuplexCodecSupport.getCodec().getVerifier().verifyOrRepair(blob);
					value = verified.toWireBytes();
				} catch (RuntimeException ex) {
					if (ackSender != null) {
						ackSender.sendNack(op, "duplex verify failed: " + ex.getMessage());
					}
					throw ex;
				}
			}

			if (!fromConsensus) {
				opLog.append(op);
				final OrchidNode orchid = nodeState.getOrchidNode();
				if (orchid != null) {
					orchid.advanceCommittedTip(op.opSeq());
				}
			}

			final Long openTxId = openTxIdByStream.get(lockKey);
			final TxEnvelopeCoordinator gate = envelopeCoordinator;
			if (openTxId != null
					&& gate != null
					&& gate.isMultiApplyOpen(openTxId)
					&& (op.type() == ReplicationOpType.UPSERT
					|| op.type() == ReplicationOpType.DELETE)) {
				gate.stageApply(
						openTxId,
						new TxEnvelopeCodec.StreamRef(op.domainType(), op.shard()),
						new TxEnvelopeCoordinator.StagedMutation(op.type(), op.key(), value, op.shard())
				);
				nodeState.advanceApplied(op.domainType(), op.shard(), op.opSeq());
				completeWaiters(op.domainType(), op.shard(), op.opSeq());
				if (ackSender != null) {
					ackSender.sendAck(op);
				}
				return;
			}

			final List<StagedMutation> staging = txStaging.get(lockKey);
			if (staging != null
					&& (op.type() == ReplicationOpType.UPSERT
					|| op.type() == ReplicationOpType.DELETE)) {
				staging.add(new StagedMutation(op.type(), op.key(), value));
				nodeState.advanceApplied(op.domainType(), op.shard(), op.opSeq());
				completeWaiters(op.domainType(), op.shard(), op.opSeq());
				if (ackSender != null) {
					ackSender.sendAck(op);
				}
				return;
			}

			installToMap(op.shard(), op.type(), op.key(), value);

			nodeState.advanceApplied(op.domainType(), op.shard(), op.opSeq());
			completeWaiters(op.domainType(), op.shard(), op.opSeq());
			if (ackSender != null) {
				ackSender.sendAck(op);
			}
		}
	}

	/**
	 * Discard any open TX staging (incomplete unit after hydrate or crash mid-commit).
	 * Map never sees partial TX rows.
	 */
	public void discardOpenTxStaging() {
		txStaging.clear();
		openTxIdByStream.clear();
		final TxEnvelopeCoordinator gate = envelopeCoordinator;
		if (gate != null) {
			gate.discardAllApply();
		}
	}

	/** Test helper: whether a stream currently buffers an open TX. */
	public boolean hasOpenTx(String domainType, int shard) {
		final String lockKey = domainType + "#" + shard;
		if (openTxIdByStream.containsKey(lockKey)) {
			return true;
		}
		final List<StagedMutation> staging = txStaging.get(lockKey);
		return staging != null;
	}

	/** Test helper: staged mutation count for open TX (local + envelope). */
	@VisibleForTesting
	public int openTxStagedCount(String domainType, int shard) {
		final String lockKey = domainType + "#" + shard;
		final Long txId = openTxIdByStream.get(lockKey);
		final TxEnvelopeCoordinator gate = envelopeCoordinator;
		if (txId != null && gate != null && gate.isMultiApplyOpen(txId)) {
			return gate.applyStagedCount(txId);
		}
		final List<StagedMutation> staging = txStaging.get(lockKey);
		return staging == null ? 0 : staging.size();
	}

	public CompletableFuture<Void> awaitApplied(String domainType, int shard, long seq) {
		if (nodeState.appliedWatermark(domainType, shard) >= seq) {
			return CompletableFuture.completedFuture(null);
		}
		final String key = domainType + "#" + shard + "@" + seq;
		return waiters.computeIfAbsent(key, k -> new CompletableFuture<>());
	}

	private void applyCatalogDdl(ReplicationOp op, boolean fromConsensus) {
		final CatalogDdlHandler handler = catalogDdlHandler;
		if (handler == null) {
			if (ackSender != null) {
				ackSender.sendNack(op, "catalog DDL handler not bound");
			}
			throw new IllegalStateException("catalog DDL handler not bound for opSeq=" + op.opSeq());
		}
		final byte[] value = op.value();
		if (value == null || value.length == 0) {
			if (ackSender != null) {
				ackSender.sendNack(op, "DDL op missing SQL value");
			}
			throw new IllegalStateException("DDL op missing SQL value opSeq=" + op.opSeq());
		}
		final String ddlSql = new String(value, java.nio.charset.StandardCharsets.UTF_8);
		try {
			handler.applyCatalogDdl(ddlSql, op.schemaEpoch());
		} catch (RuntimeException ex) {
			if (ackSender != null) {
				ackSender.sendNack(op, "DDL apply failed: " + ex.getMessage());
			}
			throw ex;
		}
		persistAndAck(op, fromConsensus);
	}

	private void persistAndAck(ReplicationOp op, boolean fromConsensus) {
		if (!fromConsensus) {
			opLog.append(op);
			final OrchidNode orchid = nodeState.getOrchidNode();
			if (orchid != null) {
				orchid.advanceCommittedTip(op.opSeq());
			}
		}
		nodeState.advanceApplied(op.domainType(), op.shard(), op.opSeq());
		completeWaiters(op.domainType(), op.shard(), op.opSeq());
		if (ackSender != null) {
			ackSender.sendAck(op);
		}
	}

	private void flushStaging(String lockKey, int shard) {
		final List<StagedMutation> staging = txStaging.get(lockKey);
		if (staging == null || staging.isEmpty()) {
			return;
		}
		for (StagedMutation m : staging) {
			installToMap(shard, m.type(), m.key(), m.value());
		}
	}

	private void flushEnvelopeStaging(List<TxEnvelopeCoordinator.StagedMutation> staging) {
		if (staging == null || staging.isEmpty()) {
			return;
		}
		for (TxEnvelopeCoordinator.StagedMutation m : staging) {
			installToMap(m.shard(), m.type(), m.key(), m.value());
		}
	}

	/**
	 * Repair path: overwrite local map for an already-applied seq (no OpLog re-append).
	 */
	private void forceReinstall(ReplicationOp op) {
		byte[] value = op.value();
		if (value != null && DuplexCodecSupport.isActiveForReplication() && DuplexBlob.isWire(value)) {
			final DuplexBlob blob = DuplexBlob.fromWireBytes(value);
			final DuplexBlob verified = DuplexCodecSupport.getCodec().getVerifier().verifyOrRepair(blob);
			value = verified.toWireBytes();
		}
		if (op.type() == ReplicationOpType.UPSERT
				|| op.type() == ReplicationOpType.DELETE) {
			installToMap(op.shard(), op.type(), op.key(), value);
		}
		if (ackSender != null) {
			ackSender.sendAck(op);
		}
	}

	private void installToMap(int shard, ReplicationOpType type, byte[] key, byte[] value) {
		if (!applyToLocalMap || processorByShard == null) {
			return;
		}
		final GridEntriesProcessor processor = processorByShard.apply(shard);
		if (processor == null) {
			return;
		}
		if (hydrateMapOnly) {
			if (type == ReplicationOpType.DELETE) {
				processor.installMapOnly(key, null, true);
				if (key != null) {
					hydrateIndexRemoves.add(key);
				}
			} else if (type == ReplicationOpType.UPSERT) {
				processor.installMapOnly(key, value, false);
				if (key != null && value != null) {
					hydrateIndexAdds.add(new GridEntriesProcessor.AddEntry(null, key, value));
				}
			}
			return;
		}
		if (type == ReplicationOpType.DELETE) {
			processor.installCommitted(key, null, true);
		} else if (type == ReplicationOpType.UPSERT) {
			processor.installCommitted(key, value, false);
		}
	}

	private void completeWaiters(String domainType, int shard, long appliedSeq) {
		waiters.entrySet().removeIf(e -> {
			final String key = e.getKey();
			if (!key.startsWith(domainType + "#" + shard + "@")) {
				return false;
			}
			final long needed = Long.parseLong(key.substring(key.lastIndexOf('@') + 1));
			if (appliedSeq >= needed) {
				e.getValue().complete(null);
				return true;
			}
			return false;
		});
	}
}
