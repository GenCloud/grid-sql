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
package org.genfork.grid.sql.exec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.genfork.grid.catalog.ColumnDef;
import org.genfork.grid.catalog.SqlType;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.serial.LogicalFieldCursor;
import org.genfork.grid.serial.SqlWireUtil;
import org.genfork.grid.serial.WireFieldBytes;
import org.genfork.grid.serial.WireSpan;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.ast.SelectAst.SelectSql;
import org.genfork.grid.utils.SerialUtil;

/**
 * Wire-key GROUP BY / DISTINCT / MIN helpers (no Map Object mid-pipeline).
 * <p>
 * Blob GROUP BY / COUNT keys use {@link WireSpan} (zero-copy) or composite
 * {@link WireFieldBytes}. INT GROUP BY uses open-addressed primitive keys.
 * Projected-row DISTINCT / window identity use owned {@link WireFieldBytes}.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlWireAggOps {
	private static final int HASH_MAP_MIN_CAPACITY = 16;
	private static final int DISTINCT_INITIAL_CAPACITY = 16;
	/** Hint for typical low-cardinality GROUP BY maps (not blobs.size()). */
	private static final int GROUP_MAP_CAPACITY_HINT = 64;
	private static final byte FIELD_SEP = 0x1F;
	/** Murmur-inspired finalizer mix for open-addressed INT group keys. */
	private static final int INT_HASH_MIX = 0x85ebca6b;

	private SqlWireAggOps() {
	}

	/**
	 * COUNT(*) GROUP BY over value blobs using zero-copy wire group keys.
	 */
	public static List<Object[]> countStarGroupByBlobs(
			TableSchema schema,
			List<byte[]> blobs,
			int groupOrdinal
	) {
		return countStarGroupByBlobs(schema, blobs, new int[]{groupOrdinal});
	}

	/**
	 * COUNT(*) multi-column GROUP BY (composite {@link WireFieldBytes} keys).
	 */
	public static List<Object[]> countStarGroupByBlobs(
			TableSchema schema,
			List<byte[]> blobs,
			int[] groupOrdinals
	) {
		if (groupOrdinals == null || groupOrdinals.length == 0) {
			throw new IllegalArgumentException("GROUP BY requires at least one column");
		}
		if (groupOrdinals.length == 1) {
			final Class<?> groupType = schema.columns().get(groupOrdinals[0]).javaType();
			if (groupType == Integer.class) {
				return countStarGroupByIntBlobs(schema, blobs, groupOrdinals[0]);
			}
			return countStarGroupBySingleSpan(schema, blobs, groupOrdinals[0]);
		}
		final int capacity = Math.max(
				HASH_MAP_MIN_CAPACITY,
				Math.min(GROUP_MAP_CAPACITY_HINT, Math.max(1, blobs.size())));
		final Map<WireFieldBytes, Long> groups = new LinkedHashMap<>(capacity);
		final Map<WireFieldBytes, Object[]> emitValues = new LinkedHashMap<>(capacity);
		for (byte[] blob : blobs) {
			final LogicalFieldCursor cur = LogicalFieldCursor.open(schema, blob);
			final WireFieldBytes key = compositeGroupKeyFromBlob(schema, blob, groupOrdinals);
			final Long prev = groups.get(key);
			if (prev == null) {
				groups.put(key, 1L);
				final Object[] emit = new Object[groupOrdinals.length];
				for (int i = 0; i < groupOrdinals.length; i++) {
					emit[i] = cur.read(groupOrdinals[i]);
				}
				emitValues.put(key, emit);
			} else {
				groups.put(key, prev + 1L);
			}
		}
		final List<Object[]> out = new ArrayList<>(groups.size());
		for (Map.Entry<WireFieldBytes, Long> e : groups.entrySet()) {
			final Object[] emit = emitValues.get(e.getKey());
			final Object[] row = new Object[emit.length + 1];
			System.arraycopy(emit, 0, row, 0, emit.length);
			row[emit.length] = (double) e.getValue();
			out.add(row);
		}
		return out;
	}

	private static List<Object[]> countStarGroupBySingleSpan(
			TableSchema schema,
			List<byte[]> blobs,
			int groupOrdinal
	) {
		final int capacity = Math.max(
				HASH_MAP_MIN_CAPACITY,
				Math.min(GROUP_MAP_CAPACITY_HINT, Math.max(1, blobs.size())));
		final Map<WireSpan, Long> groups = new LinkedHashMap<>(capacity);
		final Map<WireSpan, Object> emitValues = new LinkedHashMap<>(capacity);
		for (byte[] blob : blobs) {
			final LogicalFieldCursor cur = LogicalFieldCursor.open(schema, blob);
			final WireSpan key = cur.indexKeySpan(groupOrdinal);
			final Long prev = groups.get(key);
			if (prev == null) {
				groups.put(key, 1L);
				emitValues.put(key, cur.read(groupOrdinal));
			} else {
				groups.put(key, prev + 1L);
			}
		}
		final List<Object[]> out = new ArrayList<>(groups.size());
		for (Map.Entry<WireSpan, Long> e : groups.entrySet()) {
			out.add(new Object[]{emitValues.get(e.getKey()), (double) e.getValue()});
		}
		return out;
	}

	/**
	 * INT GROUP BY COUNT(*) — open-addressed wire int keys (no Object mid-pipeline).
	 */
	static List<Object[]> countStarGroupByIntBlobs(
			TableSchema schema,
			List<byte[]> blobs,
			int groupOrdinal
	) {
		final int tableSize = nextPowerOfTwo(Math.max(GROUP_MAP_CAPACITY_HINT, HASH_MAP_MIN_CAPACITY));
		final int mask = tableSize - 1;
		final int[] keys = new int[tableSize];
		final long[] counts = new long[tableSize];
		final boolean[] used = new boolean[tableSize];
		int distinct = 0;
		for (byte[] blob : blobs) {
			final LogicalFieldCursor cur = LogicalFieldCursor.open(schema, blob);
			final WireSpan span = cur.indexKeySpan(groupOrdinal);
			if (span.isNullWire() || span.length() < Integer.BYTES) {
				continue;
			}
			final int key = SerialUtil.readI32(span.blob(), span.offset());
			int slot = mixInt(key) & mask;
			while (used[slot] && keys[slot] != key) {
				slot = (slot + 1) & mask;
			}
			if (!used[slot]) {
				used[slot] = true;
				keys[slot] = key;
				counts[slot] = 1L;
				distinct++;
			} else {
				counts[slot]++;
			}
		}
		final List<Object[]> out = new ArrayList<>(distinct);
		for (int i = 0; i < tableSize; i++) {
			if (used[i]) {
				out.add(new Object[]{keys[i], (double) counts[i]});
			}
		}
		return out;
	}

	private static int mixInt(int key) {
		int x = key;
		x ^= (x >>> 16);
		x *= INT_HASH_MIX;
		x ^= (x >>> 13);
		return x;
	}

	private static int nextPowerOfTwo(int n) {
		int v = 1;
		while (v < n) {
			v <<= 1;
		}
		return v;
	}

	/**
	 * Single-pass MIN/MAX over one column of value blobs.
	 */
	public static double minMaxFromBlobs(TableSchema schema, List<byte[]> blobs, int colOrdinal, boolean min) {
		Double best = null;
		for (byte[] blob : blobs) {
			final Object raw = LogicalFieldCursor.open(schema, blob).read(colOrdinal);
			if (raw == null) {
				continue;
			}
			final double v = raw instanceof Number num
					? num.doubleValue()
					: Double.parseDouble(String.valueOf(raw));
			if (best == null) {
				best = v;
			} else if (min) {
				best = Math.min(best, v);
			} else {
				best = Math.max(best, v);
			}
		}
		return best == null ? 0d : best;
	}

	/**
	 * DISTINCT on projected Object[] rows using wire-encoded composite keys.
	 */
	public static List<Object[]> distinctWire(List<Object[]> rows) {
		if (rows.isEmpty()) {
			return rows;
		}
		final Set<WireFieldBytes> seen = new HashSet<>(
				Math.max(DISTINCT_INITIAL_CAPACITY, rows.size() * 2));
		final List<Object[]> out = new ArrayList<>(rows.size());
		for (Object[] row : rows) {
			if (seen.add(wireKeyOfRow(row))) {
				out.add(row);
			}
		}
		return out;
	}

	/**
	 * Partition key for window ops (single column decoded at SPI edge → owned wire).
	 */
	public static WireFieldBytes partitionWireKey(Object value) {
		return new WireFieldBytes(SqlWireUtil.toGenericArray(value));
	}

	/**
	 * Composite PARTITION BY / GROUP BY key from projected row cells (WireRangeOps hash/equals).
	 */
	public static WireFieldBytes compositeWireKey(Object[] row, int[] ordinals) {
		if (ordinals == null || ordinals.length == 0) {
			return new WireFieldBytes(null);
		}
		if (ordinals.length == 1) {
			return partitionWireKey(row[ordinals[0]]);
		}
		final Object[] parts = new Object[ordinals.length];
		for (int i = 0; i < ordinals.length; i++) {
			parts[i] = row[ordinals[i]];
		}
		return wireKeyOfRow(parts);
	}

	/**
	 * Composite GROUP BY key from blob spans (owned copy at map boundary).
	 */
	public static WireFieldBytes compositeGroupKeyFromBlob(
			TableSchema schema,
			byte[] blob,
			int[] groupOrdinals
	) {
		if (groupOrdinals == null || groupOrdinals.length == 0) {
			return new WireFieldBytes(null);
		}
		final LogicalFieldCursor cur = LogicalFieldCursor.open(schema, blob);
		if (groupOrdinals.length == 1) {
			return WireFieldBytes.copyOf(cur.indexKeySpan(groupOrdinals[0]));
		}
		int total = 0;
		final byte[][] parts = new byte[groupOrdinals.length][];
		for (int i = 0; i < groupOrdinals.length; i++) {
			parts[i] = cur.indexKeySpan(groupOrdinals[i]).toOwnedBytes();
			total += parts[i].length + 1;
		}
		final byte[] composite = new byte[total];
		int pos = 0;
		for (byte[] part : parts) {
			System.arraycopy(part, 0, composite, pos, part.length);
			pos += part.length;
			composite[pos++] = FIELD_SEP;
		}
		return new WireFieldBytes(composite);
	}

	/**
	 * Composite wire key for a projected row (DISTINCT / window identity).
	 */
	public static WireFieldBytes wireKeyOfRow(Object[] row) {
		if (row == null || row.length == 0) {
			return new WireFieldBytes(null);
		}
		if (row.length == 1) {
			return new WireFieldBytes(SqlWireUtil.toGenericArray(row[0]));
		}
		int total = 0;
		final byte[][] parts = new byte[row.length][];
		for (int i = 0; i < row.length; i++) {
			parts[i] = SqlWireUtil.toGenericArray(row[i]);
			total += parts[i].length + 1;
		}
		final byte[] composite = new byte[total];
		int pos = 0;
		for (byte[] part : parts) {
			System.arraycopy(part, 0, composite, pos, part.length);
			pos += part.length;
			composite[pos++] = FIELD_SEP;
		}
		return new WireFieldBytes(composite);
	}

	public static boolean isCountStarGroupBy(SelectSql s) {
		return s != null
				&& !s.hasJoins()
				&& s.countStar()
				&& s.hasGroupBy()
				&& !s.hasWindow()
				&& !s.minAgg()
				&& !s.maxAgg()
				&& !s.avg();
	}

	/**
	 * Plain single-table MIN/MAX without GROUP BY.
	 */
	public static boolean isMinMaxNoGroup(SelectSql s) {
		return s != null
				&& !s.hasJoins()
				&& !s.hasGroupBy()
				&& (s.minAgg() || s.maxAgg())
				&& !s.countStar()
				&& !s.avg()
				&& !s.hasWindow();
	}

	public static List<SqlResult.ColumnMeta> countGroupMetas(
			String groupCol,
			ColumnDef gcol,
			String aggLabel
	) {
		return List.of(
				SqlResult.ColumnMeta.of(groupCol, gcol.type()),
				SqlResult.ColumnMeta.of(aggLabel, SqlType.DOUBLE)
		);
	}

	/**
	 * Column metas for multi-column GROUP BY + aggregate label.
	 */
	public static List<SqlResult.ColumnMeta> countGroupMetas(
			List<String> groupCols,
			List<ColumnDef> groupDefs,
			String aggLabel
	) {
		final List<SqlResult.ColumnMeta> metas = new ArrayList<>(groupCols.size() + 1);
		for (int i = 0; i < groupCols.size(); i++) {
			metas.add(SqlResult.ColumnMeta.of(groupCols.get(i), groupDefs.get(i).type()));
		}
		metas.add(SqlResult.ColumnMeta.of(aggLabel, SqlType.DOUBLE));
		return List.copyOf(metas);
	}
}
