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
import java.util.List;

import org.genfork.grid.catalog.ColumnDef;
import org.genfork.grid.catalog.SqlType;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.ast.SelectAst.AggregateSelectItem;
import org.genfork.grid.sql.ast.SelectAst.ColumnFuncArg;
import org.genfork.grid.sql.ast.SelectAst.ColumnSelectItem;
import org.genfork.grid.sql.ast.SelectAst.FuncArg;
import org.genfork.grid.sql.ast.SelectAst.FunctionSelectItem;
import org.genfork.grid.sql.ast.SelectAst.LiteralFuncArg;
import org.genfork.grid.sql.ast.SelectAst.SelectItem;
import org.genfork.grid.sql.ast.SelectAst.SelectSql;
import org.genfork.grid.store.TableStore;

/**
 * SELECT projection / meta / aggregate / TVF / subquery-literal helpers for {@link SqlQueryExecutor}.
 * <p>
 * Column metas and ordinals stay on catalog {@link ColumnDef}; DISTINCT delegates to
 * {@link SqlWireAggOps}. Subquery helpers emit SPI literals for {@code FilterCondition} only.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlProjectionOps {
	private SqlProjectionOps() {
	}

	static Object[] resolveTvfArgs(List<FuncArg> args) {
		if (args == null || args.isEmpty()) {
			return new Object[0];
		}
		final Object[] out = new Object[args.size()];
		for (int i = 0; i < args.size(); i++) {
			final FuncArg arg = args.get(i);
			if (arg instanceof LiteralFuncArg lit) {
				out[i] = lit.value();
			} else if (arg instanceof ColumnFuncArg col) {
				throw new IllegalArgumentException(
						"FROM table UDF args must be literals, got column: " + col.column());
			} else {
				out[i] = null;
			}
		}
		return out;
	}

	static boolean hasFunctionProjection(SelectSql s) {
		if (s.selectItems() == null) {
			return false;
		}
		for (SelectItem item : s.selectItems()) {
			if (item instanceof FunctionSelectItem) {
				return true;
			}
		}
		return false;
	}

	static Object[] inListValues(SqlResult sub) {
		if (sub.rows().isEmpty()) {
			return new Object[]{null};
		}
		final List<Object> values = new ArrayList<>(sub.rows().size());
		for (Object[] row : sub.rows()) {
			if (row == null || row.length == 0) {
				continue;
			}
			values.add(row[0]);
		}
		if (values.isEmpty()) {
			return new Object[]{null};
		}
		return values.toArray();
	}

	static Object scalarSubqueryValue(SqlResult sub) {
		if (sub.rows().size() != 1 || sub.rows().getFirst().length < 1) {
			throw new IllegalArgumentException(
					"scalar subquery must return exactly one row and one column");
		}
		return sub.rows().getFirst()[0];
	}

	static String firstAggregateLabel(SelectSql s) {
		if (s.selectItems() != null) {
			for (SelectItem item : s.selectItems()) {
				if (item instanceof AggregateSelectItem agg) {
					return agg.label();
				}
			}
		}
		return s.projection().isEmpty() ? "AGG" : s.projection().getFirst();
	}

	static Object aggregateValue(TableStore store, List<Object[]> rows, SelectSql sql) {
		return aggregateValue(store.schema().columns(), rows, sql);
	}

	static Object aggregateValue(List<ColumnDef> cols, List<Object[]> rows, SelectSql sql) {
		if (sql.countStar()) {
			return (double) rows.size();
		}

		final int colOrd = ordinalOf(cols, sql.sumColumnOrNull());
		if (sql.minAgg() || sql.maxAgg()) {
			Double best = null;
			for (Object[] row : rows) {
				final Object raw = row[colOrd];
				if (raw == null) {
					continue;
				}
				final double v = raw instanceof Number num
						? num.doubleValue()
						: Double.parseDouble(String.valueOf(raw));
				if (best == null) {
					best = v;
				} else if (sql.minAgg()) {
					best = Math.min(best, v);
				} else {
					best = Math.max(best, v);
				}
			}
			return best == null ? 0d : best;
		}

		double sum = 0d;
		long n = 0L;
		for (Object[] row : rows) {
			final Object raw = row[colOrd];
			if (raw == null) {
				continue;
			}

			sum += raw instanceof Number num ? num.doubleValue() : Double.parseDouble(String.valueOf(raw));
			n++;
		}

		if (sql.avg()) {
			return n == 0L ? 0d : sum / n;
		}

		return sum;
	}

	static int ordinalOf(List<ColumnDef> cols, String name) {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("column required");
		}
		for (int i = 0; i < cols.size(); i++) {
			if (cols.get(i).name().equalsIgnoreCase(name)) {
				return i;
			}
		}
		throw new IllegalArgumentException("unknown column: " + name);
	}

	static List<SqlResult.ColumnMeta> starMetas(List<ColumnDef> cols) {
		return starMetas(cols, "", "");
	}

	static List<SqlResult.ColumnMeta> starMetas(List<ColumnDef> cols, String table, String schema) {
		final List<SqlResult.ColumnMeta> metas = new ArrayList<>(cols.size());
		for (ColumnDef c : cols) {
			metas.add(SqlResult.ColumnMeta.ofCatalog(c.name(), c.type(), c.nullable(), table, schema));
		}
		return metas;
	}

	static List<Object[]> applyDistinct(SelectSql s, List<Object[]> rows) {
		if (!s.distinct()) {
			return rows;
		}
		return SqlWireAggOps.distinctWire(rows);
	}

	static int indexOfColumn(List<ColumnDef> cols, String name) {
		for (int i = 0; i < cols.size(); i++) {
			if (cols.get(i).name().equalsIgnoreCase(name)) {
				return i;
			}
		}
		throw new IllegalArgumentException("unknown column: " + name);
	}

	static ColumnDef requireWorkingColumn(List<ColumnDef> cols, String name) {
		for (ColumnDef c : cols) {
			if (c.name().equalsIgnoreCase(name)) {
				return c;
			}
		}
		throw new IllegalArgumentException("unknown join column: " + name);
	}

	static Object columnValue(List<SqlResult.ColumnMeta> metas, Object[] row, String field) {
		if (field == null || metas == null || row == null) {
			return null;
		}
		for (int i = 0; i < metas.size(); i++) {
			if (metas.get(i).name().equalsIgnoreCase(field)) {
				return row[i];
			}
		}
		return null;
	}

	static List<SqlResult.ColumnMeta> joinMetas(
			List<ColumnDef> cols,
			List<String> projection,
			boolean star,
			List<SelectItem> selectItems
	) {
		if (star) {
			return starMetas(cols);
		}

		final List<SqlResult.ColumnMeta> metas = new ArrayList<>(projection.size());
		for (int i = 0; i < projection.size(); i++) {
			final String col = projection.get(i);
			if (selectItems != null && i < selectItems.size() && selectItems.get(i) instanceof ColumnSelectItem csi) {
				SqlType type = SqlType.VARCHAR;
				boolean nullable = true;
				boolean foundType = false;
				for (ColumnDef cdef : cols) {
					if (cdef.name().equalsIgnoreCase(csi.column())) {
						type = cdef.type();
						nullable = cdef.nullable();
						foundType = true;
						break;
					}
				}
				if (foundType) {
					metas.add(SqlResult.ColumnMeta.ofCatalog(csi.label(), type, nullable, "", ""));
				} else {
					metas.add(SqlResult.ColumnMeta.of(csi.label(), type));
				}
				continue;
			}
			boolean found = false;
			for (ColumnDef cdef : cols) {
				if (cdef.name().equalsIgnoreCase(col)
						|| (col.indexOf('.') > 0
						&& cdef.name().equalsIgnoreCase(col.substring(col.indexOf('.') + 1)))) {
					metas.add(SqlResult.ColumnMeta.ofCatalog(col, cdef.type(), cdef.nullable(), "", ""));
					found = true;
					break;
				}
			}
			if (!found) {
				throw new IllegalArgumentException("Unknown column in projection: " + col);
			}
		}
		return metas;
	}

	static List<SqlResult.ColumnMeta> columnMetas(TableStore store, List<String> projection) {
		final String[] parts = SqlInformationSchemaExecutor.splitSchemaTable(store.schema().tableName());
		final String schema = parts[0];
		final String table = parts[1];
		if (projection.size() == 1 && "*".equals(projection.getFirst())) {
			return store.schema().columns().stream()
					.map(col -> SqlResult.ColumnMeta.ofCatalog(
							col.name(), col.type(), col.nullable(), table, schema))
					.toList();
		}

		final List<SqlResult.ColumnMeta> metas = new ArrayList<>(projection.size());
		for (String col : projection) {
			final ColumnDef cdef = store.schema().requireColumn(col);
			metas.add(SqlResult.ColumnMeta.ofCatalog(
					cdef.name(), cdef.type(), cdef.nullable(), table, schema));
		}

		return metas;
	}
}
