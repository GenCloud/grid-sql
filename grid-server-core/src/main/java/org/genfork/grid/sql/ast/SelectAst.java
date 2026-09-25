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
package org.genfork.grid.sql.ast;

import java.util.List;

import org.genfork.grid.query.util.SetOpKind;

/**
 * SELECT / set-op / CTE / EXPLAIN AST types.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SelectAst {
	private SelectAst() {
	}

	/**
	 * WHERE scalar {@code = (SELECT...)}, {@code IN (SELECT...)}, or {@code EXISTS (SELECT...)}
	 * resolved before residual filter.
	 *
	 * @param column       outer column; {@code null} for EXISTS / NOT EXISTS
	 * @param inList       {@code true} for {@code IN}, {@code false} for scalar compare
	 * @param existsProbe  {@code true} for {@code EXISTS} (semi-join existence probe)
	 * @param operator     compare op ({@code =} for scalar); {@code EXISTS} for exists probe
	 * @param subquery     nested select plan
	 * @param subquerySql  nested select text (exact rewrite key)
	 *
	 * @author: GenCloud
	 * @date: 2025/11
	 * @since: 1.0
	 */
	public record WhereSubquery(
			String column,
			boolean inList,
			boolean existsProbe,
			String operator,
			SelectSql subquery,
			String subquerySql
	) {
		public WhereSubquery(
				String column,
				boolean inList,
				String operator,
				SelectSql subquery,
				String subquerySql
		) {
			this(column, inList, false, operator, subquery, subquerySql);
		}
	}

	/**
	 * {@code SELECT … {UNION|INTERSECT|EXCEPT} [ALL] SELECT …} chain.
	 *
	 * @param arms       left-to-right select arms (size &gt;= 2)
	 * @param opsBetween set operator before arms[i+1]; size = arms.size()-1
	 * @param allBetween {@code true} when {@code ALL} before arms[i+1]; size = arms.size()-1
	 *
	 * @author: GenCloud
	 * @date: 2025/11
	 * @since: 1.0
	 */
	public record SetOpSql(
			String sql,
			List<SelectSql> arms,
			List<SetOpKind> opsBetween,
			List<Boolean> allBetween
	) implements Stmt {
	}

	/** JOIN kind for one edge in a SELECT chain. */
	public enum JoinKind {
		INNER,
		LEFT,
		RIGHT,
		FULL
	}

	/** One equality in a JOIN ON clause. */
	public record JoinEq(String leftCol, String rightCol) {
	}

	/**
	 * One JOIN edge in a SELECT (INNER / LEFT / RIGHT / FULL OUTER).
	 * <p>
	 * {@code eqs} is never empty; single-equality joins use a one-element list.
	 *
	 * @param table      right-side table (resolved name)
	 * @param tableAlias optional join-side alias ({@code null} when absent)
	 * @param eqs        ON equalities left-to-right
	 * @param kind       join kind
	 */
	public record JoinEdge(String table, String tableAlias, List<JoinEq> eqs, JoinKind kind) {
		public JoinEdge {
			if (eqs == null || eqs.isEmpty()) {
				throw new IllegalArgumentException("JoinEdge requires at least one equality");
			}
			eqs = List.copyOf(eqs);
		}

		/** Convenience for single-eq call sites / render. */
		public JoinEdge(String table, String leftCol, String rightCol, JoinKind kind) {
			this(table, null, List.of(new JoinEq(leftCol, rightCol)), kind);
		}

		public String leftCol() {
			return eqs.getFirst().leftCol();
		}

		public String rightCol() {
			return eqs.getFirst().rightCol();
		}

		public boolean multiEq() {
			return eqs.size() > 1;
		}
	}

	/**
	 * One SELECT-list item (column, aggregate, or window).
	 *
	 * @author: GenCloud
	 * @date: 2025/11
	 * @since: 1.0
	 */
	public sealed interface SelectItem permits ColumnSelectItem, AggregateSelectItem, WindowSelectItem, FunctionSelectItem {
		String label();
	}

	/** One UDF argument: column reference or literal. */
	public sealed interface FuncArg permits ColumnFuncArg, LiteralFuncArg {
	}

	/**
	 * Column argument to a UDF call.
	 *
	 * @author: GenCloud
	 * @date: 2025/11
	 * @since: 1.0
	 */
	public record ColumnFuncArg(String column) implements FuncArg {
	}

	/**
	 * Literal argument to a UDF call.
	 *
	 * @author: GenCloud
	 * @date: 2025/11
	 * @since: 1.0
	 */
	public record LiteralFuncArg(Object value) implements FuncArg {
	}

	/** Scalar UDF call in the select list. */
	public record FunctionSelectItem(String label, String functionName, List<FuncArg> args) implements SelectItem {
	}

	/** Plain column projection (optional table qualifier / AS alias). */
	public record ColumnSelectItem(String column, String tableOrNull, String aliasOrNull) implements SelectItem {
		public ColumnSelectItem(String column) {
			this(column, null, null);
		}

		@Override
		public String label() {
			return aliasOrNull != null && !aliasOrNull.isBlank() ? aliasOrNull : column;
		}
	}

	/**
	 * {@code FROM fn(...)} table-valued function source.
	 *
	 * @author: GenCloud
	 * @date: 2025/11
	 * @since: 1.0
	 */
	public record FunctionFrom(String functionName, List<FuncArg> args, String aliasOrNull) {
	}

	/** Aggregate in the select list ({@code COUNT(*)} / {@code SUM}/{@code AVG}/{@code MIN}/{@code MAX}). */
	public record AggregateSelectItem(
			String label,
			boolean countStar,
			String sumColumnOrNull,
			boolean avg,
			boolean minAgg,
			boolean maxAgg
	) implements SelectItem {
	}

	/** Window function in the select list. */
	public record WindowSelectItem(
			String label,
			String func,
			String valueColumnOrNull,
			/** PARTITION BY columns (empty = none); composite wire key in window ops. */
			List<String> partitionColumns,
			String orderColOrNull
	) implements SelectItem {
		public WindowSelectItem(
				String label,
				String func,
				String valueColumnOrNull,
				String partitionOrNull,
				String orderColOrNull
		) {
			this(
					label,
					func,
					valueColumnOrNull,
					partitionOrNull == null || partitionOrNull.isBlank()
							? List.of()
							: List.of(partitionOrNull),
					orderColOrNull
			);
		}

		/** First PARTITION BY column, or null. */
		public String partitionOrNull() {
			return partitionColumns == null || partitionColumns.isEmpty()
					? null
					: partitionColumns.getFirst();
		}
	}

	/**
	 * Structured HAVING predicate from ANTLR {@code AggComparison} or column {@code Comparison}.
	 *
	 * @param leftLabel aggregate label ({@code SUM(x)}) or column name
	 * @param operator  {@code =}, {@code !=}, {@code >}, {@code >=}, {@code <}, {@code <=}
	 * @param rightValue literal RHS
	 *
	 * @author: GenCloud
	 * @date: 2025/11
	 * @since: 1.0
	 */
	public record HavingPredicate(String leftLabel, String operator, Object rightValue) {
	}

	/**
	 * Parsed SELECT query plan.
	 *
	 * @author: GenCloud
	 * @date: 2025/11
	 * @since: 1.0
	 */
	public record SelectSql(
			String sql,
			String table,
			List<String> projection,
			/** Parsed select-list items; empty when projection is {@code *}. */
			List<SelectItem> selectItems,
			String pkColumnOrNull,
			Object pkValueOrNull,
			List<JoinEdge> joins,
			boolean aggregate,
			boolean countStar,
			String sumColumnOrNull,
			boolean avg,
			/** GROUP BY columns (empty = none); composite wire key in executor. */
			List<String> groupByColumns,
			int offset,
			/** Null when no explicit {@code LIMIT} clause. */
			Integer limitOrNull,
			boolean distinct,
			/** ANTLR-parsed HAVING comparison ({@code AggComparison} / column compare), or null. */
			HavingPredicate havingOrNull,
			boolean minAgg,
			boolean maxAgg,
			String windowFuncOrNull,
			/** PARTITION BY columns for legacy single-window field (empty = none). */
			List<String> windowPartitionColumns,
			String windowOrderColOrNull,
			/** WHERE scalar / IN / EXISTS subqueries to resolve before residual filter. */
			List<WhereSubquery> whereSubqueries,
			/** Non-null when {@code FROM} is a table-valued function call. */
			FunctionFrom fromFunctionOrNull,
			/** Row locks selected by this statement are held through transaction end. */
			boolean forUpdate,
			/** Locked rows are omitted instead of waiting. */
			boolean skipLocked
	) implements Stmt {
		/** First GROUP BY column, or null when ungrouped. */
		public String groupByColumnOrNull() {
			return groupByColumns == null || groupByColumns.isEmpty()
					? null
					: groupByColumns.getFirst();
		}

		public boolean hasGroupBy() {
			return groupByColumns != null && !groupByColumns.isEmpty();
		}

		/** First window PARTITION BY column, or null. */
		public String windowPartitionOrNull() {
			return windowPartitionColumns == null || windowPartitionColumns.isEmpty()
					? null
					: windowPartitionColumns.getFirst();
		}

		public boolean hasWhereSubqueries() {
			return whereSubqueries != null && !whereSubqueries.isEmpty();
		}

		public boolean hasFromFunction() {
			return fromFunctionOrNull != null;
		}

		/**
		 * Bare {@code SELECT expr…} with no FROM table / TVF (mutating UDF pattern).
		 */
		public boolean isExprOnly() {
			return (table == null || table.isBlank()) && fromFunctionOrNull == null;
		}

		public boolean hasJoins() {
			return joins != null && !joins.isEmpty();
		}

		public boolean hasWindow() {
			if (windowFuncOrNull != null && !windowFuncOrNull.isBlank()) {
				return true;
			}
			if (selectItems == null) {
				return false;
			}
			for (SelectItem item : selectItems) {
				if (item instanceof WindowSelectItem) {
					return true;
				}
			}
			return false;
		}

		/** True when select list mixes columns with window and/or aggregate items. */
		public boolean mixedSelectList() {
			if (selectItems == null || selectItems.size() < 2) {
				return false;
			}
			boolean col = false;
			boolean computed = false;
			for (SelectItem item : selectItems) {
				if (item instanceof ColumnSelectItem) {
					col = true;
				} else {
					computed = true;
				}
			}
			return col && computed;
		}
	}

	/**
	 * {@code EXPLAIN [ANALYZE] query}.
	 *
	 * @author: GenCloud
	 * @date: 2025/11
	 * @since: 1.0
	 */
	public record ExplainSql(Stmt query, boolean analyze) implements Stmt {
		public ExplainSql(Stmt query) {
			this(query, false);
		}
	}

	/**
	 * {@code WITH RECURSIVE cte AS (anchor UNION [ALL] recursive) outer}.
	 *
	 * @param cteName    CTE binding name
	 * @param anchor     non-recursive seed SELECT
	 * @param recursive  recursive arm (must JOIN the CTE)
	 * @param unionAll   {@code true} when {@code UNION ALL}
	 * @param outer      final SELECT from the CTE
	 *
	 * @author: GenCloud
	 * @date: 2025/11
	 * @since: 1.0
	 */
	public record RecursiveCteSql(
			String cteName,
			SelectSql anchor,
			SelectSql recursive,
			boolean unionAll,
			SelectSql outer,
			String sql
	) implements Stmt {
	}
}