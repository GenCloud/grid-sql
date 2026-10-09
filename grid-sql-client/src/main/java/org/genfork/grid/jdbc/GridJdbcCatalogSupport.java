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
package org.genfork.grid.jdbc;

import org.genfork.grid.catalog.SqlType;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tooling helpers: extract simple FROM table refs and enrich JDBC column metadata
 * from {@code information_schema} (not a second SQL lexer in the engine).
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
public final class GridJdbcCatalogSupport {
	private static final Pattern FROM_TABLE = Pattern.compile(
			"(?i)\\bFROM\\s+([A-Za-z_][A-Za-z0-9_]*)(?:\\.([A-Za-z_][A-Za-z0-9_]*))?"
					+ "(?!\\s*\\()");

	private static final String SQL_COLUMNS =
			"SELECT table_schema, table_name, column_name, is_nullable, data_type, ordinal_position "
					+ "FROM information_schema.columns";
	private static final String SQL_TABLE_CONSTRAINTS =
			"SELECT table_schema, table_name, constraint_name, constraint_type "
					+ "FROM information_schema.table_constraints";
	private static final String SQL_KEY_COLUMN_USAGE =
			"SELECT table_schema, table_name, column_name, ordinal_position, constraint_name "
					+ "FROM information_schema.key_column_usage";
	private static final String CONSTRAINT_PRIMARY_KEY = "PRIMARY KEY";

	private GridJdbcCatalogSupport() {
	}

	/**
	 * Best-effort single-table FROM extraction for Data Editor SELECTs (no JOIN).
	 *
	 * @return {@code [schema, table]} or {@code null}
	 */
	public static String[] extractSimpleFromTable(String sql) {
		if (sql == null || sql.isBlank()) {
			return null;
		}
		final String upper = sql.toUpperCase(Locale.ROOT);
		if (upper.contains(" JOIN ") || upper.contains(" UNION ") || upper.contains(" INTERSECT ")
				|| upper.contains(" EXCEPT ")) {
			return null;
		}
		final Matcher m = FROM_TABLE.matcher(sql);
		if (!m.find()) {
			return null;
		}
		if (m.group(2) != null) {
			return new String[]{m.group(1), m.group(2)};
		}
		return new String[]{"public", m.group(1)};
	}

	/**
	 * Resolve edit context: PRIMARY KEY columns only, ordered by {@code ordinal_position}.
	 * {@link GridTableEditContext#pkColumn()} is the leading PK column (partial-delete contract).
	 */
	public static GridTableEditContext resolveEditContext(GridConnection connection, String sql)
			throws SQLException {
		final String[] from = extractSimpleFromTable(sql);
		if (from == null) {
			return null;
		}
		final String schema = from[0];
		final String table = from[1];
		final Set<String> pkConstraintNames = loadPrimaryKeyConstraintNames(connection, schema, table);
		if (pkConstraintNames.isEmpty()) {
			return null;
		}
		final List<PkCol> pkCols = loadOrderedPrimaryKeyColumns(connection, schema, table, pkConstraintNames);
		if (pkCols.isEmpty()) {
			return null;
		}
		final List<String> names = new ArrayList<>(pkCols.size());
		for (PkCol col : pkCols) {
			names.add(col.columnName());
		}
		return new GridTableEditContext(schema, table, names.getFirst(), -1, names);
	}

	private static Set<String> loadPrimaryKeyConstraintNames(
			GridConnection connection,
			String schema,
			String table
	) throws SQLException {
		final Set<String> names = new HashSet<>();
		final ResultSet rs = connection.executeQueryInternal(SQL_TABLE_CONSTRAINTS);
		try {
			while (rs.next()) {
				if (!schema.equalsIgnoreCase(rs.getString(1)) || !table.equalsIgnoreCase(rs.getString(2))) {
					continue;
				}
				if (!CONSTRAINT_PRIMARY_KEY.equalsIgnoreCase(rs.getString(4))) {
					continue;
				}
				final String cname = rs.getString(3);
				if (cname != null && !cname.isBlank()) {
					names.add(cname.toLowerCase(Locale.ROOT));
				}
			}
		} finally {
			closeQuietly(rs);
		}
		return names;
	}

	private static List<PkCol> loadOrderedPrimaryKeyColumns(
			GridConnection connection,
			String schema,
			String table,
			Set<String> pkConstraintNames
	) throws SQLException {
		final List<PkCol> cols = new ArrayList<>();
		final ResultSet rs = connection.executeQueryInternal(SQL_KEY_COLUMN_USAGE);
		try {
			while (rs.next()) {
				if (!schema.equalsIgnoreCase(rs.getString(1)) || !table.equalsIgnoreCase(rs.getString(2))) {
					continue;
				}
				final String cname = rs.getString(5);
				if (cname == null || !pkConstraintNames.contains(cname.toLowerCase(Locale.ROOT))) {
					continue;
				}
				final String column = rs.getString(3);
				final int ordinal = rs.getInt(4);
				if (column != null && !column.isBlank()) {
					cols.add(new PkCol(column, ordinal));
				}
			}
		} finally {
			closeQuietly(rs);
		}
		cols.sort(Comparator.comparingInt(PkCol::ordinal));
		return cols;
	}

	private static void closeQuietly(ResultSet rs) throws SQLException {
		final Statement st = rs.getStatement();
		rs.close();
		if (st != null) {
			st.close();
		}
	}

	public static GridColumnHints loadColumnHints(GridConnection connection, String schema, String table)
			throws SQLException {
		final Map<String, Hint> byName = new HashMap<>();
		final ResultSet rsCol = connection.executeQueryInternal(SQL_COLUMNS);
		try {
			while (rsCol.next()) {
				if (!schema.equalsIgnoreCase(rsCol.getString(1)) || !table.equalsIgnoreCase(rsCol.getString(2))) {
					continue;
				}
				final String col = rsCol.getString(3);
				final boolean nullable = "YES".equalsIgnoreCase(rsCol.getString(4));
				final SqlType type = GridJdbcTypeSupport.parseSqlType(rsCol.getString(5));
				byName.put(col.toLowerCase(Locale.ROOT), new Hint(schema, table, col, nullable, type));
			}
		} finally {
			closeQuietly(rsCol);
		}
		return new GridColumnHints(schema, table, byName);
	}

	/**
	 * Per-column catalog hints for ResultSetMetaData.
	 */
	public static final class GridColumnHints {
		private final String schema;
		private final String table;
		private final Map<String, Hint> byName;

		GridColumnHints(String schema, String table, Map<String, Hint> byName) {
			this.schema = schema;
			this.table = table;
			this.byName = byName;
		}

		public String schema() {
			return schema;
		}

		public String table() {
			return table;
		}

		public Hint hint(String columnName) {
			if (columnName == null) {
				return null;
			}
			return byName.get(columnName.toLowerCase(Locale.ROOT));
		}
	}

	public record Hint(String schema, String table, String column, boolean nullable, SqlType type) {
	}

	private record PkCol(String columnName, int ordinal) {
	}
}
