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

import org.genfork.grid.query.plan.QueryData;
import org.genfork.grid.query.plan.QueryParser;
import org.genfork.grid.query.plan.SortOrderData;
import org.genfork.grid.serial.SqlWireUtil;
import org.genfork.grid.serial.WireFieldCompare;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.ast.SelectAst.HavingPredicate;
import org.genfork.grid.sql.ast.SelectAst.SelectSql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * HAVING / ORDER BY / LIMIT helpers and wire-comparable value ordering.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlResultSortOps {
	private SqlResultSortOps() {
	}

	static List<Object[]> applyHaving(
			HavingPredicate having,
			List<SqlResult.ColumnMeta> metas,
			List<Object[]> rows
	) {
		final int idx = resolveHavingColumn(having.leftLabel(), metas);
		if (idx < 0) {
			throw new IllegalArgumentException("unsupported HAVING: " + having.leftLabel());
		}
		final HavingCmp direct = new HavingCmp(idx, having.operator(), having.rightValue());
		final List<Object[]> out = new ArrayList<>(rows.size());
		for (Object[] row : rows) {
			if (direct.matches(row)) {
				out.add(row);
			}
		}
		return out;
	}

	static int resolveHavingColumn(String left, List<SqlResult.ColumnMeta> metas) {
		for (int i = 0; i < metas.size(); i++) {
			if (metas.get(i).name().equalsIgnoreCase(left)) {
				return i;
			}
		}
		final String compact = left.replace(" ", "");
		for (int i = 0; i < metas.size(); i++) {
			if (metas.get(i).name().replace(" ", "").equalsIgnoreCase(compact)) {
				return i;
			}
		}
		return -1;
	}

	static List<Object[]> applyOrderLimit(
			SelectSql s,
			List<SqlResult.ColumnMeta> metas,
			List<Object[]> rows
	) {
		List<Object[]> out = rows;
		final QueryData qd = QueryParser.parseAndBuildCondition(
				null, s.sql(), Collections.emptyMap());
		if (qd.sortOrder() != null && qd.sortOrder().length > 0) {
			out = new ArrayList<>(out);
			out.sort(joinComparator(metas, qd.sortOrder()));
		}

		final int offset = Math.max(0, s.offset());
		if (s.limitOrNull() == null) {
			if (offset == 0) {
				return out;
			}
			if (offset >= out.size()) {
				return List.of();
			}
			return new ArrayList<>(out.subList(offset, out.size()));
		}

		final int limit = Math.max(0, s.limitOrNull());
		if (offset >= out.size() || limit == 0) {
			return List.of();
		}

		final int end = Math.min(out.size(), offset + limit);
		return new ArrayList<>(out.subList(offset, end));
	}

	static Comparator<Object[]> joinComparator(List<SqlResult.ColumnMeta> metas, SortOrderData[] orders) {
		return (a, b) -> {
			for (SortOrderData order : orders) {
				final String sortField = order.sortField();
				final SortOrderData.OrderDirection direction = order.direction();
				final int idx = metaIndex(metas, sortField);
				if (idx < 0) {
					continue;
				}
				final int cmp = compareValues(a[idx], b[idx]);
				if (cmp != 0) {
					return direction == SortOrderData.OrderDirection.DESC ? -cmp : cmp;
				}
			}
			return 0;
		};
	}

	static int compareValues(Object a, Object b) {
		if (a == b) {
			return 0;
		}
		final byte[] left = SqlWireUtil.toGenericArray(a);
		final byte[] right = SqlWireUtil.toGenericArray(b);
		if (WireFieldCompare.isNull(left) && WireFieldCompare.isNull(right)) {
			return 0;
		}
		if (WireFieldCompare.isNull(left)) {
			return -1;
		}
		if (WireFieldCompare.isNull(right)) {
			return 1;
		}
		return WireFieldCompare.compare(left, right);
	}

	static int metaIndex(List<SqlResult.ColumnMeta> metas, String name) {
		for (int i = 0; i < metas.size(); i++) {
			if (metas.get(i).name().equalsIgnoreCase(name)) {
				return i;
			}
		}
		return -1;
	}

	private record HavingCmp(int colIndex, String op, Object right) {
		boolean matches(Object[] row) {
			final Object left = row[colIndex];
			final int cmp = compareHaving(left, right);
			return switch (op) {
				case "=" -> cmp == 0;
				case "!=", "<>" -> cmp != 0;
				case ">" -> cmp > 0;
				case ">=" -> cmp >= 0;
				case "<" -> cmp < 0;
				case "<=" -> cmp <= 0;
				default -> false;
			};
		}

		@SuppressWarnings({"rawtypes", "unchecked"})
		private static int compareHaving(Object a, Object b) {
			if (a == b) {
				return 0;
			}
			if (a == null) {
				return -1;
			}
			if (b == null) {
				return 1;
			}
			if (a instanceof Number na && b instanceof Number nb) {
				return Double.compare(na.doubleValue(), nb.doubleValue());
			}
			return compareValues(a, b);
		}
	}
}
