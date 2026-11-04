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

import java.util.ArrayList;
import java.util.List;

import org.genfork.grid.replication.tx.TxEnvelopeCodec;
import org.genfork.grid.store.TableStore;

/**
 * Pure mappings between {@link SqlTxBuffer} dirty state and flush / replication wire shapes.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
public final class SqlTxCommitMappingUtil {

	private SqlTxCommitMappingUtil() {
	}

	public record InstalledRollback(TableStore store, TableStore.PriorBytes prior) {
	}

	public static byte[] encodeEnvelope(SqlTxBuffer buf) {
		final List<TxEnvelopeCodec.StreamRef> streams = new ArrayList<>();
		for (SqlTxBuffer.Participation p : buf.participations()) {
			streams.add(new TxEnvelopeCodec.StreamRef(p.table(), p.shard()));
		}
		return TxEnvelopeCodec.encode(streams);
	}

	public static List<TableStore.TxFlushOp> toFlushOps(List<SqlTxBuffer.DirtyEntry> dirty) {
		final List<TableStore.TxFlushOp> batch = new ArrayList<>(dirty.size());
		for (SqlTxBuffer.DirtyEntry e : dirty) {
			batch.add(new TableStore.TxFlushOp(
					e.keyBytes(),
					e.valueBytesOrNull(),
					e.op() == SqlTxBuffer.Op.DELETE));
		}
		return batch;
	}

	public static List<TxEnvelopeCodec.StreamRef> toStreamRefs(
			List<MultiShardCommitBarrier.StreamKey> streams
	) {
		final List<TxEnvelopeCodec.StreamRef> refs = new ArrayList<>(streams.size());
		for (MultiShardCommitBarrier.StreamKey sk : streams) {
			refs.add(new TxEnvelopeCodec.StreamRef(sk.table(), sk.shard()));
		}
		return refs;
	}

	public static void restoreInstalled(List<InstalledRollback> installed) {
		for (int i = installed.size() - 1; i >= 0; i--) {
			final InstalledRollback one = installed.get(i);
			try {
				one.store().restorePrior(one.prior());
			} catch (RuntimeException ignored) {
			}
		}
	}
}
