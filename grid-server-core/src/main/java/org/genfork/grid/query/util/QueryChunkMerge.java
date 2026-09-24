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
package org.genfork.grid.query.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.genfork.grid.serial.SqlWireUtil;
import org.genfork.grid.serial.WireFieldBytes;

/**
 * Shared chunk merge for distributed fan-out and SQL set ops
 * ({@code UNION} / {@code INTERSECT} / {@code EXCEPT}, with optional {@code ALL}).
 * <p>
 * Set membership and distinct keys use {@link WireFieldBytes} (full-row wire keys).
 * {@code Object[]} rows are retained only for {@code SqlResult} projection.
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
public final class QueryChunkMerge {
	private static final int HASH_MAP_MIN_CAPACITY = 16;
	private static final int ROW_KEY_LENGTH_PREFIX_BYTES = 4;
	private static final int MULTISET_EMPTY = 0;
	private static final int MULTISET_UNIT = 1;
	private static final int BYTE_SHIFT_8 = 8;
	private static final int BYTE_SHIFT_16 = 16;
	private static final int BYTE_SHIFT_24 = 24;

	private QueryChunkMerge() {
	}

	/**
	 * UNION-all merge of key/blob chunks (distributed SELECT fan-out).
	 *
	 * @param chunks ordered shard results; null chunks skipped
	 * @param limit  max rows; {@code <= 0} = no cap
	 */
	public static List<byte[]> merge(List<List<byte[]>> chunks, int limit) {
		final List<byte[]> out = new ArrayList<>();
		if (chunks == null) {
			return out;
		}
		final int cap = Math.max(0, limit);
		for (List<byte[]> chunk : chunks) {
			if (chunk == null) {
				continue;
			}
			for (byte[] row : chunk) {
				out.add(row);
				if (cap > 0 && out.size() >= cap) {
					return out;
				}
			}
		}
		return out;
	}

	/**
	 * UNION-all merge of projected SQL rows.
	 *
	 * @param chunks ordered arm results; null chunks skipped
	 * @param limit  max rows; {@code <= 0} = no cap
	 */
	public static List<Object[]> mergeRows(List<List<Object[]>> chunks, int limit) {
		final List<Object[]> out = new ArrayList<>();
		if (chunks == null) {
			return out;
		}
		final int cap = Math.max(0, limit);
		for (List<Object[]> chunk : chunks) {
			if (chunk == null) {
				continue;
			}
			for (Object[] row : chunk) {
				out.add(row);
				if (cap > 0 && out.size() >= cap) {
					return out;
				}
			}
		}
		return out;
	}

	/**
	 * UNION (distinct) merge: concatenate then keep first occurrence of each wire-equal row.
	 *
	 * @param chunks ordered arm results; null chunks skipped
	 * @param limit  max rows after dedupe; {@code <= 0} = no cap
	 */
	public static List<Object[]> mergeDistinctRows(List<List<Object[]>> chunks, int limit) {
		final List<Object[]> concatenated = mergeRows(chunks, 0);
		return dedupeByWireKey(concatenated, limit);
	}

	/**
	 * Apply one set operator between two projected arms (shared by UNION / INTERSECT / EXCEPT).
	 *
	 * @param left  accumulator / left arm rows
	 * @param right right arm rows
	 * @param kind  set operator
	 * @param all   {@code true} for multiset ({@code ALL}) semantics
	 * @param limit max rows; {@code <= 0} = no cap
	 */
	public static List<Object[]> applySetOp(
			List<Object[]> left,
			List<Object[]> right,
			SetOpKind kind,
			boolean all,
			int limit
	) {
		if (kind == null) {
			throw new IllegalArgumentException("set operator kind is required");
		}
		final List<Object[]> leftRows = left == null ? List.of() : left;
		final List<Object[]> rightRows = right == null ? List.of() : right;
		return switch (kind) {
			case UNION -> all
					? mergeRows(List.of(leftRows, rightRows), limit)
					: mergeDistinctRows(List.of(leftRows, rightRows), limit);
			case INTERSECT -> all
					? intersectAll(leftRows, rightRows, limit)
					: intersectDistinct(leftRows, rightRows, limit);
			case EXCEPT -> all
					? exceptAll(leftRows, rightRows, limit)
					: exceptDistinct(leftRows, rightRows, limit);
		};
	}

	/**
	 * Full-row wire key for set-op membership (length-prefixed column encodings).
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
			total += ROW_KEY_LENGTH_PREFIX_BYTES + parts[i].length;
		}
		final byte[] out = new byte[total];
		int pos = 0;
		for (byte[] part : parts) {
			final int len = part.length;
			out[pos++] = (byte) len;
			out[pos++] = (byte) (len >>> BYTE_SHIFT_8);
			out[pos++] = (byte) (len >>> BYTE_SHIFT_16);
			out[pos++] = (byte) (len >>> BYTE_SHIFT_24);
			System.arraycopy(part, 0, out, pos, len);
			pos += len;
		}
		return new WireFieldBytes(out);
	}

	private static List<Object[]> dedupeByWireKey(List<Object[]> rows, int limit) {
		final int capacity = Math.max(HASH_MAP_MIN_CAPACITY, rows.size() * 2);
		final Set<WireFieldBytes> seen = new HashSet<>(capacity);
		final List<Object[]> out = new ArrayList<>(rows.size());
		final int cap = Math.max(0, limit);
		for (Object[] row : rows) {
			if (seen.add(wireKeyOfRow(row))) {
				out.add(row);
				if (cap > 0 && out.size() >= cap) {
					return out;
				}
			}
		}
		return out;
	}

	private static List<Object[]> intersectDistinct(List<Object[]> left, List<Object[]> right, int limit) {
		final Set<WireFieldBytes> rightKeys = wireKeySet(right);
		final int capacity = Math.max(HASH_MAP_MIN_CAPACITY, left.size() * 2);
		final Set<WireFieldBytes> emitted = new HashSet<>(capacity);
		final List<Object[]> out = new ArrayList<>();
		final int cap = Math.max(0, limit);
		for (Object[] row : left) {
			final WireFieldBytes key = wireKeyOfRow(row);
			if (rightKeys.contains(key) && emitted.add(key)) {
				out.add(row);
				if (cap > 0 && out.size() >= cap) {
					return out;
				}
			}
		}
		return out;
	}

	private static List<Object[]> intersectAll(List<Object[]> left, List<Object[]> right, int limit) {
		final Map<WireFieldBytes, Integer> rightCounts = wireKeyCounts(right);
		final List<Object[]> out = new ArrayList<>();
		final int cap = Math.max(0, limit);
		for (Object[] row : left) {
			final WireFieldBytes key = wireKeyOfRow(row);
			final Integer remaining = rightCounts.get(key);
			if (remaining != null && remaining > MULTISET_EMPTY) {
				rightCounts.put(key, remaining - MULTISET_UNIT);
				out.add(row);
				if (cap > 0 && out.size() >= cap) {
					return out;
				}
			}
		}
		return out;
	}

	private static List<Object[]> exceptDistinct(List<Object[]> left, List<Object[]> right, int limit) {
		final Set<WireFieldBytes> rightKeys = wireKeySet(right);
		final int capacity = Math.max(HASH_MAP_MIN_CAPACITY, left.size() * 2);
		final Set<WireFieldBytes> emitted = new HashSet<>(capacity);
		final List<Object[]> out = new ArrayList<>();
		final int cap = Math.max(0, limit);
		for (Object[] row : left) {
			final WireFieldBytes key = wireKeyOfRow(row);
			if (!rightKeys.contains(key) && emitted.add(key)) {
				out.add(row);
				if (cap > 0 && out.size() >= cap) {
					return out;
				}
			}
		}
		return out;
	}

	private static List<Object[]> exceptAll(List<Object[]> left, List<Object[]> right, int limit) {
		final Map<WireFieldBytes, Integer> rightCounts = wireKeyCounts(right);
		final List<Object[]> out = new ArrayList<>();
		final int cap = Math.max(0, limit);
		for (Object[] row : left) {
			final WireFieldBytes key = wireKeyOfRow(row);
			final Integer remaining = rightCounts.get(key);
			if (remaining != null && remaining > MULTISET_EMPTY) {
				rightCounts.put(key, remaining - MULTISET_UNIT);
				continue;
			}
			out.add(row);
			if (cap > 0 && out.size() >= cap) {
				return out;
			}
		}
		return out;
	}

	private static Set<WireFieldBytes> wireKeySet(List<Object[]> rows) {
		final int capacity = Math.max(HASH_MAP_MIN_CAPACITY, rows.size() * 2);
		final Set<WireFieldBytes> keys = new HashSet<>(capacity);
		for (Object[] row : rows) {
			keys.add(wireKeyOfRow(row));
		}
		return keys;
	}

	private static Map<WireFieldBytes, Integer> wireKeyCounts(List<Object[]> rows) {
		final int capacity = Math.max(HASH_MAP_MIN_CAPACITY, rows.size() * 2);
		final Map<WireFieldBytes, Integer> counts = new HashMap<>(capacity);
		for (Object[] row : rows) {
			final WireFieldBytes key = wireKeyOfRow(row);
			final Integer prev = counts.get(key);
			counts.put(key, prev == null ? MULTISET_UNIT : prev + MULTISET_UNIT);
		}
		return counts;
	}
}
