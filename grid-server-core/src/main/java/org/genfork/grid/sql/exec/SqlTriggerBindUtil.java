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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.genfork.grid.catalog.ColumnDef;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.serial.LogicalFieldCursor;
import org.genfork.grid.sql.SqlBinds;
import org.genfork.grid.sql.SqlBindParam;
import org.genfork.grid.sql.SqlTriggerRowRef;
import org.genfork.grid.sql.ast.DmlAst.DeleteSql;
import org.genfork.grid.sql.ast.DmlAst.InsertSql;
import org.genfork.grid.sql.ast.DmlAst.MergeSql;
import org.genfork.grid.sql.ast.DmlAst.OnConflict;
import org.genfork.grid.sql.ast.DmlAst.UpdateSql;
import org.genfork.grid.sql.ast.SelectAst.FuncArg;
import org.genfork.grid.sql.ast.SelectAst.FunctionFrom;
import org.genfork.grid.sql.ast.SelectAst.FunctionSelectItem;
import org.genfork.grid.sql.ast.SelectAst.LiteralFuncArg;
import org.genfork.grid.sql.ast.SelectAst.SelectItem;
import org.genfork.grid.sql.ast.SelectAst.SelectSql;
import org.genfork.grid.sql.ast.Stmt;

/**
 * Resolve {@link SqlTriggerRowRef} slots from OLD/NEW wire blobs into concrete AST literals.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlTriggerBindUtil {
	private static final String ERR_MISSING_BLOB = "trigger row blob missing for ";

	private SqlTriggerBindUtil() {
	}

	public static boolean hasRowRefs(Stmt stmt) {
		if (stmt == null) {
			return false;
		}
		if (stmt instanceof InsertSql s) {
			if (s.rows() != null) {
				for (List<Object> row : s.rows()) {
					for (Object v : row) {
						if (v instanceof SqlTriggerRowRef) {
							return true;
						}
					}
				}
			}
			if (s.onConflictOrNull() != null && s.onConflictOrNull().updateSets() != null) {
				for (Object v : s.onConflictOrNull().updateSets().values()) {
					if (v instanceof SqlTriggerRowRef) {
						return true;
					}
				}
			}
			return false;
		}
		if (stmt instanceof DeleteSql s) {
			return s.pkValueOrNull() instanceof SqlTriggerRowRef || containsRefText(s.whereSql());
		}
		if (stmt instanceof UpdateSql s) {
			if (s.pkValueOrNull() instanceof SqlTriggerRowRef || containsRefText(s.whereSql())) {
				return true;
			}
			if (s.setLiterals() != null) {
				for (Object v : s.setLiterals().values()) {
					if (v instanceof SqlTriggerRowRef) {
						return true;
					}
				}
			}
			return false;
		}
		if (stmt instanceof MergeSql s) {
			if (s.sourceRowOrNull() != null) {
				for (Object v : s.sourceRowOrNull()) {
					if (v instanceof SqlTriggerRowRef) {
						return true;
					}
				}
			}
			if (s.insertValuesOrNull() != null) {
				for (Object v : s.insertValuesOrNull()) {
					if (v instanceof SqlTriggerRowRef) {
						return true;
					}
				}
			}
			if (s.matchedSetsOrNull() != null) {
				for (Object v : s.matchedSetsOrNull().values()) {
					if (v instanceof SqlTriggerRowRef) {
						return true;
					}
				}
			}
			return false;
		}
		if (stmt instanceof SelectSql s) {
			return s.pkValueOrNull() instanceof SqlTriggerRowRef
					|| containsRefText(s.sql())
					|| hasRefs(s.selectItems())
					|| hasRefs(s.fromFunctionOrNull());
		}
		return false;
	}

	public static Stmt bindTrigger(
			Stmt template,
			TableSchema triggerTableSchema,
			byte[] oldBlob,
			byte[] newBlob
	) {
		Objects.requireNonNull(template, "template");
		if (template instanceof DeleteSql s) {
			return bindDelete(s, triggerTableSchema, oldBlob, newBlob);
		}
		if (template instanceof InsertSql s) {
			return bindInsert(s, triggerTableSchema, oldBlob, newBlob);
		}
		if (template instanceof UpdateSql s) {
			return bindUpdate(s, triggerTableSchema, oldBlob, newBlob);
		}
		if (template instanceof MergeSql s) {
			return bindMerge(s, triggerTableSchema, oldBlob, newBlob);
		}
		if (template instanceof SelectSql s) {
			return bindSelect(s, triggerTableSchema, oldBlob, newBlob);
		}
		return template;
	}

	private static DeleteSql bindDelete(
			DeleteSql s,
			TableSchema schema,
			byte[] oldBlob,
			byte[] newBlob
	) {
		final Object pkRaw = s.pkValueOrNull();
		final Object pk = resolveValue(pkRaw, schema, oldBlob, newBlob);
		String where = s.whereSql();
		if (pkRaw instanceof SqlTriggerRowRef || containsRefText(where)) {
			where = rewriteWhereText(where, schema, oldBlob, newBlob);
		}
		final String pkCol = s.pkColumnOrNull();
		if (pkCol != null && schema.pkColumn() != null
				&& !pkCol.equalsIgnoreCase(schema.pkColumn().name())
				&& pk != null) {
			final String residual = pkCol + " = " + SqlBinds.toSqlLiteral(pk);
			return new DeleteSql(s.table(), residual, null, null);
		}
		return new DeleteSql(s.table(), where, pkCol, pk instanceof SqlBindParam ? null : pk);
	}

	private static InsertSql bindInsert(
			InsertSql s,
			TableSchema schema,
			byte[] oldBlob,
			byte[] newBlob
	) {
		final List<List<Object>> rows = new ArrayList<>();
		if (s.rows() != null) {
			for (List<Object> row : s.rows()) {
				final List<Object> next = new ArrayList<>(row.size());
				for (Object v : row) {
					next.add(resolveValue(v, schema, oldBlob, newBlob));
				}
				rows.add(List.copyOf(next));
			}
		}
		OnConflict conflict = s.onConflictOrNull();
		if (conflict != null && conflict.updateSets() != null && !conflict.updateSets().isEmpty()) {
			final Map<String, Object> sets = bindMap(conflict.updateSets(), schema, oldBlob, newBlob);
			conflict = new OnConflict(conflict.targetColumns(), conflict.action(), sets);
		}
		return new InsertSql(
				s.table(),
				s.columns(),
				List.copyOf(rows),
				conflict,
				s.returning());
	}

	private static UpdateSql bindUpdate(
			UpdateSql s,
			TableSchema schema,
			byte[] oldBlob,
			byte[] newBlob
	) {
		final Object pk = resolveValue(s.pkValueOrNull(), schema, oldBlob, newBlob);
		final Map<String, Object> sets = bindMap(s.setLiterals(), schema, oldBlob, newBlob);
		String where = s.whereSql();
		if (containsRefText(where)) {
			where = rewriteWhereText(where, schema, oldBlob, newBlob);
		}
		return new UpdateSql(
				s.table(),
				s.rmw(),
				sets,
				where,
				s.pkColumnOrNull(),
				pk,
				s.sourceTableOrNull(),
				s.targetJoinColumnOrNull(),
				s.sourceJoinColumnOrNull(),
				s.returning());
	}

	private static MergeSql bindMerge(
			MergeSql s,
			TableSchema schema,
			byte[] oldBlob,
			byte[] newBlob
	) {
		final List<Object> sourceRow = s.sourceRowOrNull() == null
				? null
				: bindList(s.sourceRowOrNull(), schema, oldBlob, newBlob);
		final Map<String, Object> matched = s.matchedSetsOrNull() == null
				? null
				: bindMap(s.matchedSetsOrNull(), schema, oldBlob, newBlob);
		final List<Object> insertVals = s.insertValuesOrNull() == null
				? null
				: bindList(s.insertValuesOrNull(), schema, oldBlob, newBlob);
		return new MergeSql(
				s.targetTable(),
				s.sourceTableOrNull(),
				sourceRow,
				s.targetOnCol(),
				s.sourceOnCol(),
				matched,
				s.insertColumnsOrNull(),
				insertVals);
	}

	private static SelectSql bindSelect(
			SelectSql s,
			TableSchema schema,
			byte[] oldBlob,
			byte[] newBlob
	) {
		final Object pk = resolveValue(s.pkValueOrNull(), schema, oldBlob, newBlob);
		return new SelectSql(
				rewriteWhereText(s.sql(), schema, oldBlob, newBlob),
				s.table(),
				s.projection(),
				bindSelectItems(s.selectItems(), schema, oldBlob, newBlob),
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
				s.whereSubqueries(),
				bindFunction(s.fromFunctionOrNull(), schema, oldBlob, newBlob),
				s.forUpdate(),
				s.skipLocked());
	}

	private static List<SelectItem> bindSelectItems(
			List<SelectItem> items,
			TableSchema schema,
			byte[] oldBlob,
			byte[] newBlob
	) {
		if (items == null || items.isEmpty()) {
			return items;
		}
		final List<SelectItem> out = new ArrayList<>(items.size());
		for (SelectItem item : items) {
			if (item instanceof FunctionSelectItem function) {
				out.add(new FunctionSelectItem(
						function.label(),
						function.functionName(),
						bindFunctionArgs(function.args(), schema, oldBlob, newBlob)));
			} else {
				out.add(item);
			}
		}
		return List.copyOf(out);
	}

	private static FunctionFrom bindFunction(
			FunctionFrom function,
			TableSchema schema,
			byte[] oldBlob,
			byte[] newBlob
	) {
		return function == null ? null : new FunctionFrom(
				function.functionName(),
				bindFunctionArgs(function.args(), schema, oldBlob, newBlob),
				function.aliasOrNull());
	}

	private static List<FuncArg> bindFunctionArgs(
			List<FuncArg> args,
			TableSchema schema,
			byte[] oldBlob,
			byte[] newBlob
	) {
		final List<FuncArg> out = new ArrayList<>(args.size());
		for (FuncArg arg : args) {
			if (arg instanceof LiteralFuncArg literal) {
				out.add(new LiteralFuncArg(resolveValue(literal.value(), schema, oldBlob, newBlob)));
			} else {
				out.add(arg);
			}
		}
		return List.copyOf(out);
	}

	private static Map<String, Object> bindMap(
			Map<String, Object> in,
			TableSchema schema,
			byte[] oldBlob,
			byte[] newBlob
	) {
		if (in == null || in.isEmpty()) {
			return in;
		}
		final Map<String, Object> out = new LinkedHashMap<>(in.size());
		for (Map.Entry<String, Object> e : in.entrySet()) {
			out.put(e.getKey(), resolveValue(e.getValue(), schema, oldBlob, newBlob));
		}
		return out;
	}

	private static List<Object> bindList(
			List<Object> in,
			TableSchema schema,
			byte[] oldBlob,
			byte[] newBlob
	) {
		final List<Object> out = new ArrayList<>(in.size());
		for (Object v : in) {
			out.add(resolveValue(v, schema, oldBlob, newBlob));
		}
		return out;
	}

	static Object resolveValue(
			Object value,
			TableSchema schema,
			byte[] oldBlob,
			byte[] newBlob
	) {
		if (!(value instanceof SqlTriggerRowRef ref)) {
			return value;
		}
		final byte[] blob = ref.oldRow() ? oldBlob : newBlob;
		if (blob == null) {
			throw new IllegalStateException(ERR_MISSING_BLOB + ref);
		}
		final ColumnDef col = schema.column(ref.column());
		if (col == null) {
			throw new IllegalArgumentException("unknown trigger column: " + ref.column());
		}
		return LogicalFieldCursor.open(schema, blob).read(col.ordinal());
	}

	private static boolean containsRefText(String sql) {
		if (sql == null || sql.isEmpty()) {
			return false;
		}
		boolean quoted = false;
		for (int index = 0; index < sql.length(); index++) {
			final char current = sql.charAt(index);
			if (current == '\'') {
				if (quoted && index + 1 < sql.length() && sql.charAt(index + 1) == '\'') {
					index++;
				} else {
					quoted = !quoted;
				}
			} else if (!quoted && (matchRef(sql, index, "OLD.") || matchRef(sql, index, "NEW."))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Replace OLD.col/NEW.col in a WHERE fragment using schema ordinals (ANTLR already validated body).
	 */
	private static String rewriteWhereText(
			String whereSql,
			TableSchema schema,
			byte[] oldBlob,
			byte[] newBlob
	) {
		if (whereSql == null || whereSql.isEmpty() || !containsRefText(whereSql)) {
			return whereSql;
		}
		final StringBuilder out = new StringBuilder(whereSql.length());
		int i = 0;
		boolean quoted = false;
		while (i < whereSql.length()) {
			final char current = whereSql.charAt(i);
			if (current == '\'') {
				out.append(current);
				if (quoted && i + 1 < whereSql.length() && whereSql.charAt(i + 1) == '\'') {
					out.append('\'');
					i += 2;
					continue;
				}
				quoted = !quoted;
				i++;
				continue;
			}
			if (!quoted && (matchRef(whereSql, i, "OLD.") || matchRef(whereSql, i, "NEW."))) {
				final boolean old = whereSql.regionMatches(true, i, "OLD.", 0, 4);
				i += 4;
				final int start = i;
				while (i < whereSql.length() && isIdent(whereSql.charAt(i))) {
					i++;
				}
				final String col = whereSql.substring(start, i);
				final Object v = resolveValue(new SqlTriggerRowRef(old, col), schema, oldBlob, newBlob);
				out.append(SqlBinds.toSqlLiteral(v));
				continue;
			}
			out.append(whereSql.charAt(i));
			i++;
		}
		return out.toString();
	}

	private static boolean matchRef(String sql, int i, String prefix) {
		return (i == 0 || !isIdent(sql.charAt(i - 1)))
				&& sql.regionMatches(true, i, prefix, 0, prefix.length());
	}

	private static boolean isIdent(char c) {
		return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
	}

	private static boolean hasRefs(List<SelectItem> items) {
		if (items == null) {
			return false;
		}
		for (SelectItem item : items) {
			if (item instanceof FunctionSelectItem function && hasRefs(function.args())) {
				return true;
			}
		}
		return false;
	}

	private static boolean hasRefs(FunctionFrom function) {
		return function != null && hasRefs(function.args());
	}

	private static boolean hasRefs(Iterable<FuncArg> args) {
		for (FuncArg arg : args) {
			if (arg instanceof LiteralFuncArg literal && literal.value() instanceof SqlTriggerRowRef) {
				return true;
			}
		}
		return false;
	}
}