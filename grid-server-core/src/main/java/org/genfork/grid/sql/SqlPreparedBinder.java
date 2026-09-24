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

import org.genfork.grid.sql.ast.DmlAst.DeleteSql;
import org.genfork.grid.sql.ast.SelectAst.FuncArg;
import org.genfork.grid.sql.ast.SelectAst.FunctionFrom;
import org.genfork.grid.sql.ast.SelectAst.FunctionSelectItem;
import org.genfork.grid.sql.ast.DmlAst.InsertSql;
import org.genfork.grid.sql.ast.SelectAst.LiteralFuncArg;
import org.genfork.grid.sql.ast.DmlAst.MergeSql;
import org.genfork.grid.sql.ast.DmlAst.OnConflict;
import org.genfork.grid.sql.ast.SelectAst.SelectItem;
import org.genfork.grid.sql.ast.SelectAst.SelectSql;
import org.genfork.grid.sql.ast.Stmt;
import org.genfork.grid.sql.ast.DmlAst.UpdateSql;
import org.genfork.grid.sql.ast.SelectAst.WhereSubquery;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Applies EXECUTE binds to a PREPARE-time {@link Stmt} without re-running ANTLR on the body.
 * <p>
 * Text fields that drive residual filters ({@link SelectSql#sql()}, {@code whereSql}) are replaced
 * with the fully materialized statement / fragment when needed. Structured bind slots
 * ({@link SqlBindParam}) are resolved in place. Unsupported shapes fall back to a full body parse.
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
	 * Bind {@code template} for EXECUTE. {@code effectiveSql} is {@link SqlBinds#materialize}
	 * of the prepared body (views already expanded at PREPARE).
	 */
	public static Stmt bind(Stmt template, String effectiveSql, Object[] binds) {
		Objects.requireNonNull(template, "template");
		Objects.requireNonNull(effectiveSql, "effectiveSql");
		try {
			return bindStrict(template, effectiveSql, binds);
		} catch (UnsupportedOperationException ex) {
			return SqlStatementParser.parse(effectiveSql);
		}
	}

	private static Stmt bindStrict(Stmt template, String effectiveSql, Object[] binds) {
		if (template instanceof SelectSql s) {
			return bindSelect(s, effectiveSql, binds);
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

	private static SelectSql bindSelect(SelectSql s, String effectiveSql, Object[] binds) {
		final Object pk = resolve(s.pkValueOrNull(), binds);
		final List<WhereSubquery> subs = bindWhereSubs(s.whereSubqueries());
		final FunctionFrom from = bindFromFunction(s.fromFunctionOrNull(), binds);
		final List<SelectItem> items = bindSelectItems(s.selectItems(), binds);
		return new SelectSql(
				effectiveSql,
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
				s.offset(),
				s.limitOrNull(),
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
			rows.add(List.copyOf(copy));
		}
		final OnConflict conflict = s.onConflictOrNull() == null
				? null
				: new OnConflict(
						s.onConflictOrNull().targetColumns(),
						s.onConflictOrNull().action(),
						bindMap(s.onConflictOrNull().updateSets(), binds)
				);
		return new InsertSql(s.table(), s.columns(), List.copyOf(rows), conflict, s.returning());
	}

	private static UpdateSql bindUpdate(UpdateSql s, Object[] binds) {
		final Object pk = resolve(s.pkValueOrNull(), binds);
		final Map<String, Object> sets = bindMap(s.setLiterals(), binds);
		// Residual keysMatching needs literals in whereSql; false PK-eq on non-PK cols must reparse.
		if (hasPlaceholder(s.whereSql())) {
			throw new UnsupportedOperationException("update where still has placeholders");
		}
		return new UpdateSql(
				s.table(),
				s.rmw(),
				sets,
				s.whereSql(),
				s.pkColumnOrNull(),
				pk,
				s.sourceTableOrNull(),
				s.targetJoinColumnOrNull(),
				s.sourceJoinColumnOrNull(),
				s.returning()
		);
	}

	private static DeleteSql bindDelete(DeleteSql s, Object[] binds) {
		// extractPkEq treats any col=? as PK; residual WHERE on non-PK must full-reparse effective SQL.
		if (hasPlaceholder(s.whereSql())) {
			throw new UnsupportedOperationException("delete where still has placeholders");
		}
		final Object pk = resolve(s.pkValueOrNull(), binds);
		return new DeleteSql(s.table(), s.whereSql(), s.pkColumnOrNull(), pk);
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
			out.put(e.getKey(), resolve(e.getValue(), binds));
		}
		return Map.copyOf(out);
	}

	private static List<Object> bindList(List<Object> values, Object[] binds) {
		final List<Object> out = new ArrayList<>(values.size());
		for (Object v : values) {
			out.add(resolve(v, binds));
		}
		return List.copyOf(out);
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

	private static boolean hasPlaceholder(String sql) {
		return sql != null && sql.indexOf(PLACEHOLDER) >= 0;
	}
}
