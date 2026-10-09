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

import org.genfork.grid.catalog.ColumnDef;
import org.genfork.grid.catalog.SqlType;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.sql.SqlBuiltinNames;
import org.genfork.grid.sql.ast.SelectAst.AggregateSelectItem;
import org.genfork.grid.sql.ast.SelectAst.ColumnFuncArg;
import org.genfork.grid.sql.ast.SelectAst.ColumnSelectItem;
import org.genfork.grid.sql.ast.SelectAst.ExistsSelectItem;
import org.genfork.grid.sql.ast.SelectAst.FuncArg;
import org.genfork.grid.sql.ast.SelectAst.FunctionSelectItem;
import org.genfork.grid.sql.ast.SelectAst.LiteralFuncArg;
import org.genfork.grid.sql.ast.SelectAst.LiteralSelectItem;
import org.genfork.grid.sql.ast.SelectAst.SelectItem;
import org.genfork.grid.sql.ast.SelectAst.SelectSql;
import org.genfork.grid.sql.ast.SelectAst.WindowSelectItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Infer {@link ColumnDef} list for {@code CREATE MATERIALIZED VIEW} from parsed {@link SelectItem}s
 * (window / agg / COALESCE / literals + base or joined columns).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.1
 */
public final class SqlMaterializedViewSchemaUtil {
	private static final String RANK = "RANK";
	private static final String DENSE_RANK = "DENSE_RANK";
	private static final String ROW_NUMBER = "ROW_NUMBER";
	private static final String LAG = "LAG";
	private static final String LEAD = "LEAD";
	private static final String ERR_EMPTY = "MATERIALIZED VIEW projection empty";
	private static final String ERR_STAR_ITEMS =
			"MATERIALIZED VIEW SELECT * with computed items requires explicit projection";

	private SqlMaterializedViewSchemaUtil() {
	}

	/**
	 * Build MV columns from select-list items resolved against joined or single-table source columns.
	 *
	 * @param sourceColumns columns available from JOIN schema or base table
	 * @param select        parsed MV body SELECT
	 * @return ordered column defs (first = PK)
	 */
	public static List<ColumnDef> resolveColumns(List<ColumnDef> sourceColumns, SelectSql select) {
		final List<String> proj = select.projection();
		final boolean star = proj.size() == 1 && "*".equals(proj.getFirst());
		final List<SelectItem> items = select.selectItems();
		if (star) {
			if (items != null && !items.isEmpty()) {
				throw new IllegalArgumentException(ERR_STAR_ITEMS);
			}
			if (sourceColumns.isEmpty()) {
				throw new IllegalArgumentException(ERR_EMPTY);
			}
			return copyAsMvColumns(sourceColumns);
		}
		if (items != null && !items.isEmpty()) {
			final List<ColumnDef> out = new ArrayList<>(items.size());
			for (int i = 0; i < items.size(); i++) {
				out.add(columnForItem(items.get(i), sourceColumns, i));
			}
			return out;
		}
		final List<ColumnDef> out = new ArrayList<>(proj.size());
		for (int i = 0; i < proj.size(); i++) {
			final ColumnDef found = findColumn(sourceColumns, proj.get(i));
			if (found == null) {
				throw new IllegalArgumentException("unknown column in MATERIALIZED VIEW: " + proj.get(i));
			}
			out.add(new ColumnDef(found.name(), found.type(), found.nullable(), i, i == 0, false));
		}
		if (out.isEmpty()) {
			throw new IllegalArgumentException(ERR_EMPTY);
		}
		return out;
	}

	/**
	 * Apply resolved columns onto a {@link TableSchema.Builder} (first column = PK).
	 */
	public static void applyToBuilder(TableSchema.Builder builder, List<ColumnDef> columns) {
		for (int i = 0; i < columns.size(); i++) {
			final ColumnDef col = columns.get(i);
			if (i == 0) {
				builder.primaryKey(col.name(), col.type());
			} else {
				builder.column(col.name(), col.type(), col.nullable());
			}
		}
	}

	private static List<ColumnDef> copyAsMvColumns(List<ColumnDef> sourceColumns) {
		final List<ColumnDef> out = new ArrayList<>(sourceColumns.size());
		for (int i = 0; i < sourceColumns.size(); i++) {
			final ColumnDef c = sourceColumns.get(i);
			out.add(new ColumnDef(c.name(), c.type(), c.nullable(), i, i == 0, false));
		}
		return out;
	}

