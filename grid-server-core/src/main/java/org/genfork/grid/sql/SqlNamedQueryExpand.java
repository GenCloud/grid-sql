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
package org.genfork.grid.sql;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.genfork.grid.antlr.SimplifiedSqlLexer;
import org.genfork.grid.antlr.SimplifiedSqlParser;
import org.genfork.grid.antlr.SimplifiedSqlParser.CteDefContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.ExecutableContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.QueryContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.SelectQueryContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.SetOperatorContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.StatementContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.UnionTailContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.WithQueryContext;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared expand for non-recursive WITH CTE and catalog VIEW definitions into flat SELECT text.
 * <p>
 * Called once at the {@link SqlEngine} edge so {@link SqlStatementParser} and
 * {@link org.genfork.grid.query.plan.QueryParser} see the same expanded SQL.
 * Results are cached by SQL + view-body fingerprint (invalidate via {@link #clearCache()} on DDL).
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlNamedQueryExpand {
	/** Max CTE/VIEW inline steps (guards against cyclic name graphs). */
	private static final int MAX_EXPAND_DEPTH = 16;
	private static final int EXPAND_CACHE_MAX = 512;
	private static final ConcurrentHashMap<String, String> EXPAND_CACHE = new ConcurrentHashMap<>();
	private static final String UNION_SEP = " UNION ";
	private static final String UNION_ALL_SEP = " UNION ALL ";
	private static final String INTERSECT_SEP = " INTERSECT ";
	private static final String INTERSECT_ALL_SEP = " INTERSECT ALL ";
	private static final String EXCEPT_SEP = " EXCEPT ";
	private static final String EXCEPT_ALL_SEP = " EXCEPT ALL ";
	private static final String ERR_JOIN_ON_JOIN =
			"JOIN on CTE/VIEW that already has JOIN is not supported";
	private static final String ERR_COMPLEX_OUTER_AGG =
			"cannot further aggregate/join CTE/VIEW with join or agg body";
	private static final String ERR_SET_OP_BODY =
			"CTE/VIEW body with set op cannot be inlined further";
	private static final String ERR_BODY_NOT_SELECT =
			"CTE/VIEW body is not a SELECT: ";
	private static final String ERR_EXPAND_DEPTH =
			"CTE/VIEW expand depth exceeded (possible cycle)";
	/** Inline / expand result: rewritten SQL or defer (keep FROM name for runtime evaluate). */
	private record ExpandOutcome(String sql, boolean deferred) {
	}
	/** Bail on first syntax error — no ConsoleErrorListener spam (e.g. SELECT1). */
	private static final BaseErrorListener BAIL_ERRORS = new BaseErrorListener() {
		@Override
		public void syntaxError(
				Recognizer<?, ?> recognizer,
				Object offendingSymbol,
				int line,
				int charPositionInLine,
				String msg,
				RecognitionException e
		) {
			throw new ParseCancellationException("line " + line + ":" + charPositionInLine + " " + msg, e);
		}
	};

	private SqlNamedQueryExpand() {
	}

	/**
	 * Drop cached expansions after VIEW / catalog mutations.
	 */
	public static void clearCache() {
		EXPAND_CACHE.clear();
	}

	/**
	 * Expand WITH / VIEW references. DDL that creates or drops views is returned unchanged.
	 *
	 * @param sql           materialized bind SQL
	 * @param catalogViews  name (lower) → defining SELECT text (non-materialized views)
	 * @return expanded SQL (may equal {@code sql})
	 */
	public static String expand(String sql, Map<String, String> catalogViews) {
		if (sql == null || sql.isBlank()) {
			return sql;
		}
		// Capacity / ad-hoc EQ: no VIEW catalog and no WITH → identity (skip ANTLR expand pass).
		// WITH still needs expandUncached; VIEW names need named-map rewrite.
		final boolean noViews = catalogViews == null || catalogViews.isEmpty();
		if (noViews && !startsWithWith(sql)) {
			return sql;
		}
		final String cacheKey = cacheKey(sql, catalogViews);
		final String cached = EXPAND_CACHE.get(cacheKey);
		if (cached != null) {
			return cached;
		}
		final String expanded = expandUncached(sql, catalogViews);
		if (EXPAND_CACHE.size() < EXPAND_CACHE_MAX) {
			EXPAND_CACHE.putIfAbsent(cacheKey, expanded);
		}
		return expanded;
	}

	private static String cacheKey(String sql, Map<String, String> catalogViews) {
		if (catalogViews == null || catalogViews.isEmpty()) {
			return sql;
		}
		return sql + '\u0000' + catalogViews.hashCode();
	}

	private static String expandUncached(String sql, Map<String, String> catalogViews) {
		final SimplifiedSqlLexer lexer = new SimplifiedSqlLexer(CharStreams.fromString(sql));
		lexer.removeErrorListeners();
		lexer.addErrorListener(BAIL_ERRORS);
		final CommonTokenStream tokens = new CommonTokenStream(lexer);
		final SimplifiedSqlParser parser = new SimplifiedSqlParser(tokens);
		parser.removeErrorListeners();
		parser.addErrorListener(BAIL_ERRORS);
		final StatementContext stmt = parser.statement();
		final ExecutableContext ex = stmt.executable();
		if (ex.createViewStmt() != null
				|| ex.dropViewStmt() != null
				|| ex.createMaterializedViewStmt() != null
				|| ex.refreshMaterializedViewStmt() != null
				|| ex.createFunctionStmt() != null
				|| ex.dropFunctionStmt() != null
				|| ex.createTriggerStmt() != null
				|| ex.dropTriggerStmt() != null
				|| ex.createTableStmt() != null
				|| ex.dropTableStmt() != null
				|| ex.deleteStmt() != null
				|| ex.updateStmt() != null
				|| ex.beginStmt() != null
				|| ex.commitStmt() != null
				|| ex.rollbackStmt() != null
				|| ex.prepareStmt() != null
				|| ex.executeStmt() != null
				|| ex.deallocateStmt() != null
				|| ex.pinStmt() != null
				|| ex.unpinStmt() != null
				|| ex.createIndexStmt() != null
				|| ex.dropIndexStmt() != null
				|| ex.createSchemaStmt() != null
				|| ex.dropSchemaStmt() != null
				|| ex.setSchemaStmt() != null
				|| ex.alterTableStmt() != null) {
			return sql;
		}

		final Map<String, String> named = new HashMap<>();
		if (catalogViews != null) {
			named.putAll(catalogViews);
		}

		// INSERT/UPSERT … SELECT: expand VIEW names in the source query (VALUES form unchanged).
		if (ex.insertStmt() != null) {
			return expandInsertStmt(ex.insertStmt(), tokens, named);
		}

		QueryContext outer;
		if (ex.withQuery() != null) {
			final WithQueryContext wq = ex.withQuery();
			if (wq.RECURSIVE() != null) {
				// Recursive CTE is executed iteratively — do not inline.
				return sql;
			}
			for (CteDefContext cte : wq.cteDef()) {
				final String name = cte.ID().getText().toLowerCase(Locale.ROOT);
				final String body = textOf(tokens, cte.query());
				named.put(name, body);
			}
			outer = wq.query();
			final ExpandOutcome expanded = expandQueryOutcome(outer, tokens, named);
			// Deferred residual left FROM cte — keep original WITH for WithSelectSql parse.
			if (expanded.deferred()) {
				return sql;
			}
			return expanded.sql();
		} else if (ex.query() != null) {
			outer = ex.query();
		} else if (ex.explainStmt() != null) {
			if (ex.explainStmt().explainBody().query() == null) {
				return sql;
			}
			final ExpandOutcome inner = expandQueryOutcome(ex.explainStmt().explainBody().query(), tokens, named);
			if (ex.explainStmt().ANALYZE() != null) {
				return "EXPLAIN ANALYZE " + inner.sql();
			}
			return "EXPLAIN " + inner.sql();
		} else {
			return sql;
		}

		return expandQueryOutcome(outer, tokens, named).sql();
	}

	/**
	 * Expand catalog VIEW / CTE names inside {@code INSERT|UPSERT … SELECT} only.
	 * {@code INSERT … VALUES} is returned unchanged (no FROM).
	 */
	private static String expandInsertStmt(
			SimplifiedSqlParser.InsertStmtContext insert,
			CommonTokenStream tokens,
			Map<String, String> named
	) {
		if (insert.query() == null) {
			return textOf(tokens, insert);
		}
		final ExpandOutcome expandedSelect = expandQueryOutcome(insert.query(), tokens, named);
		final org.antlr.v4.runtime.CharStream input = tokens.getTokenSource().getInputStream();
		final int insertStart = insert.start.getStartIndex();
		final int queryStart = insert.query().start.getStartIndex();
		final int queryStop = insert.query().stop.getStopIndex();
		final int insertStop = insert.stop.getStopIndex();
		final String prefix = input.getText(Interval.of(insertStart, queryStart - 1));
		final String suffix = queryStop < insertStop
				? input.getText(Interval.of(queryStop + 1, insertStop))
				: "";
		return prefix + expandedSelect.sql() + suffix;
	}

	private static ExpandOutcome expandQueryOutcome(
			QueryContext outer,
			CommonTokenStream tokens,
			Map<String, String> named
	) {
		if (outer.selectExprQuery() != null) {
			return new ExpandOutcome(textOf(tokens, outer.selectExprQuery()), false);
		}
		final SelectQueryContext head = outer.selectQuery();
		final List<UnionTailContext> tails = outer.unionTail();
		if (tails == null || tails.isEmpty()) {
			return expandSelectOutcome(head, tokens, named);
		}
		final StringBuilder sb = new StringBuilder();
		boolean deferred = false;
		final ExpandOutcome headOut = expandSelectOutcome(head, tokens, named);
		deferred |= headOut.deferred();
		sb.append(headOut.sql());
		for (UnionTailContext tail : tails) {
			sb.append(setOpText(tail));
			final ExpandOutcome arm = expandSelectOutcome(tail.selectQuery(), tokens, named);
			deferred |= arm.deferred();
			sb.append(arm.sql());
		}
		return new ExpandOutcome(sb.toString(), deferred);
	}

	private static String setOpText(UnionTailContext tail) {
		final SetOperatorContext op = tail.setOperator();
		final boolean all = tail.ALL() != null;
		if (op != null && op.INTERSECT() != null) {
			return all ? INTERSECT_ALL_SEP : INTERSECT_SEP;
		}
		if (op != null && op.EXCEPT() != null) {
			return all ? EXCEPT_ALL_SEP : EXCEPT_SEP;
		}
		return all ? UNION_ALL_SEP : UNION_SEP;
	}

	private static ExpandOutcome expandSelectOutcome(
			SelectQueryContext outer,
			CommonTokenStream tokens,
			Map<String, String> named
	) {
		String current = textOf(tokens, outer);
		for (int guard = 0; guard < MAX_EXPAND_DEPTH; guard++) {
			final SimplifiedSqlLexer lexer = new SimplifiedSqlLexer(CharStreams.fromString(current));
			final CommonTokenStream ts = new CommonTokenStream(lexer);
			final SimplifiedSqlParser p = new SimplifiedSqlParser(ts);
			final SelectQueryContext q = p.query().selectQuery();
			final String fromName = fromTableName(q);
			final String key = fromName.toLowerCase(Locale.ROOT);
			final int dot = key.lastIndexOf('.');
			final String simple = dot >= 0 ? key.substring(dot + 1) : key;
			final String body = named.containsKey(key) ? named.get(key) : named.get(simple);
			if (body == null) {
				return new ExpandOutcome(current, false);
			}
			final ExpandOutcome outcome = inlineNamed(q, ts, body);
			if (outcome.deferred()) {
				// Keep FROM view/cte + outer WHERE for runtime residual evaluate.
				return outcome;
			}
			current = outcome.sql();
		}
		throw new IllegalArgumentException(ERR_EXPAND_DEPTH);
	}

	private static ExpandOutcome inlineNamed(
			SelectQueryContext outer,
			CommonTokenStream tokens,
			String innerSql
	) {
		String flatInner = innerSql;
		if (startsWithWith(innerSql)) {
			flatInner = expand(innerSql, Map.of());
		}
		final SimplifiedSqlLexer lexer = new SimplifiedSqlLexer(CharStreams.fromString(flatInner));
		final CommonTokenStream ts = new CommonTokenStream(lexer);
		final SimplifiedSqlParser p = new SimplifiedSqlParser(ts);
		final QueryContext innerRoot = p.query();
		if (innerRoot.unionTail() != null && !innerRoot.unionTail().isEmpty()) {
			throw new IllegalArgumentException(ERR_SET_OP_BODY);
		}
		final SelectQueryContext inner = innerRoot.selectQuery();
		if (inner == null) {
			throw new IllegalArgumentException(ERR_BODY_NOT_SELECT + flatInner);
		}
		final boolean innerHasJoin = inner.joinClause() != null && !inner.joinClause().isEmpty();
		final boolean outerHasJoin = outer.joinClause() != null && !outer.joinClause().isEmpty();
		if (innerHasJoin && outerHasJoin) {
			throw new IllegalArgumentException(ERR_JOIN_ON_JOIN);
		}
		if (innerHasJoin || inner.GROUP() != null || selectListHasAggOrWindow(inner.selectList())) {
			if (outer.GROUP() != null || outer.HAVING() != null
					|| outerHasJoin
					|| selectListHasAggOrWindow(outer.selectList())) {
				throw new IllegalArgumentException(ERR_COMPLEX_OUTER_AGG);
			}
			if (outer.WHERE() != null) {
				// Residual WHERE on JOIN/WINDOW body — defer (do not push past RANK).
				return new ExpandOutcome(textOf(tokens, outer), true);
			}
			final StringBuilder sb = new StringBuilder(textOf(ts, inner));
			if (outer.ORDER() != null) {
				sb.append(" ORDER BY ").append(textOf(tokens, outer.orderList()));
			}
			if (outer.LIMIT() != null) {
				sb.append(" LIMIT ").append(textOf(tokens, outer.limitClause()));
			}
			if (outer.OFFSET() != null && outer.offsetInt != null) {
				sb.append(" OFFSET ").append(outer.offsetInt.getText());
			}
			return new ExpandOutcome(sb.toString(), false);
		}

		final String baseTable = fromTableName(inner);
		final String proj;
		if (outer.selectList() != null && "*".equals(outer.selectList().getText())) {
			proj = textOf(ts, inner.selectList());
		} else {
			proj = textOf(tokens, outer.selectList());
		}
		final StringBuilder sb = new StringBuilder("SELECT ");
		if (outer.DISTINCT() != null) {
			sb.append("DISTINCT ");
		}
		sb.append(proj).append(" FROM ").append(baseTable);
		if (outerHasJoin) {
			for (SimplifiedSqlParser.JoinClauseContext jc : outer.joinClause()) {
				sb.append(' ').append(textOf(tokens, jc));
			}
		}
		final String innerWhere = inner.WHERE() != null ? textOf(ts, inner.expression(0)) : null;
		final String outerWhere = outer.WHERE() != null ? textOf(tokens, outer.expression(0)) : null;
		if (innerWhere != null && outerWhere != null) {
			sb.append(" WHERE (").append(innerWhere).append(") AND (").append(outerWhere).append(')');
		} else if (innerWhere != null) {
			sb.append(" WHERE ").append(innerWhere);
		} else if (outerWhere != null) {
			sb.append(" WHERE ").append(outerWhere);
		}
		if (outer.GROUP() != null && outer.groupByList() != null) {
			sb.append(" GROUP BY ").append(textOf(tokens, outer.groupByList()));
		}
		if (outer.HAVING() != null && outer.havingExpr != null) {
			sb.append(" HAVING ").append(textOf(tokens, outer.havingExpr));
		}
		if (outer.ORDER() != null) {
			sb.append(" ORDER BY ").append(textOf(tokens, outer.orderList()));
		} else if (inner.ORDER() != null) {
			sb.append(" ORDER BY ").append(textOf(ts, inner.orderList()));
		}
		if (outer.LIMIT() != null) {
			sb.append(" LIMIT ").append(textOf(tokens, outer.limitClause()));
		} else if (inner.LIMIT() != null) {
			sb.append(" LIMIT ").append(textOf(ts, inner.limitClause()));
		}
		if (outer.OFFSET() != null && outer.offsetInt != null) {
			sb.append(" OFFSET ").append(outer.offsetInt.getText());
		} else if (inner.OFFSET() != null && inner.offsetInt != null) {
			sb.append(" OFFSET ").append(inner.offsetInt.getText());
		}
		return new ExpandOutcome(sb.toString(), false);
	}

	private static boolean selectListHasAggOrWindow(SimplifiedSqlParser.SelectListContext sl) {
		if (sl == null || sl.selectItem() == null) {
			return false;
		}
		for (SimplifiedSqlParser.SelectItemContext item : sl.selectItem()) {
			if (item.aggregateExpr() != null || item.windowExpr() != null) {
				return true;
			}
		}
		return false;
	}

	private static boolean startsWithWith(String sql) {
		if (sql == null) {
			return false;
		}
		int i = 0;
		while (i < sql.length() && Character.isWhitespace(sql.charAt(i))) {
			i++;
		}
		return sql.regionMatches(true, i, "WITH", 0, 4);
	}

	private static String fromTableName(SelectQueryContext q) {
		final SimplifiedSqlParser.FromItemContext from = q.fromItem();
		if (from.tableName() != null) {
			return from.tableName().getText();
		}
		if (from.functionCall() != null) {
			final String alias = SqlIdentParseUtil.fromItemAlias(from);
			return alias != null
					? alias
					: from.functionCall().ID().getText();
		}
		throw new IllegalArgumentException("SELECT FROM requires table or function");
	}

	private static String textOf(CommonTokenStream tokens, org.antlr.v4.runtime.ParserRuleContext ctx) {
		final int start = ctx.start.getStartIndex();
		final int stop = ctx.stop.getStopIndex();
		return tokens.getTokenSource().getInputStream().getText(Interval.of(start, stop));
	}
}
