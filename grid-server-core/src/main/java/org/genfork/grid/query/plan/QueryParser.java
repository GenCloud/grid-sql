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
package org.genfork.grid.query.plan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.antlr.v4.runtime.tree.TerminalNode;

import org.genfork.grid.sql.SqlIdentParseUtil;
import org.genfork.grid.antlr.SimplifiedSqlBaseVisitor;
import org.genfork.grid.antlr.SimplifiedSqlLexer;
import org.genfork.grid.antlr.SimplifiedSqlParser;
import org.genfork.grid.antlr.SimplifiedSqlParser.AggregateExprContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.AndExpressionContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.BetweenContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.ColumnNameContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.ComparisonContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.ComparisonSubqueryContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.ExistsSubqueryContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.InContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.InSubqueryContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.IsNotNullContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.IsNullContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.JoinClauseContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.LikeContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.LimitClauseContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.NotExpressionContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.OrExpressionContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.OrderItemContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.OrderListContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.ParenExpressionContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.QueryContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.SelectQueryContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.SelectItemContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.SelectListContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.FromItemContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.TableNameContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.TrueOrFalseExpressionContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.ValueContext;
import org.genfork.grid.mem.index.AbstractIndexOperation;
import org.genfork.grid.mem.index.btree.CompositeTreeKey;
import org.genfork.grid.query.filters.FilterCondition;
import org.genfork.grid.query.filters.impl.AlwaysFalseCondition;
import org.genfork.grid.query.filters.impl.AlwaysTrueCondition;
import org.genfork.grid.query.filters.impl.AndCondition;
import org.genfork.grid.query.filters.impl.IsNotNullCondition;
import org.genfork.grid.query.filters.impl.IsNullCondition;
import org.genfork.grid.query.filters.impl.LogicalOperatorCondition;
import org.genfork.grid.query.filters.impl.NotCondition;
import org.genfork.grid.query.filters.impl.OrCondition;
import org.genfork.grid.antlr.SimplifiedSqlParser.FunctionComparisonContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.FunctionCallContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.FuncArgContext;
import org.genfork.grid.query.filters.impl.UdfComparisonCondition;
import org.genfork.grid.query.plan.SortOrderData.OrderDirection;

