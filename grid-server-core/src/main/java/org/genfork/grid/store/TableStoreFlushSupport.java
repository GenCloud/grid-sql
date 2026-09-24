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
package org.genfork.grid.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;

import org.genfork.grid.mem.stage.GridEntriesProcessor;
import org.genfork.grid.mem.stage.GridEntriesProcessor.AddEntry;
import org.genfork.grid.mem.stage.GridEntriesProcessor.Entry;
import org.genfork.grid.mem.stage.GridEntriesProcessor.RemoveEntry;
import org.genfork.grid.store.TableStore.PriorBytes;
import org.genfork.grid.store.TableStore.TxFlushOp;

/**
 * TX flush batch/unit preparation helpers for {@link TableStore}.
 * <p>
 * Builds prior snapshots and OpLog entries for single-shard recorder paths; local
 * (no-recorder) install stays on the façade so map/index undo semantics stay co-located.
 *
 * @author: GenCloud
 * @date: 2026/02
 * @since: 1.0
 */
final class TableStoreFlushSupport {
	private static final String ERR_BATCH_SINGLE_SHARD = "flushTxBatch requires single shard";
	private static final String ERR_UNIT_SINGLE_SHARD = "flushTxUnit requires single shard";

	private TableStoreFlushSupport() {
	}

	/**
	 * Build prior snapshots + OpLog entries for a single-shard recorder flush.
	 *
	 * @param unit {@code true} when preparing {@code flushTxUnit} (error message differs)
	 */
	static PreparedBatch prepareRecorderBatch(
			List<TxFlushOp> ops,
			ToIntFunction<byte[]> shardOf,
			IntFunction<GridEntriesProcessor> processorOf,
			Consumer<byte[]> validateEncoded,
			boolean unit
	) {
		final int shard = shardOf.applyAsInt(ops.get(0).keyBytes());
		final List<PriorBytes> priors = new ArrayList<>(ops.size());
		final List<Entry> entries = new ArrayList<>(ops.size());
		final String singleShardErr = unit ? ERR_UNIT_SINGLE_SHARD : ERR_BATCH_SINGLE_SHARD;
		for (TxFlushOp op : ops) {
			Objects.requireNonNull(op, "op");
			Objects.requireNonNull(op.keyBytes(), "keyBytes");
			if (shardOf.applyAsInt(op.keyBytes()) != shard) {
				throw new IllegalArgumentException(singleShardErr);
			}
			final GridEntriesProcessor processor = processorOf.apply(shard);
			priors.add(new PriorBytes(op.keyBytes(), processor.getCommitted(op.keyBytes())));
			if (op.delete()) {
				entries.add(new RemoveEntry(op.keyBytes()));
			} else {
				validateEncoded.accept(op.valueBytesOrNull());
				entries.add(new AddEntry(null, op.keyBytes(), op.valueBytesOrNull()));
			}
		}
		return new PreparedBatch(shard, priors, entries);
	}

	/**
	 * Prior snapshots + OpLog entries for one shard recorder flush.
	 *
	 * @author: GenCloud
	 * @date: 2026/02
	 * @since: 1.0
	 */
	record PreparedBatch(int shard, List<PriorBytes> priors, List<Entry> entries) {
	}
}