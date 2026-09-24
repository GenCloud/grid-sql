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

import org.genfork.grid.catalog.ColumnDef;
import org.genfork.grid.serial.WireFieldBytes;
import org.genfork.grid.store.TableStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Applies one window function over partitioned / ordered source rows.
 * <p>
 * Supports {@code ROW_NUMBER}, {@code RANK}, {@code DENSE_RANK}, {@code LAG}/{@code LEAD}
 * (offset 1), and partition aggregates {@code SUM}/{@code MIN}/{@code MAX}/{@code AVG}
 * (frame = all rows in partition, equivalent to ROWS UNBOUNDED PRECEDING … CURRENT for ranking;
 * partition aggs use the full partition).
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class WindowOperator {
	private WindowOperator() {
	}

	/**
	 * Window function specification.
	 *
	 * @param func                   function name (upper-case preferred)
	 * @param partitionColumns       optional PARTITION BY columns (composite wire key)
	 * @param orderColOrNull         optional ORDER BY column (first key)
	 * @param valueColOrNull         value column for LAG/LEAD/partition aggs
	 */
	public record Spec(
			String func,
			List<String> partitionColumns,
			String orderColOrNull,
			String valueColOrNull
	) {
		public Spec(String func, String partitionColOrNull, String orderColOrNull, String valueColOrNull) {
			this(
					func,
					partitionColOrNull == null || partitionColOrNull.isBlank()
							? List.of()
							: List.of(partitionColOrNull),
					orderColOrNull,
					valueColOrNull
			);
		}
	}

	/**
	 * Appends the window result as the last column of each output row.
	 */
	public static List<Object[]> apply(TableStore store, List<Object[]> source, Spec spec) {
		return apply(store.schema().columns(), source, spec);
	}

	/**
	 * Same as {@link #apply(TableStore, List, Spec)} using an explicit column list
	 * (joined working set / synthetic schemas).
	 */
	public static List<Object[]> apply(List<ColumnDef> columns, List<Object[]> source, Spec spec) {
		final String func = spec.func() == null ? "" : spec.func().toUpperCase(Locale.ROOT);
		final int[] partOrds = ordinalsOf(columns, spec.partitionColumns());
		final int orderOrd = ordinalOf(columns, spec.orderColOrNull());
		final int valueOrd = ordinalOf(columns, spec.valueColOrNull());

		final Map<WireFieldBytes, List<Object[]>> partitions = new LinkedHashMap<>();
		for (Object[] row : source) {
			final WireFieldBytes key = partOrds.length == 0
					? SqlWireAggOps.partitionWireKey(Boolean.TRUE)
					: SqlWireAggOps.compositeWireKey(row, partOrds);
			partitions.computeIfAbsent(key, _ -> new ArrayList<>(4)).add(row);
		}

		final List<Object[]> out = new ArrayList<>(source.size());
		for (List<Object[]> part : partitions.values()) {
			final List<Object[]> sorted = new ArrayList<>(part);
			if (orderOrd >= 0) {
				sorted.sort((a, b) -> compareValues(a[orderOrd], b[orderOrd]));
			}
			switch (func) {
				case "ROW_NUMBER" -> applyRowNumber(sorted, out);
				case "RANK" -> applyRank(sorted, orderOrd, out, false);
				case "DENSE_RANK" -> applyRank(sorted, orderOrd, out, true);
				case "LAG" -> applyLagLead(sorted, valueOrd, out, true);
				case "LEAD" -> applyLagLead(sorted, valueOrd, out, false);
				case "SUM", "MIN", "MAX", "AVG" -> applyPartitionAgg(sorted, valueOrd, func, out);
				default -> throw new IllegalArgumentException("unsupported window function: " + func);
			}
		}
		return out;
	}

	private static int[] ordinalsOf(List<ColumnDef> columns, List<String> names) {
		if (names == null || names.isEmpty()) {
			return new int[0];
		}
		final int[] ords = new int[names.size()];
		for (int i = 0; i < names.size(); i++) {
			ords[i] = ordinalOf(columns, names.get(i));
			if (ords[i] < 0) {
				throw new IllegalArgumentException("unknown column: " + names.get(i));
			}
		}
		return ords;
	}

	private static int ordinalOf(List<ColumnDef> columns, String nameOrNull) {
		if (nameOrNull == null || nameOrNull.isBlank()) {
			return -1;
		}
		for (int i = 0; i < columns.size(); i++) {
			if (columns.get(i).name().equalsIgnoreCase(nameOrNull)) {
				return i;
			}
		}
		throw new IllegalArgumentException("unknown column: " + nameOrNull);
	}

	private static void applyRowNumber(List<Object[]> sorted, List<Object[]> out) {
		int rowNum = 0;
		for (Object[] row : sorted) {
			rowNum++;
			out.add(append(row, (double) rowNum));
		}
	}

	private static void applyRank(List<Object[]> sorted, int orderOrd, List<Object[]> out, boolean dense) {
		Object prevOrder = null;
		int rank = 0;
		int rowNum = 0;
		int denseRank = 0;
		for (Object[] row : sorted) {
			rowNum++;
			final Object orderVal = orderOrd >= 0 ? row[orderOrd] : null;
			final boolean newGroup = rank == 0 || compareValues(prevOrder, orderVal) != 0;
			if (newGroup) {
				if (dense) {
					denseRank++;
					rank = denseRank;
				} else {
					rank = rowNum;
				}
				prevOrder = orderVal;
			}
			out.add(append(row, (double) rank));
		}
	}

	private static void applyLagLead(List<Object[]> sorted, int valueOrd, List<Object[]> out, boolean lag) {
		for (int i = 0; i < sorted.size(); i++) {
			final Object[] row = sorted.get(i);
			final int peer = lag ? i - 1 : i + 1;
			final Object value;
			if (peer < 0 || peer >= sorted.size() || valueOrd < 0) {
				value = null;
			} else {
				value = sorted.get(peer)[valueOrd];
			}
			out.add(append(row, value));
		}
	}

	private static void applyPartitionAgg(
			List<Object[]> sorted,
			int valueOrd,
			String func,
			List<Object[]> out
	) {
		double sum = 0d;
		long n = 0L;
		Double min = null;
		Double max = null;
		for (Object[] row : sorted) {
			final Object raw = valueOrd < 0 ? null : row[valueOrd];
			if (raw == null) {
				continue;
			}
			final double v = raw instanceof Number num
					? num.doubleValue()
					: Double.parseDouble(String.valueOf(raw));
			sum += v;
			n++;
			min = min == null ? v : Math.min(min, v);
			max = max == null ? v : Math.max(max, v);
		}
		final double partAgg = switch (func) {
			case "AVG" -> n == 0L ? 0d : sum / n;
			case "MIN" -> min == null ? 0d : min;
			case "MAX" -> max == null ? 0d : max;
			default -> sum;
		};
		for (Object[] row : sorted) {
			out.add(append(row, partAgg));
		}
	}

	private static Object[] append(Object[] row, Object value) {
		final Object[] with = new Object[row.length + 1];
		System.arraycopy(row, 0, with, 0, row.length);
		with[row.length] = value;
		return with;
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static int compareValues(Object a, Object b) {
		if (a == b) {
			return 0;
		}
		if (a == null) {
			return -1;
		}
		if (b == null) {
			return 1;
		}
		if (a instanceof Comparable ca && b instanceof Comparable) {
			return ca.compareTo(b);
		}
		return String.valueOf(a).compareTo(String.valueOf(b));
	}
}
