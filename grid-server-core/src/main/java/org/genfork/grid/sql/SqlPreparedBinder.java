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

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.genfork.grid.query.filters.FilterCondition;
import org.genfork.grid.query.filters.impl.AndCondition;
import org.genfork.grid.query.filters.impl.LogicalOperatorCondition;
import org.genfork.grid.query.filters.impl.NotCondition;
import org.genfork.grid.query.filters.impl.NumericPlusComparisonCondition;
import org.genfork.grid.query.filters.impl.OrCondition;
import org.genfork.grid.query.plan.UpdatePlan;
import org.genfork.grid.sql.SqlBuiltinExpr.CaseBranch;
import org.genfork.grid.sql.SqlBuiltinExpr.CaseExpr;
import org.genfork.grid.sql.SqlBuiltinExpr.CoalesceExpr;
import org.genfork.grid.sql.SqlBuiltinExpr.NumericColPlus;
import org.genfork.grid.sql.ast.DmlAst.DeleteSql;
import org.genfork.grid.sql.ast.DmlAst.InsertSql;
import org.genfork.grid.sql.ast.DmlAst.MergeSql;
import org.genfork.grid.sql.ast.DmlAst.OnConflict;
import org.genfork.grid.sql.ast.DmlAst.UpdateSql;
import org.genfork.grid.sql.ast.SelectAst.ExistsSelectItem;
import org.genfork.grid.sql.ast.SelectAst.FuncArg;
import org.genfork.grid.sql.ast.SelectAst.FunctionFrom;
import org.genfork.grid.sql.ast.SelectAst.FunctionSelectItem;
import org.genfork.grid.sql.ast.SelectAst.LiteralFuncArg;
import org.genfork.grid.sql.ast.SelectAst.SelectItem;
import org.genfork.grid.sql.ast.SelectAst.SelectSql;
import org.genfork.grid.sql.ast.SelectAst.WhereSubquery;
import org.genfork.grid.sql.ast.Stmt;

