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
import org.genfork.grid.serial.LogicalFieldCursor;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.ast.SelectAst.AggregateSelectItem;
import org.genfork.grid.sql.ast.SelectAst.ColumnSelectItem;
import org.genfork.grid.sql.ast.SelectAst.FunctionSelectItem;
import org.genfork.grid.sql.ast.SelectAst.SelectItem;
import org.genfork.grid.sql.ast.SelectAst.WindowSelectItem;
import org.genfork.grid.catalog.TableCatalog.FunctionDef;
import org.genfork.grid.sql.udf.SqlUdfLookup;
import org.genfork.grid.store.TableStore;
import org.genfork.grid.sql.ast.SelectAst.ColumnFuncArg;
import org.genfork.grid.sql.ast.SelectAst.FuncArg;
import org.genfork.grid.sql.ast.SelectAst.LiteralFuncArg;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Assembles SELECT output rows from schema columns ({@link LogicalFieldCursor#project})
 * plus computed window / aggregate values.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SelectProjectionAssembler {
	private SelectProjectionAssembler() {
	}

	/**
	 * Project a stored blob for column items; computed map supplies window/agg labels.
	 */
	public static Object[] assembleFromBytes(
			TableStore store,
			byte[] valueBytes,
			List<SelectItem> items,
			Map<String, Object> computed
	) {
		if (items == null || items.isEmpty()) {
			return LogicalFieldCursor.open(store.schema(), valueBytes).project(null);
		}
		if (hasFunctionItem(items)) {
			final Object[] full = LogicalFieldCursor.open(store.schema(), valueBytes).project(null);
			return assembleFromRow(store, full, items, computed);
		}
		final int[] colOrds = columnOrdinals(store, items);
		final Object[] colValues = colOrds == null
				? null
				: LogicalFieldCursor.open(store.schema(), valueBytes).project(colOrds);
		return assemble(store, null, colValues, items, computed);
	}

	private static boolean hasFunctionItem(List<SelectItem> items) {
		for (SelectItem item : items) {
			if (item instanceof FunctionSelectItem) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Project a full decoded row (schema ordinals) plus computed values.
	 */
	public static Object[] assembleFromRow(
			TableStore store,
			Object[] fullRow,
			List<SelectItem> items,
			Map<String, Object> computed
	) {
		if (items == null || items.isEmpty()) {
			return fullRow;
		}
		return assemble(store, fullRow, null, items, computed);
	}

	public static List<SqlResult.ColumnMeta> metas(TableStore store, List<SelectItem> items, List<String> projection) {
		final String[] parts = SqlInformationSchemaExecutor.splitSchemaTable(store.schema().tableName());
		return metasFromColumns(store.schema().columns(), items, projection, parts[1], parts[0]);
	}

	/**
	 * Metas from an explicit column list (single table or joined working set).
	 */
	public static List<SqlResult.ColumnMeta> metasFromColumns(
			List<ColumnDef> columns,
			List<SelectItem> items,
			List<String> projection
	) {
		return metasFromColumns(columns, items, projection, "", "");
	}

	/**
	 * Metas from an explicit column list with optional catalog table/schema.
	 */
	public static List<SqlResult.ColumnMeta> metasFromColumns(
			List<ColumnDef> columns,
			List<SelectItem> items,
			List<String> projection,
			String table,
			String schema
	) {
		if (items == null || items.isEmpty()) {
			if (projection.size() == 1 && "*".equals(projection.getFirst())) {
				final List<SqlResult.ColumnMeta> all = new ArrayList<>(columns.size());
				for (ColumnDef c : columns) {
					all.add(SqlResult.ColumnMeta.ofCatalog(c.name(), c.type(), c.nullable(), table, schema));
				}
				return all;
			}
			final List<SqlResult.ColumnMeta> metas = new ArrayList<>(projection.size());
			for (String col : projection) {
				final ColumnDef cdef = requireColumn(columns, col);
				metas.add(SqlResult.ColumnMeta.ofCatalog(col, cdef.type(), cdef.nullable(), table, schema));
			}
			return metas;
		}
		final List<SqlResult.ColumnMeta> metas = new ArrayList<>(items.size());
		for (SelectItem item : items) {
			if (item instanceof ColumnSelectItem col) {
				final ColumnDef cdef = requireColumn(columns, col.column());
				metas.add(SqlResult.ColumnMeta.ofCatalog(
						cdef.name(), cdef.type(), cdef.nullable(), table, schema));
			} else if (item instanceof FunctionSelectItem fn) {
				final FunctionDef def = SqlUdfLookup.require(fn.functionName());
				metas.add(SqlResult.ColumnMeta.of(item.label(), def.returnType()));
			} else {
				metas.add(SqlResult.ColumnMeta.of(item.label(), SqlType.DOUBLE));
			}
		}
		return metas;
	}

	public static List<SqlResult.ColumnMeta> metasExprOnly(List<SelectItem> items) {
		if (items == null || items.isEmpty()) {
			throw new IllegalArgumentException("expression SELECT requires select items");
		}
		final List<SqlResult.ColumnMeta> metas = new ArrayList<>(items.size());
		for (SelectItem item : items) {
			if (!(item instanceof FunctionSelectItem fn)) {
				throw new IllegalArgumentException(
						"expression SELECT without FROM supports UDF calls only, got: " + item);
			}
			final FunctionDef def = SqlUdfLookup.require(fn.functionName());
			metas.add(SqlResult.ColumnMeta.of(item.label(), def.returnType()));
		}
		return metas;
	}

	/**
	 * Evaluate bare {@code SELECT fn(...)} into a single output row (literal args only).
	 */
	public static Object[] assembleExprOnly(List<SelectItem> items) {
		if (items == null || items.isEmpty()) {
			throw new IllegalArgumentException("expression SELECT requires select items");
		}
		final Object[] out = new Object[items.size()];
		for (int i = 0; i < items.size(); i++) {
			final SelectItem item = items.get(i);
			if (!(item instanceof FunctionSelectItem fn)) {
				throw new IllegalArgumentException(
						"expression SELECT without FROM supports UDF calls only, got: " + item);
			}
			out[i] = evalUdf(fn, name -> {
				throw new IllegalArgumentException(
						"expression SELECT UDF args must be literals, got column: " + name);
			});
		}
		return out;
	}

	public static Object[] assembleFromRowColumns(
			List<ColumnDef> columns,
			Object[] fullRow,
			List<SelectItem> items,
			Map<String, Object> computed
	) {
		if (items == null || items.isEmpty()) {
			return fullRow;
		}
		final Object[] out = new Object[items.size()];
		for (int i = 0; i < items.size(); i++) {
			final SelectItem item = items.get(i);
			if (item instanceof ColumnSelectItem col) {
				out[i] = fullRow[indexOf(columns, col.column())];
			} else if (item instanceof AggregateSelectItem || item instanceof WindowSelectItem) {
				out[i] = computed == null ? null : computed.get(item.label());
			} else if (item instanceof FunctionSelectItem fn) {
				out[i] = evalUdf(fn, name -> fullRow[indexOf(columns, name)]);
			} else {
				throw new IllegalArgumentException("unknown select item: " + item);
			}
		}
		return out;
	}

	private static ColumnDef requireColumn(List<ColumnDef> columns, String name) {
		for (ColumnDef c : columns) {
			if (c.name().equalsIgnoreCase(name)) {
				return c;
			}
		}
		throw new IllegalArgumentException("unknown column: " + name);
	}

	private static int indexOf(List<ColumnDef> columns, String name) {
		for (int i = 0; i < columns.size(); i++) {
			if (columns.get(i).name().equalsIgnoreCase(name)) {
				return i;
			}
		}
		throw new IllegalArgumentException("unknown column: " + name);
	}

	private static Object[] assemble(
			TableStore store,
			Object[] fullRow,
			Object[] projectedCols,
			List<SelectItem> items,
			Map<String, Object> computed
	) {
		final Object[] out = new Object[items.size()];
		int colCursor = 0;
		for (int i = 0; i < items.size(); i++) {
			final SelectItem item = items.get(i);
			if (item instanceof ColumnSelectItem col) {
				if (projectedCols != null) {
					out[i] = projectedCols[colCursor++];
				} else {
					out[i] = fullRow[store.schema().requireColumn(col.column()).ordinal()];
				}
			} else if (item instanceof AggregateSelectItem || item instanceof WindowSelectItem) {
				out[i] = computed == null ? null : computed.get(item.label());
			} else if (item instanceof FunctionSelectItem fn) {
				out[i] = evalUdf(fn, name -> {
					if (fullRow != null) {
						return fullRow[store.schema().requireColumn(name).ordinal()];
					}
					throw new IllegalStateException("UDF projection requires full row values");
				});
			} else {
				throw new IllegalArgumentException("unknown select item: " + item);
			}
		}
		return out;
	}

	private static Object evalUdf(FunctionSelectItem fn, java.util.function.Function<String, Object> columnValue) {
		final FunctionDef def = SqlUdfLookup.require(fn.functionName());
		final Object[] args = new Object[fn.args().size()];
		for (int i = 0; i < fn.args().size(); i++) {
			final FuncArg arg = fn.args().get(i);
			if (arg instanceof ColumnFuncArg c) {
				args[i] = columnValue.apply(c.column());
			} else if (arg instanceof LiteralFuncArg lit) {
				args[i] = lit.value();
			} else {
				throw new IllegalArgumentException("unknown UDF arg: " + arg);
			}
		}
		return def.udf().apply(args);
	}

	/** Ordinals for column items only, in select-list order; null when no column items. */
	private static int[] columnOrdinals(TableStore store, List<SelectItem> items) {
		int n = 0;
		for (SelectItem item : items) {
			if (item instanceof ColumnSelectItem) {
				n++;
			}
		}
		if (n == 0) {
			return null;
		}
		final int[] ords = new int[n];
		int i = 0;
		for (SelectItem item : items) {
			if (item instanceof ColumnSelectItem col) {
				ords[i++] = store.schema().requireColumn(col.column()).ordinal();
			}
		}
		return ords;
	}
}