/**
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
public class QueryParser {
	/** SQL → parsed plan; skip when explain plan is requested. */
	private static final Map<String, QueryData> PLAN_CACHE = new ConcurrentHashMap<>();

	public static void clearPlanCache() {
		PLAN_CACHE.clear();
	}

	public static QueryData parseAndBuildCondition(ExplainQuery.QueryPlan queryPlan,
	                                               String sql,
	                                               Map<List<String>, AbstractIndexOperation<byte[][], CompositeTreeKey>> compositeIndexes) {
		return parseAndBuildCondition(queryPlan, sql, compositeIndexes, null);
	}

	/**
	 * Parse SELECT filter tree; when {@code resolvedWhereSubqueries} is non-empty, each
	 * IN / scalar subquery node is replaced in order by the pre-evaluated {@link FilterCondition}
	 * (no SQL string splice). Plan cache is skipped for that path.
	 */
	public static QueryData parseAndBuildCondition(ExplainQuery.QueryPlan queryPlan,
	                                               String sql,
	                                               Map<List<String>, AbstractIndexOperation<byte[][], CompositeTreeKey>> compositeIndexes,
	                                               List<FilterCondition> resolvedWhereSubqueries) {
		final boolean hasResolved = resolvedWhereSubqueries != null && !resolvedWhereSubqueries.isEmpty();
		final String cacheKey = sql == null ? "" : sql.trim();
		if (!hasResolved && queryPlan == null && !cacheKey.isEmpty()) {
			final QueryData cached = PLAN_CACHE.get(cacheKey);
			if (cached != null) {
				return cached;
			}
		}

		final QueryData built = parseAndBuildConditionUncached(
				queryPlan, sql, compositeIndexes, resolvedWhereSubqueries);
		if (!hasResolved && queryPlan == null && !cacheKey.isEmpty() && built != null) {
			PLAN_CACHE.put(cacheKey, built);
		}
		return built;
	}

	private static QueryData parseAndBuildConditionUncached(ExplainQuery.QueryPlan queryPlan,
	                                                        String sql,
	                                                        Map<List<String>, AbstractIndexOperation<byte[][], CompositeTreeKey>> compositeIndexes,
	                                                        List<FilterCondition> resolvedWhereSubqueries) {
		final SimplifiedSqlLexer lexer = new SimplifiedSqlLexer(CharStreams.fromString(sql));
		final CommonTokenStream tokenStream = new CommonTokenStream(lexer);
		final SimplifiedSqlParser parser = new SimplifiedSqlParser(tokenStream);

		final QueryContext queryRoot = parser.query();
		if (queryRoot.unionTail() != null && !queryRoot.unionTail().isEmpty()) {
			throw new IllegalArgumentException("set op is not supported in index plan path; execute via SqlEngine");
		}
		final SelectQueryContext query = queryRoot.selectQuery();

		String[] fieldSelection = null;
		boolean countStar = false;
		String sumColumn = null;
		boolean avg = false;
		boolean min = false;
		boolean max = false;
		final SelectListContext selectListContext = query.selectList();
		if (selectListContext != null) {
			if ("*".equals(selectListContext.getText())) {
				fieldSelection = null;
			} else if (selectListContext.selectItem() != null && !selectListContext.selectItem().isEmpty()) {
				final List<String> cols = new ArrayList<>();
				for (SelectItemContext item : selectListContext.selectItem()) {
					if (item.aggregateExpr() != null) {
						final AggregateExprContext agg = item.aggregateExpr();
						if (agg.COUNT() != null) {
							countStar = true;
						} else if (agg.SUM() != null) {
							sumColumn = simpleColumn(agg.columnName());
						} else if (agg.AVG() != null) {
							sumColumn = simpleColumn(agg.columnName());
							avg = true;
						} else if (agg.MIN() != null) {
							sumColumn = simpleColumn(agg.columnName());
							min = true;
						} else if (agg.MAX() != null) {
							sumColumn = simpleColumn(agg.columnName());
							max = true;
						}
					} else if (item.columnName() != null && item.columnName().ident() != null) {
						cols.add(simpleColumn(item.columnName()));
					}
				}
				if (!cols.isEmpty()) {
					fieldSelection = cols.toArray(String[]::new);
				}
			}
		}

		String tableName = null;
		if (query.FROM() != null) {
			final FromItemContext from = query.fromItem();
			if (from.tableName() != null) {
				tableName = from.tableName().getText();
			} else if (from.functionCall() != null) {
				final String alias = SqlIdentParseUtil.fromItemAlias(from);
				tableName = alias != null
						? alias
						: from.functionCall().ID().getText();
			}
		}

		List<JoinSpec> joins = List.of();
		if (query.joinClause() != null && !query.joinClause().isEmpty()) {
			final ArrayList<JoinSpec> acc = new ArrayList<>(query.joinClause().size());
			for (JoinClauseContext jc : query.joinClause()) {
				acc.add(new JoinSpec(
						SqlIdentParseUtil.joinTableName(jc),
						SqlIdentParseUtil.joinTargetAlias(jc.joinTarget()),
						SqlIdentParseUtil.joinEqs(jc.joinCond()),
						SqlIdentParseUtil.joinKindOf(jc)
				));
			}
			joins = List.copyOf(acc);
		}

		List<String> groupByColumns = List.of();
		if (query.GROUP() != null && query.groupByList() != null) {
			final List<String> cols = new ArrayList<>();
			for (SimplifiedSqlParser.ColumnNameContext c : query.groupByList().columnName()) {
				cols.add(simpleColumn(c));
			}
			groupByColumns = List.copyOf(cols);
		}

		SortOrderData[] sortOrders = null;
		if (query.ORDER() != null) {
			final OrderListContext orderListContext = query.orderList();

			final List<OrderItemContext> orderItemContexts = orderListContext.orderItem();
			sortOrders = new SortOrderData[orderItemContexts.size()];

			for (int i = 0; i < orderItemContexts.size(); i++) {
				final OrderItemContext ctx = orderItemContexts.get(i);
				final String column = simpleColumn(ctx.columnName());
				final OrderDirection direction = ctx.DESC() == null || ctx.ASC() != null ? OrderDirection.ASC : OrderDirection.DESC;
				sortOrders[i] = new SortOrderData(column, direction);
			}
		}

		FilterConditionData conditionData;
		if (query.WHERE() != null && !query.expression().isEmpty()) {
			final SqlFilterVisitor filterVisitor = new SqlFilterVisitor(resolvedWhereSubqueries);
			final FilterCondition baseCondition = filterVisitor.visit(query.expression(0));
			// Composite optimize deferred to execute time (binds live index instances).
			conditionData = new FilterConditionData(baseCondition, false);
		} else {
			// SELECT without WHERE → full table via AlwaysTrue / PK searchAll
			conditionData = new FilterConditionData(AlwaysTrueCondition.getInstance(), false);
		}

		int offset = 0;
		// No LIMIT → unbounded scan (avoid Integer.MAX_VALUE overflow in offset+limit helpers).
		int limit = 1_000_000_000;

		if (query.LIMIT() != null) {
			final LimitClauseContext limitClauseContext = query.limitClause();
			final TerminalNode first = limitClauseContext.INT(0);
			final TerminalNode second = limitClauseContext.INT(1);
			if (second != null) {
				// LIMIT offset, count
				offset = Math.max(0, Integer.parseInt(first.getSymbol().getText()));
				limit = Math.max(0, Integer.parseInt(second.getSymbol().getText()));
			} else if (first != null) {
				// LIMIT count
				limit = Math.max(0, Integer.parseInt(first.getSymbol().getText()));
			}
		}
		if (query.OFFSET() != null && query.offsetInt != null) {
			if (query.LIMIT() != null && query.limitClause() != null && query.limitClause().INT(1) != null) {
				throw new IllegalArgumentException("cannot combine LIMIT offset,count with OFFSET clause");
			}
			offset = Math.max(0, Integer.parseInt(query.offsetInt.getText()));
		}

		final PagingData pagingData = new PagingData(offset, limit);
		final AggregateSpec aggregate = (countStar || sumColumn != null || !groupByColumns.isEmpty())
				? new AggregateSpec(countStar, sumColumn, avg, min, max, groupByColumns)
				: null;

		ExplainQuery.QueryPlanNode queryPlanNode = null;
		if (queryPlan != null) {
			final String filterDesc = conditionData != null ? conditionData.conditionTree().toPlanPartString() : "none";
			final String aggDesc = aggregate == null ? "" : (" agg=" + aggregate);
			final String joinDesc = joins.isEmpty() ? "" : (" join=" + joins);
			queryPlanNode = ExplainQuery.startNode(queryPlan, "PARSE", filterDesc + aggDesc + joinDesc);
		}

		try {
			return new QueryData(tableName, fieldSelection, conditionData, pagingData, sortOrders, aggregate, joins);
		} finally {
			if (queryPlanNode != null) {
				ExplainQuery.endNode(queryPlanNode, -1, -1);
				queryPlan.completeCurrentNode();
			}
		}
	}

	public static class SqlFilterVisitor extends SimplifiedSqlBaseVisitor<FilterCondition> {
		private final ArrayDeque<FilterCondition> resolvedSubqueries;

		public SqlFilterVisitor() {
			this(null);
		}

		public SqlFilterVisitor(List<FilterCondition> resolvedWhereSubqueries) {
			this.resolvedSubqueries = resolvedWhereSubqueries == null || resolvedWhereSubqueries.isEmpty()
					? null
					: new ArrayDeque<>(resolvedWhereSubqueries);
		}

		@Override
		public FilterCondition visitAndExpression(AndExpressionContext ctx) {
			final FilterCondition left = visit(ctx.expression(0));
			final FilterCondition right = visit(ctx.expression(1));

			if (left == null && right == null) {
				return null;
			}

			if (left == null) {
				return right;
			}

			if (right == null) {
				return left;
			}

			return new AndCondition(left, right);
		}

		@Override
		public FilterCondition visitOrExpression(OrExpressionContext ctx) {
			final FilterCondition left = visit(ctx.expression(0));
			final FilterCondition right = visit(ctx.expression(1));

			if (left == null && right == null) {
				return null;
			}

			if (left == null) {
				return right;
			}

			if (right == null) {
				return left;
			}

			return new OrCondition(left, right);
		}

		@Override
		public FilterCondition visitNotExpression(NotExpressionContext ctx) {
			final FilterCondition child = visit(ctx.expression());
			return child != null ? new NotCondition(child) : null;
		}

		@Override
		public FilterCondition visitParenExpression(ParenExpressionContext ctx) {
			return visit(ctx.expression());
		}

		@Override
		public FilterCondition visitComparison(ComparisonContext ctx) {
			final String column = simpleColumn(ctx.columnName());
			final String op = ctx.operator().getText();
			final Object value = parseValue(ctx.value());

			final LogicalOperatorCondition.Operator operator = convertOperator(op);
			return new LogicalOperatorCondition(column, operator, value);
		}

		@Override
		public FilterCondition visitFunctionComparison(FunctionComparisonContext ctx) {
			final FunctionCallContext fc = ctx.functionCall();
			final String name = fc.ID().getText();
			final List<String> argCols = new ArrayList<>();
			final List<Object> argLits = new ArrayList<>();
			if (fc.funcArg() != null) {
				for (FuncArgContext arg : fc.funcArg()) {
					if (arg.columnName() != null) {
						argCols.add(simpleColumn(arg.columnName()));
						argLits.add(null);
					} else {
						argCols.add(null);
						argLits.add(parseValue(arg.value()));
					}
				}
			}
			return new UdfComparisonCondition(
					name,
					argCols,
					argLits,
					convertOperator(ctx.operator().getText()),
					parseValue(ctx.value())
			);
		}

		@Override
		public FilterCondition visitBetween(BetweenContext ctx) {
			final String column = simpleColumn(ctx.columnName());
			final Object low = parseValue(ctx.value(0));
			final Object high = parseValue(ctx.value(1));

			return new LogicalOperatorCondition(column, LogicalOperatorCondition.Operator.BETWEEN, low, high);
		}

		@Override
		public FilterCondition visitIn(InContext ctx) {
			final String column = simpleColumn(ctx.columnName());
			final List<Object> values = new ArrayList<>();
			for (ValueContext valueCtx : ctx.valueList().value()) {
				values.add(parseValue(valueCtx));
			}

			final Object[] array = values.toArray(new Object[0]);
			return new LogicalOperatorCondition(column, LogicalOperatorCondition.Operator.IN, array);
		}

		@Override
		public FilterCondition visitInSubquery(InSubqueryContext ctx) {
			return takeResolvedSubquery("IN subquery");
		}

		@Override
		public FilterCondition visitExistsSubquery(ExistsSubqueryContext ctx) {
			return takeResolvedSubquery("EXISTS subquery");
		}

		@Override
		public FilterCondition visitComparisonSubquery(ComparisonSubqueryContext ctx) {
			return takeResolvedSubquery("scalar subquery");
		}

		private FilterCondition takeResolvedSubquery(String kind) {
			if (resolvedSubqueries == null) {
				// Soft parse (ORDER BY / helpers): subquery nodes are ignored here.
				return AlwaysTrueCondition.getInstance();
			}
			if (resolvedSubqueries.isEmpty()) {
				throw new IllegalArgumentException(kind + " must be resolved before QueryParser filter build");
			}
			return resolvedSubqueries.removeFirst();
		}

		@Override
		public FilterCondition visitLike(LikeContext ctx) {
			final String column = simpleColumn(ctx.columnName());
			final String pattern = unquoteSqlString(ctx.STRING().getText());
			return new LogicalOperatorCondition(column, LogicalOperatorCondition.Operator.LIKE, pattern);
		}

		@Override
		public FilterCondition visitIsNull(IsNullContext ctx) {
			final String column = simpleColumn(ctx.columnName());
			return new IsNullCondition(column);
		}

		@Override
		public FilterCondition visitIsNotNull(IsNotNullContext ctx) {
			final String column = simpleColumn(ctx.columnName());
			return new IsNotNullCondition(column);
		}

		@Override
		public FilterCondition visitTrueOrFalseExpression(TrueOrFalseExpressionContext ctx) {
			final boolean true_ = Boolean.parseBoolean(ctx.getText());
			return true_ ? AlwaysTrueCondition.getInstance() : AlwaysFalseCondition.getInstance();
		}

		private LogicalOperatorCondition.Operator convertOperator(String op) {
			return switch (op) {
				case "=" -> LogicalOperatorCondition.Operator.EQ;
				case "!=" -> LogicalOperatorCondition.Operator.NE;
				case ">" -> LogicalOperatorCondition.Operator.GT;
				case ">=" -> LogicalOperatorCondition.Operator.GE;
				case "<" -> LogicalOperatorCondition.Operator.LT;
				case "<=" -> LogicalOperatorCondition.Operator.LE;
				default -> throw new IllegalArgumentException("Unknown operator: " + op);
			};
		}

		private Object parseValue(ValueContext ctx) {
			if (ctx.CAST() != null) {
				return castValue(parseValue(ctx.value()), ctx.typeName().getText());
			}
			if (ctx.caseExpr() != null) {
				throw new IllegalArgumentException(
						"non-constant CASE in WHERE not supported; use CAST or literal");
			}
			if (ctx.INT() != null) {
				return Integer.parseInt(ctx.INT().getText());
			}

			if (ctx.FLOAT() != null) {
				return Double.parseDouble(ctx.FLOAT().getText());
			}

			if (ctx.STRING() != null) {
				return unquoteSqlString(ctx.STRING().getText());
			}

			if (ctx.NULL() != null) {
				return null;
			}

			if (ctx.TRUE() != null) {
				return Boolean.TRUE;
			}
			if (ctx.FALSE() != null) {
				return Boolean.FALSE;
			}

			return null;
		}

		private static Object castValue(Object value, String typeToken) {
			if (value == null) {
				return null;
			}
			final String t = typeToken.trim().toUpperCase(Locale.ROOT);
			return switch (t) {
				case "INT", "INTEGER" -> value instanceof Number n
						? Integer.valueOf(n.intValue())
						: Integer.valueOf(String.valueOf(value));
				case "BIGINT", "LONG" -> value instanceof Number n
						? Long.valueOf(n.longValue())
						: Long.valueOf(String.valueOf(value));
				case "DOUBLE", "FLOAT", "REAL" -> value instanceof Number n
						? Double.valueOf(n.doubleValue())
						: Double.valueOf(String.valueOf(value));
				case "BOOLEAN", "BOOL" -> value instanceof Boolean b
						? b
						: Boolean.valueOf(String.valueOf(value));
				case "VARCHAR", "STRING", "TEXT" -> String.valueOf(value);
				default -> throw new IllegalArgumentException("Unsupported CAST type: " + typeToken);
			};
		}
	}

	/** Unqualified column id ({@code t.col} → {@code col}). */
	private static String simpleColumn(ColumnNameContext ctx) {
		try {
			return SqlIdentParseUtil.simpleColumn(ctx);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** Strip outer SQL quotes from an ANTLR {@code STRING} token (no regexp). */
	private static String unquoteSqlString(String raw) {
		if (raw == null) {
			return null;
		}
		if (raw.length() >= 2 && raw.charAt(0) == '\'' && raw.charAt(raw.length() - 1) == '\'') {
			return raw.substring(1, raw.length() - 1).replace("''", "'");
		}
		return raw;
	}
}
