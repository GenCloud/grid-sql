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

import org.genfork.grid.query.filters.FilterCondition;
import org.genfork.grid.query.filters.impl.AlwaysTrueCondition;
import org.genfork.grid.query.plan.QueryData;
import org.genfork.grid.query.plan.QueryParser;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.ast.SelectAst.SelectSql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Residual WHERE over {@link SqlResult} row sets (result-edge Object[]).
 * <p>
 * Shared by plain SELECT residual and deferred VIEW/CTE evaluate — ANTLR via {@link QueryParser}.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.1
 */
public final class SqlResultResidualUtil {
	private SqlResultResidualUtil() {
	}

	/**
	 * Build residual filter tree from SELECT text (ANTLR). Optional resolved subquery filters.
	 */
	public static FilterCondition selectFilter(String selectSql, List<FilterCondition> resolvedSubqueryFilters) {
		final List<FilterCondition> resolved = resolvedSubqueryFilters == null
				? Collections.emptyList()
				: resolvedSubqueryFilters;
		final QueryData qd = QueryParser.parseAndBuildCondition(
				null, selectSql, Collections.emptyMap(), resolved);
		if (qd.filter() == null || qd.filter().conditionTree() == null) {
			return AlwaysTrueCondition.getInstance();
		}
		return qd.filter().conditionTree();
	}

	/**
	 * Apply outer WHERE to decoded result rows; identity when filter is always-true.
	 */
	public static List<Object[]> applyWhere(
			SelectSql outer,
			List<SqlResult.ColumnMeta> metas,
			List<Object[]> rows
	) {
		return applyWhere(outer, metas, rows, Collections.emptyList());
	}

	/**
	 * Apply outer WHERE with optional resolved subquery filters (TLS path in executor).
	 */
	public static List<Object[]> applyWhere(
			SelectSql outer,
			List<SqlResult.ColumnMeta> metas,
			List<Object[]> rows,
			List<FilterCondition> resolvedSubqueryFilters
	) {
		final FilterCondition filter = selectFilter(outer.sql(), resolvedSubqueryFilters);
		if (filter instanceof AlwaysTrueCondition) {
			return rows;
		}
		final List<Object[]> out = new ArrayList<>(rows.size());
		for (Object[] row : rows) {
			if (filter.matchesColumns(name -> SqlProjectionOps.columnValue(metas, row, name))) {
				out.add(row);
			}
		}
		return out;
	}
}
