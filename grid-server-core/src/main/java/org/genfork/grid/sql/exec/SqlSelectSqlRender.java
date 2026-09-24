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

import org.genfork.grid.sql.ast.SelectAst.AggregateSelectItem;
import org.genfork.grid.sql.ast.SelectAst.ColumnSelectItem;
import org.genfork.grid.sql.ast.SelectAst.FuncArg;
import org.genfork.grid.sql.ast.SelectAst.FunctionFrom;
import org.genfork.grid.sql.ast.SelectAst.FunctionSelectItem;
import org.genfork.grid.sql.ast.SelectAst.LiteralFuncArg;
import org.genfork.grid.sql.ast.SelectAst.ColumnFuncArg;
import org.genfork.grid.sql.ast.SelectAst.JoinEdge;
import org.genfork.grid.sql.ast.SelectAst.JoinKind;
import org.genfork.grid.sql.ast.SelectAst.SelectItem;
import org.genfork.grid.sql.ast.SelectAst.SelectSql;
import org.genfork.grid.sql.ast.SelectAst.WindowSelectItem;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.misc.Interval;

import org.genfork.grid.antlr.SimplifiedSqlLexer;
import org.genfork.grid.antlr.SimplifiedSqlParser;

import java.util.List;

/**
 * Rebuild SELECT text from typed {@link SelectSql} fields (no keyword {@code indexOf} scans).
 * <p>
 * WHERE / ORDER BY clauses are taken from an ANTLR re-parse of {@link SelectSql#sql()} when present,
 * so callers can change projection / joins without string surgery on JOIN / ON / SELECT / FROM.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlSelectSqlRender {
	private SqlSelectSqlRender() {
	}

	/**
	 * Render a SELECT statement from typed fields; preserves WHERE / GROUP / HAVING / ORDER / LIMIT
	 * text from the original SQL via ANTLR when {@code s.sql()} is non-blank.
	 */
	public static String render(SelectSql s) {
		return render(s, true);
	}

	/** Render the same SELECT without its locking suffix for local key resolution. */
	public static String renderWithoutForUpdate(SelectSql s) {
		return render(s, false, true);
	}

	/**
	 * Key-lookup SQL for {@code FOR UPDATE}: keep WHERE / ORDER, drop locking and LIMIT/OFFSET
	 * (paging is applied while acquiring locks so residual filters are not truncated early).
	 */
	public static String renderForUpdateKeyLookup(SelectSql s) {
		return render(s, false, false);
	}

	private static String render(SelectSql s, boolean includeForUpdate) {
		return render(s, includeForUpdate, true);
	}

	private static String render(SelectSql s, boolean includeForUpdate, boolean includePaging) {
		final StringBuilder sb = new StringBuilder(96);
		sb.append("SELECT ");
		if (s.distinct()) {
			sb.append("DISTINCT ");
		}
		appendProjection(sb, s);
		sb.append(" FROM ");
		appendFrom(sb, s);
		if (s.joins() != null) {
			for (JoinEdge edge : s.joins()) {
				sb.append(' ').append(joinKeyword(edge.kind())).append(" JOIN ");
				sb.append(edge.table());
				sb.append(" ON ").append(edge.leftCol()).append(" = ").append(edge.rightCol());
			}
		}
		appendTailFromOriginal(sb, s.sql(), includeForUpdate, includePaging);
		return sb.toString();
	}

	/**
	 * Copy {@code s} with projection {@code *} (empty select-items) and rendered SQL.
	 */
	public static SelectSql withStarProjection(SelectSql s) {
		final SelectSql star = new SelectSql(
				s.sql(),
				s.table(),
				List.of("*"),
				List.of(),
				s.pkColumnOrNull(),
				s.pkValueOrNull(),
				s.joins(),
				false,
				false,
				null,
				false,
				List.of(),
				s.offset(),
				s.limitOrNull(),
				false,
				null,
				false,
				false,
				null,
				List.of(),
				null,
				s.whereSubqueries(),
				s.fromFunctionOrNull(),
				s.forUpdate(),
				s.skipLocked()
		);
		return new SelectSql(
				render(star),
				star.table(),
				star.projection(),
				star.selectItems(),
				star.pkColumnOrNull(),
				star.pkValueOrNull(),
				star.joins(),
				star.aggregate(),
				star.countStar(),
				star.sumColumnOrNull(),
				star.avg(),
				star.groupByColumns(),
				star.offset(),
				star.limitOrNull(),
				star.distinct(),
				star.havingOrNull(),
				star.minAgg(),
				star.maxAgg(),
				star.windowFuncOrNull(),
				star.windowPartitionColumns(),
				star.windowOrderColOrNull(),
				star.whereSubqueries(),
				star.fromFunctionOrNull(),
				star.forUpdate(),
				star.skipLocked()
		);
	}

	/**
	 * Copy {@code s} keeping only {@code kept} joins; SQL rebuilt from typed fields.
	 */
	public static SelectSql withJoins(SelectSql s, List<JoinEdge> kept) {
		final SelectSql copy = new SelectSql(
				s.sql(),
				s.table(),
				s.projection(),
				s.selectItems(),
				s.pkColumnOrNull(),
				s.pkValueOrNull(),
				List.copyOf(kept),
				s.aggregate(),
				s.countStar(),
				s.sumColumnOrNull(),
				s.avg(),
				s.groupByColumns(),
				s.offset(),
				s.limitOrNull(),
				s.distinct(),
				s.havingOrNull(),
				s.minAgg(),
				s.maxAgg(),
				s.windowFuncOrNull(),
				s.windowPartitionColumns(),
				s.windowOrderColOrNull(),
				s.whereSubqueries(),
				s.fromFunctionOrNull(),
				s.forUpdate(),
				s.skipLocked()
		);
		return new SelectSql(
				render(copy),
				copy.table(),
				copy.projection(),
				copy.selectItems(),
				copy.pkColumnOrNull(),
				copy.pkValueOrNull(),
				copy.joins(),
				copy.aggregate(),
				copy.countStar(),
				copy.sumColumnOrNull(),
				copy.avg(),
				copy.groupByColumns(),
				copy.offset(),
				copy.limitOrNull(),
				copy.distinct(),
				copy.havingOrNull(),
				copy.minAgg(),
				copy.maxAgg(),
				copy.windowFuncOrNull(),
				copy.windowPartitionColumns(),
				copy.windowOrderColOrNull(),
				copy.whereSubqueries(),
				copy.fromFunctionOrNull(),
				copy.forUpdate(),
				copy.skipLocked()
		);
	}

	/**
	 * Copy with forced OFFSET/LIMIT (EXISTS early-stop probe).
	 */
	public static SelectSql withOffsetLimit(SelectSql s, int offset, Integer limitOrNull) {
		final SelectSql copy = new SelectSql(
				s.sql(),
				s.table(),
				s.projection(),
				s.selectItems(),
				s.pkColumnOrNull(),
				s.pkValueOrNull(),
				s.joins(),
				s.aggregate(),
				s.countStar(),
				s.sumColumnOrNull(),
				s.avg(),
				s.groupByColumns(),
				offset,
				limitOrNull,
				s.distinct(),
				s.havingOrNull(),
				s.minAgg(),
				s.maxAgg(),
				s.windowFuncOrNull(),
				s.windowPartitionColumns(),
				s.windowOrderColOrNull(),
				s.whereSubqueries(),
				s.fromFunctionOrNull(),
				s.forUpdate(),
				s.skipLocked()
		);
		return new SelectSql(
				render(copy),
				copy.table(),
				copy.projection(),
				copy.selectItems(),
				copy.pkColumnOrNull(),
				copy.pkValueOrNull(),
				copy.joins(),
				copy.aggregate(),
				copy.countStar(),
				copy.sumColumnOrNull(),
				copy.avg(),
				copy.groupByColumns(),
				copy.offset(),
				copy.limitOrNull(),
				copy.distinct(),
				copy.havingOrNull(),
				copy.minAgg(),
				copy.maxAgg(),
				copy.windowFuncOrNull(),
				copy.windowPartitionColumns(),
				copy.windowOrderColOrNull(),
				copy.whereSubqueries(),
				copy.fromFunctionOrNull(),
				copy.forUpdate(),
				copy.skipLocked()
		);
	}

	
	private static void appendFrom(StringBuilder sb, SelectSql s) {
		if (s.fromFunctionOrNull() != null) {
			final FunctionFrom from = s.fromFunctionOrNull();
			sb.append(from.functionName()).append('(');
			if (from.args() != null) {
				boolean first = true;
				for (FuncArg arg : from.args()) {
					if (!first) {
						sb.append(", ");
					}
					first = false;
					if (arg instanceof LiteralFuncArg lit) {
						sb.append(renderLiteral(lit.value()));
					} else if (arg instanceof ColumnFuncArg col) {
						sb.append(col.column());
					}
				}
			}
			sb.append(')');
			if (from.aliasOrNull() != null && !from.aliasOrNull().isBlank()) {
				sb.append(" AS ").append(from.aliasOrNull());
			}
			return;
		}
		sb.append(s.table());
	}

	private static String renderLiteral(Object value) {
		if (value == null) {
			return "NULL";
		}
		if (value instanceof Number || value instanceof Boolean) {
			return String.valueOf(value);
		}
		return "'" + String.valueOf(value).replace("'", "''") + "'";
	}
	private static void appendProjection(StringBuilder sb, SelectSql s) {
		final List<String> projection = s.projection();
		if (projection == null || projection.isEmpty()
				|| (projection.size() == 1 && "*".equals(projection.getFirst()))) {
			sb.append('*');
			return;
		}
		if (s.selectItems() != null && !s.selectItems().isEmpty()) {
			boolean first = true;
			for (SelectItem item : s.selectItems()) {
				if (!first) {
					sb.append(", ");
				}
				first = false;
				sb.append(renderSelectItem(item));
			}
			return;
		}
		boolean first = true;
		for (String col : projection) {
			if (!first) {
				sb.append(", ");
			}
			first = false;
			sb.append(col);
		}
	}

	private static String renderSelectItem(SelectItem item) {
		if (item instanceof ColumnSelectItem col) {
			if (col.aliasOrNull() != null && !col.aliasOrNull().isBlank()) {
				return col.column() + " AS " + col.aliasOrNull();
			}
			return col.column();
		}
		if (item instanceof AggregateSelectItem || item instanceof WindowSelectItem
				|| item instanceof FunctionSelectItem) {
			return item.label();
		}
		return item.label();
	}

	private static String joinKeyword(JoinKind kind) {
		return switch (kind) {
			case LEFT -> "LEFT OUTER";
			case RIGHT -> "RIGHT OUTER";
			case FULL -> "FULL OUTER";
			case INNER -> "INNER";
		};
	}

	/**
	 * Append GROUP BY / HAVING / ORDER BY / LIMIT from original SQL via ANTLR (not keyword indexOf).
	 * WHERE is omitted when the original had WHERE-subqueries (caller applies typed filters),
	 * otherwise WHERE text is preserved from the parse tree.
	 */
	private static void appendTailFromOriginal(
			StringBuilder sb,
			String originalSql,
			boolean includeForUpdate,
			boolean includePaging
	) {
		if (originalSql == null || originalSql.isBlank()) {
			return;
		}
		final SimplifiedSqlParser.SelectQueryContext query = parseSelectQuery(originalSql);
		if (query == null) {
			return;
		}
		if (query.WHERE() != null && query.expression() != null && !query.expression().isEmpty()) {
			sb.append(" WHERE ").append(textOf(query.expression(0)));
		}
		if (query.GROUP() != null && query.groupByList() != null) {
			sb.append(" GROUP BY ").append(textOf(query.groupByList()));
		}
		if (query.HAVING() != null && query.havingExpr != null) {
			sb.append(" HAVING ").append(textOf(query.havingExpr));
		}
		if (query.windowClause() != null) {
			sb.append(' ').append(textOf(query.windowClause()));
		}
		if (query.ORDER() != null && query.orderList() != null) {
			sb.append(" ORDER BY ").append(textOf(query.orderList()));
		}
		if (includePaging && query.LIMIT() != null && query.limitClause() != null) {
			sb.append(" LIMIT ").append(textOf(query.limitClause()));
		}
		if (includePaging && query.OFFSET() != null && query.offsetInt != null) {
			sb.append(" OFFSET ").append(query.offsetInt.getText());
		}
		if (includeForUpdate && query.forUpdateClause() != null) {
			sb.append(" FOR UPDATE");
			if (query.forUpdateClause().LOCKED() != null) {
				sb.append(" SKIP LOCKED");
			}
		}
	}

	private static SimplifiedSqlParser.SelectQueryContext parseSelectQuery(String sql) {
		try {
			final SimplifiedSqlLexer lexer = new SimplifiedSqlLexer(CharStreams.fromString(sql));
			final SimplifiedSqlParser parser = new SimplifiedSqlParser(new CommonTokenStream(lexer));
			final SimplifiedSqlParser.QueryContext q = parser.query();
			if (q == null || q.selectQuery() == null) {
				return null;
			}
			return q.selectQuery();
		} catch (RuntimeException ex) {
			return null;
		}
	}

	private static String textOf(org.antlr.v4.runtime.ParserRuleContext ctx) {
		final int a = ctx.getStart().getStartIndex();
		final int b = ctx.getStop().getStopIndex();
		return ctx.getStart().getInputStream().getText(Interval.of(a, b));
	}
}
