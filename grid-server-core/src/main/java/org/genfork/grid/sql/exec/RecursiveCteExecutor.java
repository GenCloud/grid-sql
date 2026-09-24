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

import org.genfork.grid.catalog.SqlType;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.SqlSession;
import org.genfork.grid.sql.ast.SelectAst.JoinEdge;
import org.genfork.grid.sql.ast.SelectAst.RecursiveCteSql;
import org.genfork.grid.sql.ast.SelectAst.SelectSql;
import org.genfork.grid.serial.SqlWireUtil;
import org.genfork.grid.serial.WireFieldBytes;
import org.genfork.grid.sql.tx.KeyWrapper;
import org.genfork.grid.store.TableStore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Iterative WITH RECURSIVE helper: worklist + depth cap + PK-byte cycle detect.
 * <p>
 * Not a separate engine — reuses {@link SqlQueryExecutor#select} for the anchor and
 * scans the recursive base table against seed join values.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class RecursiveCteExecutor {
	/** Minimum HashSet capacity hint for recursive seed value sets. */
	private static final int SEED_SET_MIN_CAPACITY = 16;

	private RecursiveCteExecutor() {
	}

	/**
	 * Execute a recursive CTE and apply the outer SELECT projection against
	 * the accumulated CTE result set (in memory).
	 *
	 * @param maxDepth strict iteration cap ({@code <= 0} rejected by caller)
	 */
	public static SqlResult execute(
			SqlSession session,
			SqlQueryExecutor query,
			SqlTableResolver tables,
			RecursiveCteSql cte,
			int maxDepth
	) {
		Objects.requireNonNull(cte, "cte");
		if (maxDepth <= 0) {
			throw new IllegalArgumentException("recursive CTE max depth must be > 0");
		}
		final SelectSql anchor = cte.anchor();
		final SelectSql recursive = cte.recursive();
		final String cteName = cte.cteName().toLowerCase(Locale.ROOT);

		final JoinEdge link = findCteJoin(recursive, cteName);
		if (link == null) {
			throw new IllegalArgumentException(
					"RECURSIVE arm must JOIN the CTE name ON col = col (got no CTE join)");
		}

		final SqlResult seed = query.select(session, stripCteJoins(anchor, cteName));
		final List<String> cteColumns = columnLabels(seed);
		final List<Object[]> accumulated = new ArrayList<>(seed.rows());
		final Set<KeyWrapper> seenPk = new HashSet<>();
		final String baseTable = tables.resolveTable(session, recursive.table());
		final TableStore baseStore = tables.requireStore(baseTable);
		final int pkOrdinal = 0;

		List<Object[]> generation = new ArrayList<>(seed.rows());
		for (Object[] row : generation) {
			seenPk.add(pkKey(baseStore, row, pkOrdinal));
		}

		int depth = 0;
		while (!generation.isEmpty()) {
			depth++;
			if (depth > maxDepth) {
				throw new IllegalStateException(
						"RECURSIVE CTE depth exceeded maxDepth=" + maxDepth);
			}
			final Set<WireFieldBytes> seeds = seedValues(generation, cteColumns, link.rightCol());
			if (seeds.isEmpty()) {
				break;
			}
			final List<Object[]> nextGen = new ArrayList<>();
			final SelectSql baseScan = stripCteJoins(recursive, cteName);
			final SqlResult candidates = query.select(session, baseScan);
			final List<String> candCols = columnLabels(candidates);
			final int leftIdx = indexOf(candCols, link.leftCol());
			if (leftIdx < 0) {
				throw new IllegalArgumentException(
						"RECURSIVE join left column missing in projection: " + link.leftCol());
			}
			for (Object[] cand : candidates.rows()) {
				final Object leftVal = cand[leftIdx];
				if (!seeds.contains(wireSeed(leftVal))) {
					continue;
				}
				final KeyWrapper pk = pkKey(baseStore, cand, pkOrdinal);
				if (!seenPk.add(pk)) {
					continue;
				}
				nextGen.add(alignRow(cand, candCols, cteColumns));
			}
			accumulated.addAll(nextGen);
			generation = nextGen;
		}

		return projectOuter(cte, cteColumns, accumulated);
	}

	private static JoinEdge findCteJoin(SelectSql recursive, String cteNameLower) {
		if (recursive.joins() == null) {
			return null;
		}
		for (JoinEdge edge : recursive.joins()) {
			final String t = edge.table().toLowerCase(Locale.ROOT);
			final int dot = t.lastIndexOf('.');
			final String simple = dot >= 0 ? t.substring(dot + 1) : t;
			if (cteNameLower.equals(t) || cteNameLower.equals(simple)) {
				return edge;
			}
		}
		return null;
	}

	private static SelectSql stripCteJoins(SelectSql s, String cteNameLower) {
		if (s.joins() == null || s.joins().isEmpty()) {
			return s;
		}
		final List<JoinEdge> kept = new ArrayList<>();
		for (JoinEdge edge : s.joins()) {
			final String t = edge.table().toLowerCase(Locale.ROOT);
			final int dot = t.lastIndexOf('.');
			final String simple = dot >= 0 ? t.substring(dot + 1) : t;
			if (cteNameLower.equals(t) || cteNameLower.equals(simple)) {
				continue;
			}
			kept.add(edge);
		}
		if (kept.size() == s.joins().size()) {
		return s;
	}
	return SqlSelectSqlRender.withJoins(s, kept);
	}

	private static List<String> columnLabels(SqlResult result) {
		final List<String> names = new ArrayList<>(result.columns().size());
		for (SqlResult.ColumnMeta meta : result.columns()) {
			names.add(meta.name());
		}
		return names;
	}

	private static Set<WireFieldBytes> seedValues(List<Object[]> rows, List<String> cols, String rightCol) {
		final int idx = indexOf(cols, rightCol);
		if (idx < 0) {
			throw new IllegalArgumentException("RECURSIVE join right column missing: " + rightCol);
		}
		final Set<WireFieldBytes> seeds = new HashSet<>(Math.max(SEED_SET_MIN_CAPACITY, rows.size() * 2));
		for (Object[] row : rows) {
			seeds.add(wireSeed(row[idx]));
		}
		return seeds;
	}

	private static WireFieldBytes wireSeed(Object v) {
		return new WireFieldBytes(SqlWireUtil.toGenericArray(v));
	}

	private static KeyWrapper pkKey(TableStore store, Object[] row, int pkOrdinal) {
		final Object pkVal = row[pkOrdinal];
		return new KeyWrapper(store.keyBytesForPk(pkVal));
	}

	private static Object[] alignRow(Object[] cand, List<String> candCols, List<String> cteColumns) {
		final Object[] out = new Object[cteColumns.size()];
		for (int i = 0; i < cteColumns.size(); i++) {
			final int src = indexOf(candCols, cteColumns.get(i));
			out[i] = src >= 0 ? cand[src] : null;
		}
		return out;
	}

	private static int indexOf(List<String> cols, String name) {
		for (int i = 0; i < cols.size(); i++) {
			if (cols.get(i).equalsIgnoreCase(name)) {
				return i;
			}
		}
		return -1;
	}

	private static SqlResult projectOuter(RecursiveCteSql cte, List<String> cteColumns, List<Object[]> rows) {
		final SelectSql outer = cte.outer();
		final List<String> projection = outer.projection();
		final boolean star = projection.size() == 1 && "*".equals(projection.getFirst());
		final List<SqlResult.ColumnMeta> metas;
		final List<Object[]> out = new ArrayList<>(rows.size());
		if (star) {
			metas = metasFor(cteColumns, rows);
			out.addAll(rows);
		} else {
			metas = new ArrayList<>(projection.size());
			final int[] idxs = new int[projection.size()];
			for (int i = 0; i < projection.size(); i++) {
				idxs[i] = indexOf(cteColumns, projection.get(i));
				if (idxs[i] < 0) {
					throw new IllegalArgumentException("unknown CTE column: " + projection.get(i));
				}
				metas.add(SqlResult.ColumnMeta.of(projection.get(i), SqlType.VARCHAR));
			}
			if (!rows.isEmpty()) {
				for (int i = 0; i < metas.size(); i++) {
					final Object sample = rows.getFirst()[idxs[i]];
					metas.set(i, SqlResult.ColumnMeta.of(projection.get(i), typeOf(sample)));
				}
			}
			for (Object[] row : rows) {
				final Object[] projected = new Object[idxs.length];
				for (int i = 0; i < idxs.length; i++) {
					projected[i] = row[idxs[i]];
				}
				out.add(projected);
			}
		}
		List<Object[]> limited = out;
		if (outer.offset() > 0 || outer.limitOrNull() != null) {
			final int from = Math.min(outer.offset(), limited.size());
			final int to = outer.limitOrNull() == null
					? limited.size()
					: Math.min(limited.size(), from + Math.max(0, outer.limitOrNull()));
			limited = new ArrayList<>(limited.subList(from, to));
		}
		return SqlResult.resultSet(metas, limited);
	}

	private static List<SqlResult.ColumnMeta> metasFor(List<String> cols, List<Object[]> rows) {
		final List<SqlResult.ColumnMeta> metas = new ArrayList<>(cols.size());
		for (int i = 0; i < cols.size(); i++) {
			SqlType type = SqlType.VARCHAR;
			if (!rows.isEmpty() && rows.getFirst().length > i) {
				type = typeOf(rows.getFirst()[i]);
			}
			metas.add(SqlResult.ColumnMeta.of(cols.get(i), type));
		}
		return metas;
	}

	private static SqlType typeOf(Object v) {
		if (v instanceof Integer) {
			return SqlType.INT;
		}
		if (v instanceof Long) {
			return SqlType.BIGINT;
		}
		if (v instanceof Double || v instanceof Float) {
			return SqlType.DOUBLE;
		}
		if (v instanceof Boolean) {
			return SqlType.BOOLEAN;
		}
		if (v instanceof byte[]) {
			return SqlType.BYTES;
		}
		return SqlType.VARCHAR;
	}
}