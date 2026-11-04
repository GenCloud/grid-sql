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
package org.genfork.grid.replication.snapshot.sealed;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.log.OpLog;
import org.genfork.grid.serial.LogicalFieldCursor;
import org.genfork.grid.utils.ArrayUtil;

/**
 * Static helpers for sealed dump merge: OpLog delta replay, merge→{@code .sbpt} postings, and filename parsing.
 * <p>
 * Seal dump must not mutate live RAM BPTrees — concurrent STRICT EQ / ON CONFLICT probe those trees.
 * RAM overlay must apply sealed-delete tombstones as {@code remove} (not skip), or STRICT UNIQUE
 * displace losers from prior sealed reappear after LAZY reopen.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
public final class SealedDumpMergeUtil {
	/** OpLog read batch size while merging delta into a seal dump map. */
	public static final int SEAL_DUMP_OPLOG_BATCH = 2_048;

	private SealedDumpMergeUtil() {
	}

	public static List<SealedGridMapWriter.Kv> flattenNodeBuckets(List<SealedGridMapWriter.Kv>[] nodes) {
		final List<SealedGridMapWriter.Kv> flat = new ArrayList<>();
		if (nodes == null) {
			return flat;
		}
		for (List<SealedGridMapWriter.Kv> bucket : nodes) {
			if (bucket != null) {
				flat.addAll(bucket);
			}
		}
		return flat;
	}

	/**
	 * Replay OpLog after sealed watermark into the dump merge map (UPSERT/DELETE only).
	 */
	public static void mergeOpLogDeltaInto(
			LinkedHashMap<BytesKey, byte[]> merged,
			String domainType,
			int shard,
			long priorWm,
			OpLog opLog
	) {
		if (merged == null || opLog == null || domainType == null) {
			return;
		}
		long from = Math.max(1L, priorWm + 1L);
		while (true) {
			final List<ReplicationOp> batch = opLog.readFrom(domainType, shard, from, SEAL_DUMP_OPLOG_BATCH);
			if (batch.isEmpty()) {
				break;
			}
			for (ReplicationOp op : batch) {
				if (op.type() == ReplicationOpType.UPSERT) {
					merged.put(new BytesKey(op.key()), op.value());
				} else if (op.type() == ReplicationOpType.DELETE) {
					merged.remove(new BytesKey(op.key()));
				}
				from = op.opSeq() + 1L;
			}
			if (batch.size() < SEAL_DUMP_OPLOG_BATCH) {
				break;
			}
		}
	}

	/**
	 * Build sealed {@code .sbpt} postings from the dump merge snapshot (not live RAM BPTree walks).
	 */
	public static List<SealedBPTreeWriter.Entry> singleColumnSealedEntries(
			LinkedHashMap<BytesKey, byte[]> merged,
			TableSchema schema,
			String column,
			int shard,
			int shardCount
	) {
		if (merged == null || merged.isEmpty() || schema == null || column == null) {
			return List.of();
		}
		final int ordinal = schema.requireColumn(column).ordinal();
		final List<SealedBPTreeWriter.Entry> entries = new ArrayList<>(merged.size());
		for (Map.Entry<BytesKey, byte[]> e : merged.entrySet()) {
			final byte[] rowKey = e.getKey().bytes();
			if (Math.abs(ArrayUtil.fastHash(rowKey) % shardCount) != shard) {
				continue;
			}
			final byte[] value = e.getValue();
			if (value == null || !LogicalFieldCursor.canOpen(schema, value)) {
				continue;
			}
			final LogicalFieldCursor cursor = LogicalFieldCursor.open(schema, value);
			final byte[] indexKey = cursor.indexKeyBytes(ordinal);
			if (indexKey != null) {
				entries.add(new SealedBPTreeWriter.Entry(indexKey, rowKey));
			}
		}
		return entries;
	}

	/**
	 * Composite secondary index postings from the dump merge snapshot.
	 */
	public static List<SealedBPTreeWriter.Entry> compositeSealedEntries(
			LinkedHashMap<BytesKey, byte[]> merged,
			TableSchema schema,
			List<String> columns,
			int shard,
			int shardCount
	) {
		if (merged == null || merged.isEmpty() || schema == null || columns == null || columns.isEmpty()) {
			return List.of();
		}
		final int[] ordinals = new int[columns.size()];
		for (int i = 0; i < columns.size(); i++) {
			ordinals[i] = schema.requireColumn(columns.get(i)).ordinal();
		}
		final List<SealedBPTreeWriter.Entry> entries = new ArrayList<>(merged.size());
		for (Map.Entry<BytesKey, byte[]> e : merged.entrySet()) {
			final byte[] rowKey = e.getKey().bytes();
			if (Math.abs(ArrayUtil.fastHash(rowKey) % shardCount) != shard) {
				continue;
			}
			final byte[] value = e.getValue();
			if (value == null || !LogicalFieldCursor.canOpen(schema, value)) {
				continue;
			}
			final LogicalFieldCursor cursor = LogicalFieldCursor.open(schema, value);
			final byte[][] indexKeyParts = new byte[ordinals.length][];
			for (int c = 0; c < ordinals.length; c++) {
				indexKeyParts[c] = cursor.indexKeyBytes(ordinals[c]);
			}
			entries.add(new SealedBPTreeWriter.Entry(
					SealedCompositeIndexKey.encode(indexKeyParts), rowKey));
		}
		return entries;
	}

	/** Equality by wire key bytes for sealed∪RAM merge. */
	public record BytesKey(byte[] bytes) {
		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof BytesKey other)) {
				return false;
			}
			return Arrays.equals(bytes, other.bytes);
		}

		@Override
		public int hashCode() {
			return Arrays.hashCode(bytes);
		}
	}

	public static int parseNode(String name) throws IOException {
		final int marker = name.lastIndexOf("_n");
		final int extension = name.lastIndexOf(".gmap");
		if (marker < 0 || extension <= marker + 2) {
			throw new IOException("invalid sealed node filename: " + name);
		}
		try {
			return Integer.parseInt(name.substring(marker + 2, extension));
		} catch (NumberFormatException e) {
			throw new IOException("invalid sealed node filename: " + name, e);
		}
	}

	public static int parseShard(String streamKey) {
		return Integer.parseInt(streamKey.substring(streamKey.lastIndexOf('#') + 1));
	}

	public static void closeQuietly(SealedGridMapReader reader) {
		try {
			reader.close();
		} catch (IOException ignored) {
			// Reader replacement remains authoritative; close is best effort.
		}
	}
}
