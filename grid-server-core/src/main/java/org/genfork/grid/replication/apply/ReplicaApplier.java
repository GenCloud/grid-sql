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
import org.genfork.grid.diag.VisibilityDiag;
import org.genfork.grid.codec.duplex.DuplexCodecSupport;
import org.genfork.grid.mem.stage.GridEntriesProcessor;
import org.genfork.grid.replication.ReplicationNodeState;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.log.OpLog;
import org.genfork.grid.replication.log.StreamOpLogAppender;
import org.genfork.grid.replication.orchid.OrchidNode;
import org.genfork.grid.replication.transport.ApplyAckSender;
import org.genfork.grid.replication.tx.TxEnvelopeCodec;
import org.genfork.grid.replication.tx.TxEnvelopeCoordinator;

import java.nio.charset.StandardCharsets;
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
 * <p>
 * Peer consensus apply ({@code journalRemote=true}): OpLog bytes via {@link StreamOpLogAppender}
 * <em>outside</em> shard locks (no tip fsync here — OrchidNode batches {@code confirmPersisted} after drain).
 * Local propose apply keeps OpLog + tip in {@code MutationRecorder} ({@code journalRemote=false}).
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public class ReplicaApplier {
	private static final String ERR_APPLIER_BY_DOMAIN_UNSET =
			"applierByDomain unset; cannot flush multi-stream envelope";
	private static final String ERR_APPLIER_MISSING_PREFIX = "no ReplicaApplier for domain ";
	private static final String ERR_TX_BEGIN_NONEMPTY_STAGING =
			"TX_BEGIN with non-empty staging on ";
	private static final String ERR_TX_BEGIN_OPEN_TX =
			"TX_BEGIN while open TX already present on ";
	private static final String ERR_TX_BEGIN_STAGED_MID = " staged=";
	private static final String ERR_TX_BEGIN_INTERLEAVE_SUFFIX =
			" (concurrent TX unit interleave)";

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
	 * Resolve peer domain appliers so multi-stream envelope flush installs keys into the
	 * owning table processors (not the last TX_COMMIT stream's map).
	 */
	private volatile Function<String, ReplicaApplier> applierByDomain;
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

	/**
	 * Wire domain → applier lookup for multi-table envelope flush (set from
	 * {@code ReplicationCoordinator} after registerDomain).
	 */
	public void setApplierByDomain(Function<String, ReplicaApplier> applierByDomain) {
		this.applierByDomain = applierByDomain;
	}

	/**
	 * Applies committed ops. {@code opSeq} is the global ORCHID sequence (not per-shard dense).
	 * Per-shard watermark only requires {@code opSeq > applied} (gaps across shards are expected).
	 *
	 * @param fromConsensus true when already durable in local orchid/oplog path (skip re-append)
	 */
	public void apply(ReplicationOp op, boolean fromConsensus) {
		apply(op, fromConsensus, false, false);
	}

	/**
	 * @param forceInstall when true (repair FETCH_ROW/RESHIP), re-install map bytes even if
	 *                     {@code opSeq <= applied} — watermark can match while value checksum diverged
	 */
	public void apply(ReplicationOp op, boolean fromConsensus, boolean forceInstall) {
		apply(op, fromConsensus, forceInstall, false);
	}

	/**
	 * @param journalRemote peer consensus path: write OpLog bytes outside shard locks (no tip fsync)
	 */
	public void apply(ReplicationOp op, boolean fromConsensus, boolean forceInstall, boolean journalRemote) {
		if (journalRemote) {
			journalRemoteConsensusOp(op);
		}
		final String lockKey = op.domainType() + "#" + op.shard();
		synchronized (shardLocks.computeIfAbsent(lockKey, _ -> new Object())) {
			final long applied = nodeState.appliedWatermark(op.domainType(), op.shard());
			if (op.opSeq() <= applied) {
				// Watermark already past must not skip TX_COMMIT/ABORT close — otherwise staged
				// UPSERTs stay invisible forever (Jepsen Elle G-single / HAS≈0 after :ok append).
				if (closeOpenTxIfPresent(lockKey, op)) {
                    if (VisibilityDiag.enabled()) {
                        VisibilityDiag.debugf("applier.watermark",
                                "closeOpenTx stream=%s type=%s opSeq=%d applied=%d",
                                lockKey, op.type(), op.opSeq(), applied);
                    }

					return;
				}
				final OrchidNode catchUpOrchid = nodeState.getOrchidNode();
				final boolean installCatchUp = catchUpOrchid != null && catchUpOrchid.isInstallCatchUpRequired();
				// Tip-ok empty-map fence: dishonest applied wm must not skip catch-up installs
				// (GHA 37311157765 cell I / watermark skip after synthetic tip).
				if (!forceInstall && installCatchUp
						&& (op.type() == ReplicationOpType.UPSERT || op.type() == ReplicationOpType.DELETE)) {
					forceReinstall(op);
					catchUpOrchid.noteInstallCatchUpProgress(op.opSeq());
					return;
				}
				if (!forceInstall) {
                    if (VisibilityDiag.enabled()) {
                        VisibilityDiag.debugf("applier.watermark",
                                "skip stream=%s type=%s opSeq=%d applied=%d",
                                lockKey, op.type(), op.opSeq(), applied);
                    }
					return;
				}
				// Same-seq checksum heal only. Stale REPAIR_REPLY (opSeq < applied) must not
				// clobber a newer primary watermark — ASYNC learners lag and FETCH_ROW/RESHIP
				// can return pre-catch-up row bytes (Elle lost-append / G0 under dc-link heal).
				if (op.opSeq() < applied) {
					return;
				}
				// Never force-reinstall TX markers (no map payload); UPSERT/DELETE only.
				if (op.type() != ReplicationOpType.UPSERT && op.type() != ReplicationOpType.DELETE) {
					return;
				}
				forceReinstall(op);
				if (catchUpOrchid != null) {
					catchUpOrchid.noteInstallCatchUpProgress(op.opSeq());
				}
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
				// Contiguous TX admits (MutationRecorder stream lock) prevent interleave; this
				// guard is fail-closed defense in depth. Reject any second TX_BEGIN while an
				// open TX id is present — including empty staging (BEGIN before first UPSERT).
				final List<StagedMutation> priorStaging = txStaging.get(lockKey);
				if (priorStaging != null && !priorStaging.isEmpty()) {
					throw new IllegalStateException(
							ERR_TX_BEGIN_NONEMPTY_STAGING + lockKey
									+ ERR_TX_BEGIN_STAGED_MID + priorStaging.size()
									+ ERR_TX_BEGIN_INTERLEAVE_SUFFIX);
				}
				if (openTxIdByStream.containsKey(lockKey)) {
					throw new IllegalStateException(
							ERR_TX_BEGIN_OPEN_TX + lockKey + ERR_TX_BEGIN_INTERLEAVE_SUFFIX);
				}
				txStaging.put(lockKey, new ArrayList<>());
				final long txId = TxEnvelopeCodec.txIdFromKey(op.key());
				openTxIdByStream.put(lockKey, txId);
				final TxEnvelopeCoordinator gate = envelopeCoordinator;
				boolean multi = false;
				if (gate != null) {
					final TxEnvelopeCodec.Membership membership =
							TxEnvelopeCodec.decode(txId, op.value());
					if (membership != null && membership.isMultiStream()) {
						gate.registerApply(membership);
						multi = true;
					}
				}

                if (VisibilityDiag.enabled()) {
                    VisibilityDiag.debugf("applier.TX_BEGIN",
                            "stream=%s txId=%d multi=%s fromConsensus=%s opSeq=%d",
                            lockKey, txId, multi, fromConsensus, op.opSeq());
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
					final List<StagedMutation> dropped = txStaging.get(lockKey);
					final int droppedN = dropped == null ? 0 : dropped.size();
                    if (VisibilityDiag.enabled()) {
                        VisibilityDiag.debugf("applier.TX_COMMIT",
                                "multi stream=%s txId=%d localStaging=%d opSeq=%d",
                                lockKey, txId, droppedN, op.opSeq());
                    }

					persistAndAck(op, fromConsensus);
					openTxIdByStream.remove(lockKey);
					txStaging.remove(lockKey);
					if (gate.noteApplyCommit(txId, stream)) {
                        if (VisibilityDiag.enabled()) {
                            VisibilityDiag.debugf("applier.TX_COMMIT",
                                    "multi COMPLETE flushEnvelope txId=%d stream=%s", txId, lockKey);
                        }

						flushEnvelopeStagingByOwningDomain(gate.takeApplyStagingByStream(txId));
					}
					return;
				}
				final List<StagedMutation> staged = txStaging.get(lockKey);
				final int stagedN = staged == null ? 0 : staged.size();
                if (VisibilityDiag.enabled()) {
                    VisibilityDiag.debugf("applier.TX_COMMIT",
                            "single stream=%s txId=%d staged=%d opSeq=%d",
                            lockKey, txId, stagedN, op.opSeq());
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
				final List<StagedMutation> aborted = txStaging.get(lockKey);
				final int abortedN = aborted == null ? 0 : aborted.size();
				final TxEnvelopeCoordinator gate = envelopeCoordinator;
				if (gate != null && gate.isMultiApplyOpen(txId)) {
					gate.discardApply(txId);
				}

                if (VisibilityDiag.enabled()) {
                    VisibilityDiag.debugf("applier.TX_ABORT",
                            "stream=%s txId=%d droppedStaging=%d opSeq=%d",
                            lockKey, txId, abortedN, op.opSeq());
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
				final OrchidNode orchid = nodeState.getOrchidNode();
				// Live peer commit journals via journalRemote; skip ship OpLog+tip when tip already covers seq.
				final boolean tipCovers = orchid != null && op.opSeq() <= orchid.getLastCommittedSeq();
				if (!tipCovers) {
					opLog.tryAppendDeferred(op);
					if (orchid != null) {
						orchid.advanceCommittedTip(op.opSeq(), "ship");
					}
				} else if (VisibilityDiag.enabled()
						&& (op.type() == ReplicationOpType.UPSERT || op.type() == ReplicationOpType.DELETE)) {
					VisibilityDiag.debugf(VisibilityDiag.WHERE_APPLIER_SHIP_TIP_COVERS,
							"stream=%s type=%s opSeq=%d localTip=%d %s",
							lockKey,
							op.type(),
							op.opSeq(),
							orchid.getLastCommittedSeq(),
							VisibilityDiag.keyTag(op.key()));
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

                if (VisibilityDiag.enabled()) {
                    VisibilityDiag.debugf("applier.stageEnvelope",
                            "stream=%s txId=%d type=%s %s %s opSeq=%d",
                            lockKey, openTxId, op.type(), VisibilityDiag.keyTag(op.key()),
                            VisibilityDiag.valTag(value), op.opSeq());
                }

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

                if (VisibilityDiag.enabled()) {
                    VisibilityDiag.debugf("applier.stageLocal",
                            "stream=%s type=%s stagedNow=%d %s %s opSeq=%d",
                            lockKey, op.type(), staging.size(), VisibilityDiag.keyTag(op.key()),
                            VisibilityDiag.valTag(value), op.opSeq());
                }

				nodeState.advanceApplied(op.domainType(), op.shard(), op.opSeq());
				completeWaiters(op.domainType(), op.shard(), op.opSeq());
				if (ackSender != null) {
					ackSender.sendAck(op);
				}
				return;
			}

            if (VisibilityDiag.enabled()) {
                VisibilityDiag.debugf("applier.installDirect",
                        "stream=%s type=%s %s %s opSeq=%d",
                        lockKey, op.type(), VisibilityDiag.keyTag(op.key()),
                        VisibilityDiag.valTag(value), op.opSeq());
            }

			installToMap(op.shard(), op.type(), op.key(), value);

			nodeState.advanceApplied(op.domainType(), op.shard(), op.opSeq());
			completeWaiters(op.domainType(), op.shard(), op.opSeq());
			final OrchidNode installedOrchid = nodeState.getOrchidNode();
			if (installedOrchid != null) {
				installedOrchid.noteInstallCatchUpProgress(op.opSeq());
			}
			if (ackSender != null) {
				ackSender.sendAck(op);
			}
		}
	}

	/**
	 * Peer consensus durability: reshipable OpLog bytes without tip fsync / OpLog.force.
	 * Race-safe vs concurrent OPLOG_PUSH ({@link OpLog#tryAppendDeferred}); never throws OOO.
	 * Must run outside {@code shardLocks}.
	 */
	private void journalRemoteConsensusOp(ReplicationOp op) {
		if (op == null || op.opSeq() <= 0L) {
			return;
		}
		opLog.tryAppendDeferred(op);
	}

	/**
	 * Clear per-stream local TX staging only (no envelope coordinator wipe).
	 * <p>
	 * Hydrate / OpLog replay must use this — {@link #discardOpenTxStaging()} also calls
	 * {@link TxEnvelopeCoordinator#discardAllApply()} which drops waiting multi-stream
	 * envelopes (GHA M: child half staged, hydrate wipe, late parent never flushEnvelope →
	 * Elle G-single nil read).
	 */
	public void discardLocalOpenTxStaging() {
		txStaging.clear();
		openTxIdByStream.clear();
	}

	/**
	 * Discard any open TX staging including multi-stream apply envelopes.
	 * Map never sees partial TX rows.
	 * <p>
	 * PITR / crash recovery only. Hydrate must use {@link #discardLocalOpenTxStaging()}.
	 * Commit abort must use {@link #discardOpenTxStaging(String, int)} so a failed unit on
	 * shard A cannot wipe in-flight staging on shard B.
	 */
	public void discardOpenTxStaging() {
		discardLocalOpenTxStaging();
		final TxEnvelopeCoordinator gate = envelopeCoordinator;
		if (gate != null) {
			gate.discardAllApply();
		}
	}

	/**
	 * Discard open TX staging for one {@code domain#shard} stream only.
	 */
	public void discardOpenTxStaging(String domainType, int shard) {
		final String lockKey = domainType + "#" + shard;
		synchronized (shardLocks.computeIfAbsent(lockKey, _ -> new Object())) {
			final Long txId = openTxIdByStream.remove(lockKey);
			txStaging.remove(lockKey);
			final TxEnvelopeCoordinator gate = envelopeCoordinator;
			if (txId != null && gate != null) {
				gate.discardApply(txId);
			}
		}
	}

	/**
	 * Close open TX staging when {@code TX_COMMIT}/{@code TX_ABORT} is applied at or behind
	 * the watermark (duplicate / repair / tip race). Returns {@code true} when handled.
	 */
	private boolean closeOpenTxIfPresent(String lockKey, ReplicationOp op) {
		if (op.type() == ReplicationOpType.TX_COMMIT) {
			if (!hasOpenStaging(lockKey)) {
				return false;
			}
			final long txId = openTxIdByStream.getOrDefault(
					lockKey, TxEnvelopeCodec.txIdFromKey(op.key()));
			final TxEnvelopeCoordinator gate = envelopeCoordinator;
			final TxEnvelopeCodec.StreamRef stream =
					new TxEnvelopeCodec.StreamRef(op.domainType(), op.shard());
			if (gate != null && gate.isMultiApplyOpen(txId)) {
				openTxIdByStream.remove(lockKey);
				txStaging.remove(lockKey);
				final boolean complete = gate.noteApplyCommit(txId, stream);

                if (VisibilityDiag.enabled()) {
                    VisibilityDiag.debugf("applier.watermark.closeTx",
                            "multi stream=%s txId=%d complete=%s opSeq=%d",
                            lockKey, txId, complete, op.opSeq());
                }

				if (complete) {
					flushEnvelopeStagingByOwningDomain(gate.takeApplyStagingByStream(txId));
				}
				return true;
			}

            if (VisibilityDiag.enabled()) {
                VisibilityDiag.debugf("applier.watermark.closeTx",
                        "single stream=%s txId=%d opSeq=%d", lockKey, txId, op.opSeq());
            }

			flushStaging(lockKey, op.shard());
			txStaging.remove(lockKey);
			openTxIdByStream.remove(lockKey);
			return true;
		}
		if (op.type() == ReplicationOpType.TX_ABORT) {
			if (!hasOpenStaging(lockKey)) {
				return false;
			}
			final long txId = openTxIdByStream.getOrDefault(
					lockKey, TxEnvelopeCodec.txIdFromKey(op.key()));
			final TxEnvelopeCoordinator gate = envelopeCoordinator;
			if (gate != null && gate.isMultiApplyOpen(txId)) {
				gate.discardApply(txId);
			}
			txStaging.remove(lockKey);
			openTxIdByStream.remove(lockKey);
			return true;
		}
		return false;
	}

	private boolean hasOpenStaging(String lockKey) {
		if (openTxIdByStream.containsKey(lockKey)) {
			return true;
		}
		final List<StagedMutation> staging = txStaging.get(lockKey);
		return staging != null;
	}

	/** Test helper: whether a stream currently buffers an open TX. */
	@VisibleForTesting
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
		return waiters.computeIfAbsent(key, _ -> new CompletableFuture<>());
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
		final String ddlSql = new String(value, StandardCharsets.UTF_8);
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
			final OrchidNode orchid = nodeState.getOrchidNode();
			final boolean tipCovers = orchid != null && op.opSeq() <= orchid.getLastCommittedSeq();
			if (!tipCovers) {
				opLog.tryAppendDeferred(op);
				if (orchid != null) {
					orchid.advanceCommittedTip(op.opSeq(), "persistAndAck");
				}
			}
		}
		nodeState.advanceApplied(op.domainType(), op.shard(), op.opSeq());
		completeWaiters(op.domainType(), op.shard(), op.opSeq());
		// Markers / DDL cover tip without map UPSERT — clear tip-ok empty-map fence when seq covers tip.
		final OrchidNode orchid = nodeState.getOrchidNode();
		if (orchid != null) {
			orchid.noteInstallCatchUpProgress(op.opSeq());
		}
		if (ackSender != null) {
			ackSender.sendAck(op);
		}
	}

	private void flushStaging(String lockKey, int shard) {
		final List<StagedMutation> staging = txStaging.get(lockKey);
		if (staging == null || staging.isEmpty()) {
            if (VisibilityDiag.enabled()) {
                VisibilityDiag.debugf("applier.flushStaging", "empty stream=%s shard=%d", lockKey, shard);
            }

            return;
		}

        if (VisibilityDiag.enabled()) {
            VisibilityDiag.debugf("applier.flushStaging", "stream=%s shard=%d count=%d",
                    lockKey, shard, staging.size());
        }

		for (StagedMutation m : staging) {
            if (VisibilityDiag.enabled()) {
                VisibilityDiag.debugf("applier.flushStaging.item",
                        "stream=%s type=%s %s %s",
                        lockKey, m.type(), VisibilityDiag.keyTag(m.key()), VisibilityDiag.valTag(m.value()));
            }

			installToMap(shard, m.type(), m.key(), m.value());
		}
	}

	/**
	 * Install each stream's staged mutations via that stream's domain applier.
	 * Never fall back to {@code this} for a foreign domain (cross-table TX / Elle G2).
	 */
	private void flushEnvelopeStagingByOwningDomain(
			Map<TxEnvelopeCodec.StreamRef, List<TxEnvelopeCoordinator.StagedMutation>> byStream
	) {
		if (byStream == null || byStream.isEmpty()) {
            if (VisibilityDiag.enabled()) {
                VisibilityDiag.debug("applier.flushEnvelope", "empty");
            }

			return;
		}

        if (VisibilityDiag.enabled()) {
            VisibilityDiag.debugf("applier.flushEnvelope", "streams=%d", byStream.size());
        }

		final Function<String, ReplicaApplier> resolver = applierByDomain;
		if (resolver == null) {
			throw new IllegalStateException(ERR_APPLIER_BY_DOMAIN_UNSET);
		}
		for (Map.Entry<TxEnvelopeCodec.StreamRef, List<TxEnvelopeCoordinator.StagedMutation>> e
				: byStream.entrySet()) {
			final TxEnvelopeCodec.StreamRef ref = e.getKey();
			final List<TxEnvelopeCoordinator.StagedMutation> staging = e.getValue();
			if (ref == null || staging == null || staging.isEmpty()) {
				continue;
			}

            if (VisibilityDiag.enabled()) {
                VisibilityDiag.debugf("applier.flushEnvelope",
                        "domain=%s shard=%d count=%d", ref.domain(), ref.shard(), staging.size());
            }

			final ReplicaApplier owner = resolver.apply(ref.domain());
			if (owner == null) {
				throw new IllegalStateException(ERR_APPLIER_MISSING_PREFIX + ref.domain());
			}
			owner.installOwnedEnvelopeStaging(staging);
		}
	}

	/** Map+index install for envelope staging owned by this domain applier. */
	private void installOwnedEnvelopeStaging(List<TxEnvelopeCoordinator.StagedMutation> staging) {
        if (VisibilityDiag.enabled()) {
            VisibilityDiag.debugf("applier.flushEnvelope.item", "install count=%d", staging.size());
        }

        for (TxEnvelopeCoordinator.StagedMutation m : staging) {
            if (VisibilityDiag.enabled()) {
                VisibilityDiag.debugf("applier.flushEnvelope.item",
                        "type=%s shard=%d %s %s", m.type(), m.shard(),
                        VisibilityDiag.keyTag(m.key()), VisibilityDiag.valTag(m.value()));
            }

			installToMap(m.shard(), m.type(), m.key(), m.value());
		}
	}

	/**
	 * Repair path: overwrite local map for an already-applied seq (no OpLog re-append).
	 */
	private void forceReinstall(ReplicationOp op) {
		byte[] value = op.value();
		if (DuplexCodecSupport.isActiveForReplication() && DuplexBlob.isWire(value)) {
			final DuplexBlob blob = DuplexBlob.fromWireBytes(value);
			final DuplexBlob verified = DuplexCodecSupport.getCodec().getVerifier().verifyOrRepair(blob);
			value = verified.toWireBytes();
		}
		if (op.type() == ReplicationOpType.UPSERT
				|| op.type() == ReplicationOpType.DELETE) {
            if (VisibilityDiag.enabled()) {
                VisibilityDiag.debugf("applier.forceReinstall",
                        "stream=%s#%d type=%s opSeq=%d %s %s",
                        op.domainType(), op.shard(), op.type(), op.opSeq(),
                        VisibilityDiag.keyTag(op.key()), VisibilityDiag.valTag(value));
            }

			installToMap(op.shard(), op.type(), op.key(), value);
		}
		final OrchidNode orchid = nodeState.getOrchidNode();
		if (orchid != null) {
			orchid.noteInstallCatchUpProgress(op.opSeq());
		}
		if (ackSender != null) {
			ackSender.sendAck(op);
		}
	}

	private void installToMap(int shard, ReplicationOpType type, byte[] key, byte[] value) {
		if (!applyToLocalMap || processorByShard == null) {
            if (VisibilityDiag.enabled()) {
                VisibilityDiag.debugf("applier.installToMap",
                        "skip applyToLocalMap=%s processorByShardNull=%s shard=%d type=%s %s",
                        applyToLocalMap, processorByShard == null, shard, type, VisibilityDiag.keyTag(key));
            }

            return;
		}

		final GridEntriesProcessor processor = processorByShard.apply(shard);
		if (processor == null) {
            if (VisibilityDiag.enabled()) {
                VisibilityDiag.debugf("applier.installToMap",
                        "skip null processor shard=%d type=%s %s", shard, type, VisibilityDiag.keyTag(key));
            }

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
