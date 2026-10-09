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
import org.genfork.grid.catalog.TableCatalog.ViewDef;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.ast.SelectAst.CteBinding;
import org.genfork.grid.sql.ast.SelectAst.SelectSql;
import org.genfork.grid.sql.ast.SelectAst.WithSelectSql;
import org.genfork.grid.sql.udf.SqlUdfCallContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Evaluate plain VIEW / deferred non-recursive WITH bodies, then apply outer residual
 * WHERE / projection / ORDER / LIMIT (post-window semantics — no text pushdown past RANK).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.1
 */
public final class SqlNamedSourceEvaluateUtil {
	private static final String STAR = "*";
	private static final String ERR_OUTER_JOIN =
			"JOIN on CTE/VIEW that already has JOIN is not supported";
	private static final String ERR_OUTER_AGG =
			"cannot further aggregate CTE/VIEW with join or agg body";
	private static final String ERR_MULTI_CTE =
			"deferred WITH with multiple CTEs requires a single residual FROM cte";
	private static final String ERR_CTE_FROM =
			"deferred WITH outer FROM must reference a CTE name";

	private SqlNamedSourceEvaluateUtil() {
	}

	/**
	 * Run catalog plain-view body then residual outer clauses from {@code outer}.
	 */
	public static SqlResult selectFromPlainView(ViewDef view, SelectSql outer) {
		rejectUnsupportedOuter(outer);
		final SqlResult body = SqlUdfCallContext.require().execute(view.selectSql());
		return applyOuterResidual(outer, body);
	}

	/**
	 * Execute deferred non-recursive WITH: materialize CTE body rows, then residual outer.
	 */
	public static SqlResult selectWith(WithSelectSql with) {
		rejectUnsupportedOuter(with.outer());
		if (with.ctes().size() != 1) {
			throw new IllegalArgumentException(ERR_MULTI_CTE);
		}
		final CteBinding cte = with.ctes().getFirst();
		final String from = with.outer().table();
		if (from == null || !cte.name().equalsIgnoreCase(simpleName(from))) {
			throw new IllegalArgumentException(ERR_CTE_FROM);
		}
		final SqlResult body = SqlUdfCallContext.require().execute(cte.bodySql());
		return applyOuterResidual(with.outer(), body);
	}

	private static void rejectUnsupportedOuter(SelectSql outer) {
		if (outer.hasJoins()) {
			throw new IllegalArgumentException(ERR_OUTER_JOIN);
		}
		if (outer.aggregate() || outer.hasGroupBy() || outer.havingOrNull() != null || outer.hasWindow()) {
			throw new IllegalArgumentException(ERR_OUTER_AGG);
		}
	}

	private static SqlResult applyOuterResidual(SelectSql outer, SqlResult body) {
		List<Object[]> rows = SqlResultResidualUtil.applyWhere(outer, body.columns(), body.rows());
		rows = project(outer, body.columns(), rows);
		final List<SqlResult.ColumnMeta> metas = projectedMetas(outer, body.columns(), rows);
		rows = SqlProjectionOps.applyDistinct(outer, rows);
		rows = SqlResultSortOps.applyOrderLimit(outer, metas, rows);
		return SqlResult.resultSet(metas, rows);
	}

	private static List<Object[]> project(
			SelectSql outer,
			List<SqlResult.ColumnMeta> bodyMetas,
			List<Object[]> rows
	) {
		final List<String> projection = outer.projection();
		if (projection == null || projection.isEmpty()
				|| (projection.size() == 1 && STAR.equals(projection.getFirst()))) {
			return rows;
		}
		final int[] idxs = new int[projection.size()];
		for (int i = 0; i < projection.size(); i++) {
			idxs[i] = indexOfMeta(bodyMetas, projection.get(i));
			if (idxs[i] < 0) {
				throw new IllegalArgumentException(
						"unknown column in view/CTE residual: " + projection.get(i));
			}
		}
		final List<Object[]> out = new ArrayList<>(rows.size());
		for (Object[] row : rows) {
			final Object[] projected = new Object[idxs.length];
			for (int i = 0; i < idxs.length; i++) {
				projected[i] = row[idxs[i]];
			}
			out.add(projected);
		}
		return out;
	}

	private static List<SqlResult.ColumnMeta> projectedMetas(
			SelectSql outer,
			List<SqlResult.ColumnMeta> bodyMetas,
			List<Object[]> rows
	) {
		final List<String> projection = outer.projection();
		if (projection == null || projection.isEmpty()
				|| (projection.size() == 1 && STAR.equals(projection.getFirst()))) {
			return bodyMetas;
		}
		final List<SqlResult.ColumnMeta> metas = new ArrayList<>(projection.size());
		for (int i = 0; i < projection.size(); i++) {
			final int idx = indexOfMeta(bodyMetas, projection.get(i));
			SqlType type = bodyMetas.get(idx).type();
			if (!rows.isEmpty() && rows.getFirst()[i] != null) {
				type = typeOf(rows.getFirst()[i], type);
			}
			metas.add(SqlResult.ColumnMeta.of(projection.get(i), type));
		}
		return metas;
	}

	private static int indexOfMeta(List<SqlResult.ColumnMeta> metas, String name) {
		for (int i = 0; i < metas.size(); i++) {
			if (metas.get(i).name().equalsIgnoreCase(name)) {
				return i;
			}
		}
		return -1;
	}

	private static SqlType typeOf(Object sample, SqlType fallback) {
		if (sample instanceof Long) {
			return SqlType.BIGINT;
		}
		if (sample instanceof Integer) {
			return SqlType.INT;
		}
		if (sample instanceof Double || sample instanceof Float) {
			return SqlType.DOUBLE;
		}
		if (sample instanceof Boolean) {
			return SqlType.BOOLEAN;
		}
		if (sample instanceof String) {
			return SqlType.VARCHAR;
		}
		return fallback;
	}

	private static String simpleName(String table) {
		final int dot = table.lastIndexOf('.');
		final String raw = dot >= 0 ? table.substring(dot + 1) : table;
		return raw.toLowerCase(Locale.ROOT);
	}
}
