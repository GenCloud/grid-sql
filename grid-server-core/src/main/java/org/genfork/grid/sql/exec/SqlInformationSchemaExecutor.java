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
import org.genfork.grid.catalog.FkDef;
import org.genfork.grid.catalog.IndexDef;
import org.genfork.grid.catalog.SqlType;
import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.catalog.TableCatalog.ViewDef;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.mem.index.IndexType;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.ast.SelectAst.SelectSql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Virtual information_schema catalog views for tooling (JDBC / DBeaver).
 * <p>
 * Invoked only after ANTLR produces a typed {@link SelectSql}; never parses SQL by string.
 * Rows are assembled as {@link SqlResult} Object arrays at the SPI edge.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlInformationSchemaExecutor {
	public static final String SCHEMA_NAME = "information_schema";
	public static final String TABLE_SCHEMATA = "schemata";
	public static final String TABLE_TABLES = "tables";
	public static final String TABLE_COLUMNS = "columns";
	public static final String TABLE_STATISTICS = "statistics";
	public static final String TABLE_KEY_COLUMN_USAGE = "key_column_usage";
	public static final String TABLE_TABLE_CONSTRAINTS = "table_constraints";

	public static final String COL_CATALOG_NAME = "catalog_name";
	public static final String COL_SCHEMA_NAME = "schema_name";
	public static final String COL_TABLE_SCHEMA = "table_schema";
	public static final String COL_TABLE_NAME = "table_name";
	public static final String COL_TABLE_TYPE = "table_type";
	public static final String COL_COLUMN_NAME = "column_name";
	public static final String COL_ORDINAL_POSITION = "ordinal_position";
	public static final String COL_IS_NULLABLE = "is_nullable";
	public static final String COL_DATA_TYPE = "data_type";
	public static final String COL_COLUMN_DEFAULT = "column_default";
	public static final String COL_IS_IDENTITY = "is_identity";
	public static final String COL_INDEX_NAME = "index_name";
	public static final String COL_NON_UNIQUE = "non_unique";
	public static final String COL_SEQ_IN_INDEX = "seq_in_index";
	public static final String COL_CONSTRAINT_NAME = "constraint_name";
	public static final String COL_CONSTRAINT_TYPE = "constraint_type";
	public static final String COL_CONSTRAINT_SCHEMA = "constraint_schema";
	public static final String COL_UNIQUE_CONSTRAINT_SCHEMA = "unique_constraint_schema";
	public static final String COL_UNIQUE_CONSTRAINT_NAME = "unique_constraint_name";
	public static final String COL_DELETE_RULE = "delete_rule";
	public static final String COL_UPDATE_RULE = "update_rule";

	public static final String CATALOG_GRID = "grid";
	public static final String DEFAULT_SCHEMA = "public";
	public static final String TABLE_TYPE_BASE = "BASE TABLE";
	public static final String TABLE_TYPE_VIEW = "VIEW";
	public static final String YES = "YES";
	public static final String NO = "NO";
	public static final String CONSTRAINT_PRIMARY = "PRIMARY KEY";
	public static final String CONSTRAINT_FOREIGN = "FOREIGN KEY";
	public static final String PK_NAME_PREFIX = "pk_";
	public static final String TABLE_REFERENTIAL_CONSTRAINTS = "referential_constraints";

	private static final String MSG_UNKNOWN_VIEW = "Unknown information_schema view: ";

	private SqlInformationSchemaExecutor() {
	}

	public static boolean matches(String tableRef) {
		if (tableRef == null || tableRef.isBlank()) {
			return false;
		}
		final String t = tableRef.trim().toLowerCase(Locale.ROOT);
		final int dot = t.indexOf('.');
		if (dot <= 0) {
			return false;
		}
		return SCHEMA_NAME.equals(t.substring(0, dot));
	}

	public static SqlResult select(TableCatalog catalog, SelectSql s) {
		final String view = viewName(s.table());
		final List<SqlResult.ColumnMeta> metas = metasFor(view);
		final List<Object[]> fullRows = switch (view) {
			case TABLE_SCHEMATA -> schemataRows(catalog);
			case TABLE_TABLES -> tablesRows(catalog);
			case TABLE_COLUMNS -> columnsRows(catalog);
			case TABLE_STATISTICS -> statisticsRows(catalog);
			case TABLE_KEY_COLUMN_USAGE -> keyColumnUsageRows(catalog);
			case TABLE_TABLE_CONSTRAINTS -> tableConstraintsRows(catalog);
			case TABLE_REFERENTIAL_CONSTRAINTS -> referentialConstraintsRows(catalog);
			default -> throw new IllegalArgumentException(MSG_UNKNOWN_VIEW + view);
		};
		return SqlResult.resultSet(metas, fullRows);
	}

	/**
	 * Apply SELECT-list projection after WHERE (caller filters on full metas first).
	 */
	public static SqlResult project(SqlResult full, SelectSql s) {
		final List<Object[]> projected = projectRows(full.columns(), full.rows(), s.projection());
		return SqlResult.resultSet(metasForProjection(full.columns(), s.projection()), projected);
	}

	private static String viewName(String tableRef) {
		final String t = tableRef.trim().toLowerCase(Locale.ROOT);
		final int dot = t.indexOf('.');
		return t.substring(dot + 1);
	}

	private static List<SqlResult.ColumnMeta> metasFor(String view) {
		return switch (view) {
			case TABLE_SCHEMATA -> List.of(
					SqlResult.ColumnMeta.of(COL_CATALOG_NAME, SqlType.VARCHAR),
					SqlResult.ColumnMeta.of(COL_SCHEMA_NAME, SqlType.VARCHAR)
			);
			case TABLE_TABLES -> List.of(
					SqlResult.ColumnMeta.of(COL_TABLE_SCHEMA, SqlType.VARCHAR),
					SqlResult.ColumnMeta.of(COL_TABLE_NAME, SqlType.VARCHAR),
					SqlResult.ColumnMeta.of(COL_TABLE_TYPE, SqlType.VARCHAR)
			);
			case TABLE_COLUMNS -> List.of(
					SqlResult.ColumnMeta.of(COL_TABLE_SCHEMA, SqlType.VARCHAR),
					SqlResult.ColumnMeta.of(COL_TABLE_NAME, SqlType.VARCHAR),
					SqlResult.ColumnMeta.of(COL_COLUMN_NAME, SqlType.VARCHAR),
					SqlResult.ColumnMeta.of(COL_ORDINAL_POSITION, SqlType.INT),
					SqlResult.ColumnMeta.of(COL_COLUMN_DEFAULT, SqlType.VARCHAR),
					SqlResult.ColumnMeta.of(COL_IS_NULLABLE, SqlType.VARCHAR),
					SqlResult.ColumnMeta.of(COL_DATA_TYPE, SqlType.VARCHAR),
					SqlResult.ColumnMeta.of(COL_IS_IDENTITY, SqlType.VARCHAR)
			);
			case TABLE_STATISTICS -> List.of(
					SqlResult.ColumnMeta.of(COL_TABLE_SCHEMA, SqlType.VARCHAR),
					SqlResult.ColumnMeta.of(COL_TABLE_NAME, SqlType.VARCHAR),
					SqlResult.ColumnMeta.of(COL_NON_UNIQUE, SqlType.INT),
					SqlResult.ColumnMeta.of(COL_INDEX_NAME, SqlType.VARCHAR),
					SqlResult.ColumnMeta.of(COL_SEQ_IN_INDEX, SqlType.INT),
					SqlResult.ColumnMeta.of(COL_COLUMN_NAME, SqlType.VARCHAR)
			);
			case TABLE_KEY_COLUMN_USAGE -> List.of(
					SqlResult.ColumnMeta.of(COL_CONSTRAINT_SCHEMA, SqlType.VARCHAR),
					SqlResult.ColumnMeta.of(COL_CONSTRAINT_NAME, SqlType.VARCHAR),
					SqlResult.ColumnMeta.of(COL_TABLE_SCHEMA, SqlType.VARCHAR),
					SqlResult.ColumnMeta.of(COL_TABLE_NAME, SqlType.VARCHAR),
					SqlResult.ColumnMeta.of(COL_COLUMN_NAME, SqlType.VARCHAR),
					SqlResult.ColumnMeta.of(COL_ORDINAL_POSITION, SqlType.INT)
			);
			case TABLE_TABLE_CONSTRAINTS -> List.of(
					SqlResult.ColumnMeta.of(COL_CONSTRAINT_SCHEMA, SqlType.VARCHAR),
					SqlResult.ColumnMeta.of(COL_CONSTRAINT_NAME, SqlType.VARCHAR),
					SqlResult.ColumnMeta.of(COL_TABLE_SCHEMA, SqlType.VARCHAR),
					SqlResult.ColumnMeta.of(COL_TABLE_NAME, SqlType.VARCHAR),
					SqlResult.ColumnMeta.of(COL_CONSTRAINT_TYPE, SqlType.VARCHAR)
			);
			case TABLE_REFERENTIAL_CONSTRAINTS -> List.of(
					SqlResult.ColumnMeta.of(COL_CONSTRAINT_SCHEMA, SqlType.VARCHAR),
					SqlResult.ColumnMeta.of(COL_CONSTRAINT_NAME, SqlType.VARCHAR),
					SqlResult.ColumnMeta.of(COL_UNIQUE_CONSTRAINT_SCHEMA, SqlType.VARCHAR),
					SqlResult.ColumnMeta.of(COL_UNIQUE_CONSTRAINT_NAME, SqlType.VARCHAR),
					SqlResult.ColumnMeta.of(COL_DELETE_RULE, SqlType.VARCHAR),
					SqlResult.ColumnMeta.of(COL_UPDATE_RULE, SqlType.VARCHAR)
			);
			default -> throw new IllegalArgumentException(MSG_UNKNOWN_VIEW + view);
		};
	}

	private static List<Object[]> schemataRows(TableCatalog catalog) {
		final List<Object[]> rows = new ArrayList<>();
		for (String schema : catalog.schemaNames()) {
			rows.add(new Object[]{CATALOG_GRID, schema});
		}
		return rows;
	}

	private static List<Object[]> tablesRows(TableCatalog catalog) {
		final List<Object[]> rows = new ArrayList<>();
		for (TableSchema schema : catalog.schemas()) {
			final String[] parts = splitSchemaTable(schema.tableName());
			rows.add(new Object[]{parts[0], parts[1], TABLE_TYPE_BASE});
		}
		for (String viewName : catalog.viewNames()) {
			final ViewDef view = catalog.getView(viewName);
			if (view == null) {
				continue;
			}
			final String[] parts = splitSchemaTable(viewName);
			rows.add(new Object[]{parts[0], parts[1], TABLE_TYPE_VIEW});
		}
		return rows;
	}

	private static List<Object[]> columnsRows(TableCatalog catalog) {
		final List<Object[]> rows = new ArrayList<>();
		for (TableSchema schema : catalog.schemas()) {
			final String[] parts = splitSchemaTable(schema.tableName());
			for (ColumnDef col : schema.columns()) {
				rows.add(new Object[]{
						parts[0],
						parts[1],
						col.name(),
						Integer.valueOf(col.ordinal() + 1),
						null,
						col.nullable() ? YES : NO,
						col.type().name(),
						col.identity() ? YES : NO
				});
			}
		}
		return rows;
	}

	private static List<Object[]> statisticsRows(TableCatalog catalog) {
		final List<Object[]> rows = new ArrayList<>();
		for (TableSchema schema : catalog.schemas()) {
			final String[] parts = splitSchemaTable(schema.tableName());
			for (IndexDef idx : schema.indexes()) {
				// STRICT = unique (PK mirror / UNIQUE INDEX); LAX / BITMAP = non-unique.
				final int nonUnique = idx.kind() == IndexType.STRICT ? 0 : 1;
				int seq = 1;
				for (String col : idx.columns()) {
					rows.add(new Object[]{
							parts[0],
							parts[1],
							Integer.valueOf(nonUnique),
							idx.name(),
							Integer.valueOf(seq),
							col
					});
					seq++;
				}
			}
		}
		return rows;
	}

	private static List<Object[]> keyColumnUsageRows(TableCatalog catalog) {
		final List<Object[]> rows = new ArrayList<>();
		for (TableSchema schema : catalog.schemas()) {
			final String[] parts = splitSchemaTable(schema.tableName());
			final ColumnDef pk = schema.pkColumn();
			final String cname = PK_NAME_PREFIX + parts[1];
			rows.add(new Object[]{
					parts[0],
					cname,
					parts[0],
					parts[1],
					pk.name(),
					Integer.valueOf(1)
			});
			for (FkDef fk : schema.foreignKeys()) {
				int ord = 1;
				for (String col : fk.childColumns()) {
					rows.add(new Object[]{
							parts[0],
							fk.name(),
							parts[0],
							parts[1],
							col,
							Integer.valueOf(ord++)
					});
				}
			}
		}
		return rows;
	}

	private static List<Object[]> tableConstraintsRows(TableCatalog catalog) {
		final List<Object[]> rows = new ArrayList<>();
		for (TableSchema schema : catalog.schemas()) {
			final String[] parts = splitSchemaTable(schema.tableName());
			final String cname = PK_NAME_PREFIX + parts[1];
			rows.add(new Object[]{
					parts[0],
					cname,
					parts[0],
					parts[1],
					CONSTRAINT_PRIMARY
			});
			for (FkDef fk : schema.foreignKeys()) {
				rows.add(new Object[]{
						parts[0],
						fk.name(),
						parts[0],
						parts[1],
						CONSTRAINT_FOREIGN
				});
			}
		}
		return rows;
	}

	private static List<Object[]> referentialConstraintsRows(TableCatalog catalog) {
		final List<Object[]> rows = new ArrayList<>();
		for (TableSchema schema : catalog.schemas()) {
			final String[] childParts = splitSchemaTable(schema.tableName());
			for (FkDef fk : schema.foreignKeys()) {
				final String[] parentParts = splitSchemaTable(fk.parentTable());
				rows.add(new Object[]{
						childParts[0],
						fk.name(),
						parentParts[0],
						PK_NAME_PREFIX + parentParts[1],
						fk.onDelete().sqlToken(),
						fk.onUpdate().sqlToken()
				});
			}
		}
		return rows;
	}

	static String[] splitSchemaTable(String qualified) {
		if (qualified == null || qualified.isBlank()) {
			return new String[]{DEFAULT_SCHEMA, ""};
		}
		final String t = qualified.trim();
		final int dot = t.indexOf('.');
		if (dot <= 0) {
			return new String[]{DEFAULT_SCHEMA, t};
		}
		return new String[]{t.substring(0, dot), t.substring(dot + 1)};
	}

	private static List<Object[]> projectRows(
			List<SqlResult.ColumnMeta> fullMetas,
			List<Object[]> fullRows,
			List<String> projection
	) {
		if (projection == null || projection.isEmpty()
				|| (projection.size() == 1 && "*".equals(projection.getFirst()))) {
			return fullRows;
		}
		final int[] ords = new int[projection.size()];
		for (int i = 0; i < projection.size(); i++) {
			ords[i] = indexOfColumn(fullMetas, projection.get(i));
		}
		final List<Object[]> out = new ArrayList<>(fullRows.size());
		for (Object[] row : fullRows) {
			final Object[] projected = new Object[ords.length];
			for (int i = 0; i < ords.length; i++) {
				projected[i] = row[ords[i]];
			}
			out.add(projected);
		}
		return out;
	}

	private static List<SqlResult.ColumnMeta> metasForProjection(
			List<SqlResult.ColumnMeta> fullMetas,
			List<String> projection
	) {
		if (projection == null || projection.isEmpty()
				|| (projection.size() == 1 && "*".equals(projection.getFirst()))) {
			return fullMetas;
		}
		final List<SqlResult.ColumnMeta> out = new ArrayList<>(projection.size());
		for (String col : projection) {
			final int idx = indexOfColumn(fullMetas, col);
			out.add(fullMetas.get(idx));
		}
		return out;
	}

	private static int indexOfColumn(List<SqlResult.ColumnMeta> metas, String name) {
		for (int i = 0; i < metas.size(); i++) {
			if (metas.get(i).name().equalsIgnoreCase(name)) {
				return i;
			}
		}
		throw new IllegalArgumentException("Unknown column in information_schema projection: " + name);
	}
}