/**
 * Applies EXECUTE binds to a PREPARE-time {@link Stmt} without re-running ANTLR on the body.
 * <p>
 * Text fields that drive residual filters ({@link SelectSql#sql()}, {@code whereSql}) are replaced
 * with materialized literals when needed. Structured bind slots ({@link SqlBindParam}) are resolved
 * in place so {@code byte[]} and other wire cells stay as {@link Object}. Unsupported shapes fall
 * back to a full body parse under the session timezone.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlPreparedBinder {
	private static final char PLACEHOLDER = '?';

	private SqlPreparedBinder() {
	}

	/**
	 * Bind {@code template} using session timezone for any full-parse fallback.
	 *
	 * @param placeholderSql prepared body (views already expanded); may still contain {@code ?}
	 */
	public static Stmt bind(Stmt template, String placeholderSql, Object[] binds, ZoneId zone) {
		Objects.requireNonNull(template, "template");
		Objects.requireNonNull(placeholderSql, "placeholderSql");
		try {
			return bindStrict(template, placeholderSql, binds);
		} catch (UnsupportedOperationException ex) {
			// Residual shapes still need a full ANTLR pass (subquery placeholders, etc.).
			return SqlStatementParser.parse(SqlBinds.materialize(placeholderSql, binds), zone);
		}
	}

	private static Stmt bindStrict(Stmt template, String placeholderSql, Object[] binds) {
		if (template instanceof SelectSql s) {
			return bindSelect(s, placeholderSql, binds);
		}
		if (template instanceof InsertSql s) {
			return bindInsert(s, binds);
		}
		if (template instanceof UpdateSql s) {
			return bindUpdate(s, binds);
		}
		if (template instanceof DeleteSql s) {
			return bindDelete(s, binds);
		}
		if (template instanceof MergeSql s) {
			return bindMerge(s, binds);
		}
		if (binds == null || binds.length == 0) {
			return template;
		}
		throw new UnsupportedOperationException("prepared bind unsupported for " + template.getClass().getSimpleName());
	}

	private static SelectSql bindSelect(SelectSql s, String placeholderSql, Object[] binds) {
		final Object pk = resolve(s.pkValueOrNull(), binds);
		final List<WhereSubquery> subs = bindWhereSubs(s.whereSubqueries());
		final FunctionFrom from = bindFromFunction(s.fromFunctionOrNull(), binds);
		final List<SelectItem> items = bindSelectItems(s.selectItems(), binds);
		final String sql = hasPlaceholder(placeholderSql)
				? SqlBinds.materialize(placeholderSql, binds)
				: placeholderSql;
		return new SelectSql(
				sql,
				s.table(),
				s.projection(),
				items,
				s.pkColumnOrNull(),
				pk,
				s.joins(),
				s.aggregate(),
				s.countStar(),
				s.sumColumnOrNull(),
				s.avg(),
				s.groupByColumns(),
				resolvePaging(s.offsetValue(), binds),
				resolvePaging(s.limitValueOrNull(), binds),
				s.distinct(),
				s.havingOrNull(),
				s.minAgg(),
				s.maxAgg(),
				s.windowFuncOrNull(),
				s.windowPartitionColumns(),
				s.windowOrderColOrNull(),
				subs,
				from,
				s.forUpdate(),
				s.skipLocked()
		);
	}

	private static InsertSql bindInsert(InsertSql s, Object[] binds) {
		final List<List<Object>> rows = new ArrayList<>(s.rows().size());
		for (List<Object> row : s.rows()) {
			final List<Object> copy = new ArrayList<>(row.size());
			for (Object cell : row) {
				copy.add(resolve(cell, binds));
			}
			rows.add(SqlNullSafeCollections.freezeList(copy));
		}
		final OnConflict conflict = s.onConflictOrNull() == null
				? null
				: new OnConflict(
						s.onConflictOrNull().targetColumns(),
						s.onConflictOrNull().action(),
						bindMap(s.onConflictOrNull().updateSets(), binds)
				);
		final SelectSql selectSource = s.selectSourceOrNull();
		if (selectSource != null && hasPlaceholder(selectSource.sql())) {
			throw new UnsupportedOperationException("INSERT SELECT still has placeholders");
		}
		return new InsertSql(
				s.table(),
				s.columns(),
				List.copyOf(rows),
				conflict,
				s.returning(),
				selectSource);
	}

	private static UpdateSql bindUpdate(UpdateSql s, Object[] binds) {
		final int whereBindStart = maxBindIndex(s.setLiterals()) + 1;
		final Object pk = resolve(s.pkValueOrNull(), binds);
		final Map<String, Object> sets = bindMap(s.setLiterals(), binds);
		final String whereSql = materializeWhere(s.whereSql(), binds, whereBindStart);
		final UpdatePlan rmw = bindRmw(s.rmw(), pk);
		return new UpdateSql(
				s.table(),
				rmw,
				sets,
				whereSql,
				s.pkColumnOrNull(),
				pk,
				s.sourceTableOrNull(),
				s.targetJoinColumnOrNull(),
				s.sourceJoinColumnOrNull(),
				s.returning()
		);
	}

	private static DeleteSql bindDelete(DeleteSql s, Object[] binds) {
		final Object pk = resolve(s.pkValueOrNull(), binds);
		final String whereSql = materializeWhere(s.whereSql(), binds, 0);
		return new DeleteSql(s.table(), whereSql, s.pkColumnOrNull(), pk);
	}

	private static UpdatePlan bindRmw(UpdatePlan rmw, Object resolvedPk) {
		if (rmw == null) {
			return null;
		}
		return new UpdatePlan(rmw.table(), rmw.assigns(), rmw.pkColumn(), resolvedPk, rmw.templateKey());
	}

	private static String materializeWhere(String whereSql, Object[] binds, int startIndex) {
		if (!hasPlaceholder(whereSql)) {
			return whereSql;
		}
		return SqlBinds.materializeRange(whereSql, binds, startIndex);
	}

	private static int maxBindIndex(Map<String, Object> map) {
		if (map == null || map.isEmpty()) {
			return -1;
		}
		int max = -1;
		for (Object value : map.values()) {
			max = Math.max(max, maxBindIndexDeep(value));
		}
		return max;
	}

	private static int maxBindIndexDeep(Object value) {
		if (value instanceof SqlBindParam param) {
			return param.index();
		}
		if (value instanceof NumericColPlus plus) {
			return maxBindIndexDeep(plus.addend());
		}
		if (value instanceof CoalesceExpr coal) {
			int max = -1;
			for (Object arg : coal.args()) {
				max = Math.max(max, maxBindIndexDeep(arg));
			}
			return max;
		}
		if (value instanceof CaseExpr caseExpr) {
			int max = -1;
			for (CaseBranch branch : caseExpr.branches()) {
				max = Math.max(max, maxBindIndexCondition(branch.when()));
				max = Math.max(max, maxBindIndexDeep(branch.thenValue()));
			}
			return Math.max(max, maxBindIndexDeep(caseExpr.elseValueOrNull()));
		}
		return -1;
	}

	private static int maxBindIndexCondition(FilterCondition condition) {
		if (condition instanceof LogicalOperatorCondition loc) {
			int max = -1;
			for (Object v : loc.getValues()) {
				max = Math.max(max, maxBindIndexDeep(v));
			}
			return max;
		}
		if (condition instanceof NumericPlusComparisonCondition npc) {
			return Math.max(maxBindIndexDeep(npc.getAddend()), maxBindIndexDeep(npc.getCompareTo()));
		}
		if (condition instanceof AndCondition and) {
			return Math.max(maxBindIndexCondition(and.getLeft()), maxBindIndexCondition(and.getRight()));
		}
		if (condition instanceof OrCondition or) {
			return Math.max(maxBindIndexCondition(or.getLeft()), maxBindIndexCondition(or.getRight()));
		}
		if (condition instanceof NotCondition not) {
			return maxBindIndexCondition(not.getChild());
		}
		return -1;
	}

	private static MergeSql bindMerge(MergeSql s, Object[] binds) {
		final List<Object> sourceRow = s.sourceRowOrNull() == null
				? null
				: bindList(s.sourceRowOrNull(), binds);
		final Map<String, Object> matched = bindMap(s.matchedSetsOrNull(), binds);
		final List<Object> insertVals = s.insertValuesOrNull() == null
				? null
				: bindList(s.insertValuesOrNull(), binds);
		return new MergeSql(
				s.targetTable(),
				s.sourceTableOrNull(),
				sourceRow,
				s.targetOnCol(),
				s.sourceOnCol(),
				matched,
				s.insertColumnsOrNull(),
				insertVals
		);
	}

	private static List<WhereSubquery> bindWhereSubs(List<WhereSubquery> subs) {
		if (subs == null || subs.isEmpty()) {
			return subs;
		}
		if (hasPlaceholderInSubs(subs)) {
			throw new UnsupportedOperationException("where subquery still has placeholders");
		}
		return subs;
	}

	private static boolean hasPlaceholderInSubs(List<WhereSubquery> subs) {
		for (WhereSubquery sub : subs) {
			if (hasPlaceholder(sub.subquerySql()) || sub.subquery() != null && hasPlaceholder(sub.subquery().sql())) {
				return true;
			}
		}
		return false;
	}

	private static FunctionFrom bindFromFunction(FunctionFrom from, Object[] binds) {
		if (from == null) {
			return null;
		}
		return new FunctionFrom(from.functionName(), bindFuncArgs(from.args(), binds), from.aliasOrNull());
	}

	private static List<SelectItem> bindSelectItems(List<SelectItem> items, Object[] binds) {
		if (items == null || items.isEmpty()) {
			return items;
		}
		final List<SelectItem> out = new ArrayList<>(items.size());
		for (SelectItem item : items) {
			if (item instanceof FunctionSelectItem fn) {
				out.add(new FunctionSelectItem(fn.label(), fn.functionName(), bindFuncArgs(fn.args(), binds)));
			} else if (item instanceof ExistsSelectItem exists) {
				if (hasPlaceholder(exists.subquerySql())
						|| exists.subquery() != null && hasPlaceholder(exists.subquery().sql())) {
					throw new UnsupportedOperationException("EXISTS select item still has placeholders");
				}
				out.add(exists);
			} else {
				out.add(item);
			}
		}
		return List.copyOf(out);
	}

	private static List<FuncArg> bindFuncArgs(List<FuncArg> args, Object[] binds) {
		if (args == null || args.isEmpty()) {
			return args;
		}
		final List<FuncArg> out = new ArrayList<>(args.size());
		for (FuncArg arg : args) {
			if (arg instanceof LiteralFuncArg lit) {
				out.add(new LiteralFuncArg(resolve(lit.value(), binds)));
			} else {
				out.add(arg);
			}
		}
		return List.copyOf(out);
	}

	private static Map<String, Object> bindMap(Map<String, Object> map, Object[] binds) {
		if (map == null) {
			return null;
		}
		if (map.isEmpty()) {
			return map;
		}
		final Map<String, Object> out = new LinkedHashMap<>(map.size());
		for (Map.Entry<String, Object> e : map.entrySet()) {
			out.put(e.getKey(), resolveDeep(e.getValue(), binds));
		}
		return SqlNullSafeCollections.freezeMap(out);
	}

	private static List<Object> bindList(List<Object> values, Object[] binds) {
		final List<Object> out = new ArrayList<>(values.size());
		for (Object v : values) {
			out.add(resolveDeep(v, binds));
		}
		return SqlNullSafeCollections.freezeList(out);
	}

	private static Object resolve(Object value, Object[] binds) {
		if (!(value instanceof SqlBindParam param)) {
			return value;
		}
		if (binds == null || param.index() >= binds.length) {
			throw new IllegalArgumentException(
					"Not enough bind values for prepared placeholder index " + param.index());
		}
		return binds[param.index()];
	}

	/**
	 * Resolve {@link SqlBindParam} and nested SET markers ({@link CaseExpr}, {@link NumericColPlus}, …).
	 */
	private static Object resolveDeep(Object value, Object[] binds) {
		if (value instanceof SqlBindParam) {
			return resolve(value, binds);
		}
		if (value instanceof NumericColPlus plus) {
			return new NumericColPlus(plus.column(), resolveDeep(plus.addend(), binds));
		}
		if (value instanceof CoalesceExpr coal) {
			final List<Object> args = new ArrayList<>(coal.args().size());
			for (Object arg : coal.args()) {
				args.add(resolveDeep(arg, binds));
			}
			return new CoalesceExpr(args);
		}
		if (value instanceof CaseExpr caseExpr) {
			return bindCaseExpr(caseExpr, binds);
		}
		return value;
	}

	private static CaseExpr bindCaseExpr(CaseExpr caseExpr, Object[] binds) {
		final List<CaseBranch> branches = new ArrayList<>(caseExpr.branches().size());
		for (CaseBranch branch : caseExpr.branches()) {
			branches.add(new CaseBranch(
					bindCondition(branch.when(), binds),
					resolveDeep(branch.thenValue(), binds)));
		}
		return new CaseExpr(branches, resolveDeep(caseExpr.elseValueOrNull(), binds));
	}

	private static FilterCondition bindCondition(FilterCondition condition, Object[] binds) {
		if (condition instanceof LogicalOperatorCondition loc) {
			final Object[] values = loc.getValues();
			final Object[] resolved = new Object[values.length];
			for (int i = 0; i < values.length; i++) {
				resolved[i] = resolveDeep(values[i], binds);
			}
			return new LogicalOperatorCondition(loc.getField(), loc.getOperator(), resolved);
		}
		if (condition instanceof NumericPlusComparisonCondition npc) {
			return new NumericPlusComparisonCondition(
					npc.getField(),
					resolveDeep(npc.getAddend(), binds),
					npc.getOperator(),
					resolveDeep(npc.getCompareTo(), binds));
		}
		if (condition instanceof AndCondition and) {
			return new AndCondition(bindCondition(and.getLeft(), binds), bindCondition(and.getRight(), binds));
		}
		if (condition instanceof OrCondition or) {
			return new OrCondition(bindCondition(or.getLeft(), binds), bindCondition(or.getRight(), binds));
		}
		if (condition instanceof NotCondition not) {
			return new NotCondition(bindCondition(not.getChild(), binds));
		}
		return condition;
	}

	/** Resolve LIMIT/OFFSET bind to a non-negative {@link Integer} (or null). */
	private static Object resolvePaging(Object value, Object[] binds) {
		if (value == null) {
			return null;
		}
		final Object resolved = resolve(value, binds);
		if (resolved == null) {
			return Integer.valueOf(0);
		}
		if (resolved instanceof Number n) {
			return Integer.valueOf(Math.max(0, n.intValue()));
		}
		throw new IllegalArgumentException(
				"LIMIT/OFFSET bind must be numeric, got " + resolved.getClass().getSimpleName());
	}

	private static boolean hasPlaceholder(String sql) {
		return sql != null && sql.indexOf(PLACEHOLDER) >= 0;
	}
}
