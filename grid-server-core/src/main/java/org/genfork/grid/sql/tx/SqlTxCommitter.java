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
package org.genfork.grid.sql.tx;

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.metrics.SqlTxMetrics;
import org.genfork.grid.replication.ReplicaAccessGate;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.replication.apply.ReplicaApplier;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.netty.NettyReplicationTransport;
import org.genfork.grid.replication.tx.StreamCommitSerializer;
import org.genfork.grid.replication.tx.TxEnvelopeCodec;
import org.genfork.grid.sql.SqlSession;
import org.genfork.grid.store.TableStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQL TX commit-unit orchestrator.
 * <p>
 * Open TX: dirty only in {@link SqlTxBuffer} (no ORCHID / map visibility).
 * Single-stream + replication: one OpLog group fsync for {@code TX_BEGIN}+data+{@code TX_COMMIT}
 * via {@link TableStore#flushTxUnit}. Multi-stream: barrier begin/end with batched markers
 * (one orchid confirm tip) + per-stream data {@link TableStore#flushTxBatch}.
 * Without replication: local install with undo snapshots only.
 *
 * @author: GenCloud
 * @date: 2026/01
 * @since: 1.0
 */
public final class SqlTxCommitter {
	private final TableCatalog catalog;
	private final ReplicationCoordinator replication;

	public SqlTxCommitter(TableCatalog catalog, ReplicationCoordinator replication) {
		this.catalog = catalog;
		this.replication = replication;
	}

	public void commit(SqlSession session) {
		final SqlTxBuffer buf = session.requireTx();
		final boolean locksOnly = buf.isEmpty() && !buf.peerLockedLeases().isEmpty();
		if (buf.isEmpty() && !locksOnly) {
			catalog.flushSequencesIfDirty();
			session.endTx();
			SqlTxMetrics.recordCommit();
			return;
		}
		final NettyReplicationTransport netty =
				replication != null ? replication.getNettyTransport() : null;
		DistForUpdatePrepareVotes.prepareOrThrow(session, buf, netty);
		if (locksOnly) {
			finishForUpdatePrepare(session, buf, true);
			catalog.flushSequencesIfDirty();
			session.endTx();
			SqlTxMetrics.recordCommit();
			return;
		}
		final boolean repl = replication != null && replication.isEnabled();
		final List<Installed> installed = new ArrayList<>();
		final MultiShardCommitBarrier barrier = new MultiShardCommitBarrier();
		for (SqlTxBuffer.Participation p : buf.participations()) {
			barrier.registerStream(p.table(), p.shard());
		}
		final byte[] envelopeValue = encodeEnvelope(buf);
		final boolean singleStream = barrier.participatingStreams().size() == 1;
		try {
			if (repl) {
				ReplicaAccessGate.ensureWrite(replication);
				if (singleStream) {
					flushDirtyAsUnits(buf, envelopeValue, installed);
				} else {
					final StreamCommitSerializer serializer = replication.getStreamCommitSerializer();
					final List<String> streamKeys = new ArrayList<>(barrier.participatingStreams().size());
					for (MultiShardCommitBarrier.StreamKey sk : barrier.participatingStreams()) {
						streamKeys.add(sk.table() + "#" + sk.shard());
					}
					final Runnable multiCommit = () -> {
						barrier.beginAllBatch(streams -> replication.recordTxMarkersBatch(
								ReplicationOpType.TX_BEGIN, buf.txId(), toStreamRefs(streams), envelopeValue));
						barrier.markOps();
						flushDirty(buf, installed);
						barrier.endAllBatch(streams -> replication.recordTxMarkersBatch(
								ReplicationOpType.TX_COMMIT, buf.txId(), toStreamRefs(streams), null));
					};
					if (serializer != null) {
						serializer.runWithLocks(streamKeys, multiCommit);
					} else {
						multiCommit.run();
					}
				}
			} else {
				flushDirty(buf, installed);
			}
			finishForUpdatePrepare(session, buf, true);
			catalog.flushSequencesIfDirty();
			session.endTx();
			SqlTxMetrics.recordCommit();
		} catch (RuntimeException ex) {
			finishForUpdatePrepare(session, buf, false);
			if (!repl) {
				restoreInstalled(installed);
			} else {
				barrier.abortBegun(sk -> {
					try {
						replication.recordTxMarker(
								ReplicationOpType.TX_ABORT, buf.txId(), sk.table(), sk.shard());
					} catch (RuntimeException ignored) {
					}
					final ReplicaApplier applier = replication.applier(sk.table());
					if (applier != null) {
						applier.discardOpenTxStaging();
					}
				});
				if (singleStream) {
					for (MultiShardCommitBarrier.StreamKey sk : barrier.participatingStreams()) {
						final ReplicaApplier applier = replication.applier(sk.table());
						if (applier != null) {
							applier.discardOpenTxStaging();
						}
					}
				}
			}
			session.endTx();
			throw ex;
		}
	}

	public void rollback(SqlSession session) {
		final SqlTxBuffer buf = session.requireTx();
		finishForUpdatePrepare(session, buf, false);
		session.endTx();
		SqlTxMetrics.recordRollback();
	}

	private void finishForUpdatePrepare(SqlSession session, SqlTxBuffer buf, boolean commit) {
		final NettyReplicationTransport netty =
				replication != null ? replication.getNettyTransport() : null;
		DistForUpdatePrepareVotes.finish(session, buf, netty, commit);
	}

	private static byte[] encodeEnvelope(SqlTxBuffer buf) {
		final List<TxEnvelopeCodec.StreamRef> streams = new ArrayList<>();
		for (SqlTxBuffer.Participation p : buf.participations()) {
			streams.add(new TxEnvelopeCodec.StreamRef(p.table(), p.shard()));
		}
		return TxEnvelopeCodec.encode(streams);
	}

	private void flushDirtyAsUnits(SqlTxBuffer buf, byte[] beginValue, List<Installed> installed) {
		final Map<MultiShardCommitBarrier.StreamKey, List<SqlTxBuffer.DirtyEntry>> byStream =
				groupByStream(buf);
		for (Map.Entry<MultiShardCommitBarrier.StreamKey, List<SqlTxBuffer.DirtyEntry>> se
				: byStream.entrySet()) {
			final TableStore store = catalog.getStore(se.getKey().table());
			final List<TableStore.TxFlushOp> batch = toFlushOps(se.getValue());
			final List<TableStore.PriorBytes> priors =
					store.flushTxUnit(buf.txId(), beginValue, batch);
			for (TableStore.PriorBytes prior : priors) {
				installed.add(new Installed(store, prior));
			}
		}
	}

	private void flushDirty(SqlTxBuffer buf, List<Installed> installed) {
		final Map<MultiShardCommitBarrier.StreamKey, List<SqlTxBuffer.DirtyEntry>> byStream =
				groupByStream(buf);
		for (Map.Entry<MultiShardCommitBarrier.StreamKey, List<SqlTxBuffer.DirtyEntry>> se
				: byStream.entrySet()) {
			final TableStore store = catalog.getStore(se.getKey().table());
			final List<TableStore.TxFlushOp> batch = toFlushOps(se.getValue());
			final List<TableStore.PriorBytes> priors = store.flushTxBatch(batch);
			for (TableStore.PriorBytes prior : priors) {
				installed.add(new Installed(store, prior));
			}
		}
	}

	private Map<MultiShardCommitBarrier.StreamKey, List<SqlTxBuffer.DirtyEntry>> groupByStream(
			SqlTxBuffer buf
	) {
		final Map<MultiShardCommitBarrier.StreamKey, List<SqlTxBuffer.DirtyEntry>> byStream =
				new LinkedHashMap<>();
		for (Map.Entry<String, Map<KeyWrapper, SqlTxBuffer.DirtyEntry>> te : buf.tables()) {
			if (catalog.getStore(te.getKey()) == null) {
				throw new IllegalStateException("table gone during commit: " + te.getKey());
			}
			for (SqlTxBuffer.DirtyEntry e : te.getValue().values()) {
				byStream.computeIfAbsent(
						new MultiShardCommitBarrier.StreamKey(te.getKey(), e.shard()),
						k -> new ArrayList<>()
				).add(e);
			}
		}
		return byStream;
	}

	private static List<TableStore.TxFlushOp> toFlushOps(List<SqlTxBuffer.DirtyEntry> dirty) {
		final List<TableStore.TxFlushOp> batch = new ArrayList<>(dirty.size());
		for (SqlTxBuffer.DirtyEntry e : dirty) {
			batch.add(new TableStore.TxFlushOp(
					e.keyBytes(),
					e.valueBytesOrNull(),
					e.op() == SqlTxBuffer.Op.DELETE));
		}
		return batch;
	}

	private static List<TxEnvelopeCodec.StreamRef> toStreamRefs(
			List<MultiShardCommitBarrier.StreamKey> streams
	) {
		final List<TxEnvelopeCodec.StreamRef> refs = new ArrayList<>(streams.size());
		for (MultiShardCommitBarrier.StreamKey sk : streams) {
			refs.add(new TxEnvelopeCodec.StreamRef(sk.table(), sk.shard()));
		}
		return refs;
	}

	private static void restoreInstalled(List<Installed> installed) {
		for (int i = installed.size() - 1; i >= 0; i--) {
			final Installed one = installed.get(i);
			try {
				one.store().restorePrior(one.prior());
			} catch (RuntimeException ignored) {
			}
		}
	}

	private record Installed(TableStore store, TableStore.PriorBytes prior) {
	}
}