	private static ColumnDef columnForItem(SelectItem item, List<ColumnDef> sourceColumns, int ordinal) {
		final boolean pk = ordinal == 0;
		return switch (item) {
			case ColumnSelectItem col -> {
				final ColumnDef found = findColumn(sourceColumns, col.column());
				if (found == null) {
					throw new IllegalArgumentException(
							"unknown column in MATERIALIZED VIEW: " + col.column());
				}
				yield new ColumnDef(col.label(), found.type(), found.nullable(), ordinal, pk, false);
			}
			case WindowSelectItem win -> new ColumnDef(
					win.label(),
					windowType(win, sourceColumns),
					false,
					ordinal,
					pk,
					false);
			case AggregateSelectItem agg -> new ColumnDef(
					agg.label(),
					aggregateType(agg, sourceColumns),
					true,
					ordinal,
					pk,
					false);
			case FunctionSelectItem fn -> new ColumnDef(
					fn.label(),
					functionType(fn, sourceColumns),
					true,
					ordinal,
					pk,
					false);
			case LiteralSelectItem lit -> new ColumnDef(
					lit.label(),
					literalType(lit.value()),
					true,
					ordinal,
					pk,
					false);
			case ExistsSelectItem ignored -> new ColumnDef(
					item.label(),
					SqlType.BOOLEAN,
					false,
					ordinal,
					pk,
					false);
		};
	}

	private static SqlType windowType(WindowSelectItem win, List<ColumnDef> sourceColumns) {
		final String func = win.func() == null ? "" : win.func().toUpperCase(Locale.ROOT);
		if (RANK.equals(func) || DENSE_RANK.equals(func) || ROW_NUMBER.equals(func)) {
			return SqlType.BIGINT;
		}
		if (LAG.equals(func) || LEAD.equals(func)) {
			final ColumnDef src = findColumn(sourceColumns, win.valueColumnOrNull());
			return src != null ? src.type() : SqlType.BIGINT;
		}
		return SqlType.BIGINT;
	}

	private static SqlType aggregateType(AggregateSelectItem agg, List<ColumnDef> sourceColumns) {
		if (agg.countStar()) {
			return SqlType.BIGINT;
		}
		if (agg.avg()) {
			return SqlType.DOUBLE;
		}
		if (agg.sumColumnOrNull() != null) {
			final ColumnDef src = findColumn(sourceColumns, agg.sumColumnOrNull());
			if (src != null && (src.type() == SqlType.DOUBLE || src.type() == SqlType.INT)) {
				return src.type() == SqlType.INT ? SqlType.BIGINT : SqlType.DOUBLE;
			}
			return SqlType.DOUBLE;
		}
		if (agg.minAgg() || agg.maxAgg()) {
			final ColumnDef src = findColumn(sourceColumns, agg.sumColumnOrNull());
			return src != null ? src.type() : SqlType.BIGINT;
		}
		return SqlType.BIGINT;
	}

	private static SqlType functionType(FunctionSelectItem fn, List<ColumnDef> sourceColumns) {
		final String name = fn.functionName() == null ? "" : fn.functionName().toUpperCase(Locale.ROOT);
		if (SqlBuiltinNames.COALESCE.sqlToken().equalsIgnoreCase(name)) {
			for (FuncArg arg : fn.args()) {
				if (arg instanceof ColumnFuncArg col) {
					final ColumnDef found = findColumn(sourceColumns, col.column());
					if (found != null) {
						return found.type();
					}
				}
				if (arg instanceof LiteralFuncArg lit) {
					return literalType(lit.value());
				}
			}
			return SqlType.VARCHAR;
		}
		return SqlType.VARCHAR;
	}

	private static SqlType literalType(Object value) {
		if (value instanceof Long) {
			return SqlType.BIGINT;
		}
		if (value instanceof Integer) {
			return SqlType.INT;
		}
		if (value instanceof Double || value instanceof Float) {
			return SqlType.DOUBLE;
		}
		if (value instanceof Boolean) {
			return SqlType.BOOLEAN;
		}
		return SqlType.VARCHAR;
	}

	private static ColumnDef findColumn(List<ColumnDef> sourceColumns, String name) {
		if (name == null || name.isBlank()) {
			return null;
		}
		for (ColumnDef c : sourceColumns) {
			if (c.name().equalsIgnoreCase(name)) {
				return c;
			}
		}
		return null;
	}
}
