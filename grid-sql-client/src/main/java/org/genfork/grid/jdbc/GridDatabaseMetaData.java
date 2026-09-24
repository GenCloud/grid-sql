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

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.genfork.grid.catalog.SqlType;

/**
 * JDBC {@link DatabaseMetaData} via catalog SQL ({@code information_schema}) + bridge.
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
public final class GridDatabaseMetaData implements DatabaseMetaData {
	public static final String CATALOG_NAME = "grid";
	public static final String DRIVER_NAME = "Jamoa Grid JDBC";
	private static final String SQL_SCHEMATA =
			"SELECT schema_name FROM information_schema.schemata";
	private static final String SQL_TABLES =
			"SELECT table_schema, table_name, table_type FROM information_schema.tables";
	private static final String SQL_COLUMNS =
			"SELECT table_schema, table_name, column_name, ordinal_position, column_default, "
					+ "is_nullable, data_type, is_identity FROM information_schema.columns";
	private static final String SQL_STATISTICS =
			"SELECT table_schema, table_name, non_unique, index_name, seq_in_index, column_name "
					+ "FROM information_schema.statistics";
	private static final String SQL_KEY_COLUMN_USAGE =
			"SELECT constraint_schema, constraint_name, table_schema, table_name, column_name, "
					+ "ordinal_position FROM information_schema.key_column_usage";
	private static final String SQL_TABLE_CONSTRAINTS =
			"SELECT constraint_schema, constraint_name, table_schema, table_name, constraint_type "
					+ "FROM information_schema.table_constraints";
	private static final String SQL_REFERENTIAL_CONSTRAINTS =
			"SELECT constraint_schema, constraint_name, unique_constraint_schema, unique_constraint_name, "
					+ "delete_rule, update_rule FROM information_schema.referential_constraints";
	private static final String SQL_TABLE_PRIVILEGES =
			"SELECT grantee, table_schema, table_name, privilege_type, is_grantable "
					+ "FROM information_schema.table_privileges";
	private static final String CONSTRAINT_PRIMARY = "PRIMARY KEY";
	private static final String IDENTITY_YES = "YES";
	private static final String GRANTOR_SYSTEM = "";
	private static final String IS_GRANTABLE_NO = "NO";
	/**
	 * Simplified SQL keywords for Generic JDBC / DBeaver dialect sniffing (comma-separated).
	 */
	private static final String SQL_KEYWORDS =
			"SCHEMA,USER,ROLE,GRANT,REVOKE,IDENTITY,UPSERT,EXPLAIN,ANALYZE,BITMAP";

	private final GridConnection connection;

	GridDatabaseMetaData(GridConnection connection) {
		this.connection = connection;
	}

	@Override
	public boolean allProceduresAreCallable() {
		return false;
	}

	@Override
	public boolean allTablesAreSelectable() {
		return true;
	}

	@Override
	public String getURL() {
		return GridJdbcUrls.JDBC_GRID_PREFIX;
	}

	@Override
	public String getUserName() {
		return connection.authUser();
	}

	@Override
	public boolean isReadOnly() {
		return false;
	}

	@Override
	public boolean nullsAreSortedHigh() {
		return false;
	}

	@Override
	public boolean nullsAreSortedLow() {
		return true;
	}

	@Override
	public boolean nullsAreSortedAtStart() {
		return false;
	}

	@Override
	public boolean nullsAreSortedAtEnd() {
		return false;
	}

	@Override
	public String getDatabaseProductName() {
		return GridConnection.PRODUCT_NAME;
	}

	@Override
	public String getDatabaseProductVersion() {
		return GridConnection.PRODUCT_VERSION;
	}

	@Override
	public String getDriverName() {
		return DRIVER_NAME;
	}

	@Override
	public String getDriverVersion() {
		return GridDriver.MAJOR_VERSION + "." + GridDriver.MINOR_VERSION;
	}

	@Override
	public int getDriverMajorVersion() {
		return GridDriver.MAJOR_VERSION;
	}

	@Override
	public int getDriverMinorVersion() {
		return GridDriver.MINOR_VERSION;
	}

	@Override
	public boolean usesLocalFiles() {
		return false;
	}

	@Override
	public boolean usesLocalFilePerTable() {
		return false;
	}

	@Override
	public boolean supportsMixedCaseIdentifiers() {
		return false;
	}

	@Override
	public boolean storesUpperCaseIdentifiers() {
		return false;
	}

	@Override
	public boolean storesLowerCaseIdentifiers() {
		return true;
	}

	@Override
	public boolean storesMixedCaseIdentifiers() {
		return false;
	}

	@Override
	public boolean supportsMixedCaseQuotedIdentifiers() {
		return true;
	}

	@Override
	public boolean storesUpperCaseQuotedIdentifiers() {
		return false;
	}

	@Override
	public boolean storesLowerCaseQuotedIdentifiers() {
		return false;
	}

	@Override
	public boolean storesMixedCaseQuotedIdentifiers() {
		return true;
	}

	@Override
	public String getIdentifierQuoteString() {
		return "\"";
	}

	@Override
	public String getSQLKeywords() {
		return SQL_KEYWORDS;
	}

	@Override
	public String getNumericFunctions() {
		return "";
	}

	@Override
	public String getStringFunctions() {
		return "";
	}

	@Override
	public String getSystemFunctions() {
		return "";
	}

	@Override
	public String getTimeDateFunctions() {
		return "";
	}

	@Override
	public String getSearchStringEscape() {
		return "\\";
	}

	@Override
	public String getExtraNameCharacters() {
		return "";
	}

	@Override
	public boolean supportsAlterTableWithAddColumn() {
		return true;
	}

	@Override
	public boolean supportsAlterTableWithDropColumn() {
		return true;
	}

	@Override
	public boolean supportsColumnAliasing() {
		return true;
	}

	@Override
	public boolean nullPlusNonNullIsNull() {
		return true;
	}

	@Override
	public boolean supportsConvert() {
		return false;
	}

	@Override
	public boolean supportsConvert(int fromType, int toType) {
		return false;
	}

	@Override
	public boolean supportsTableCorrelationNames() {
		return true;
	}

	@Override
	public boolean supportsDifferentTableCorrelationNames() {
		return false;
	}

	@Override
	public boolean supportsExpressionsInOrderBy() {
		return true;
	}

	@Override
	public boolean supportsOrderByUnrelated() {
		return false;
	}

	@Override
	public boolean supportsGroupBy() {
		return true;
	}

	@Override
	public boolean supportsGroupByUnrelated() {
		return false;
	}

	@Override
	public boolean supportsGroupByBeyondSelect() {
		return false;
	}

	@Override
	public boolean supportsLikeEscapeClause() {
		return false;
	}

	@Override
	public boolean supportsMultipleResultSets() {
		return true;
	}

	@Override
	public boolean supportsMultipleTransactions() {
		return true;
	}

	@Override
	public boolean supportsNonNullableColumns() {
		return true;
	}

	@Override
	public boolean supportsMinimumSQLGrammar() {
		return true;
	}

	@Override
	public boolean supportsCoreSQLGrammar() {
		return false;
	}

	@Override
	public boolean supportsExtendedSQLGrammar() {
		return false;
	}

	@Override
	public boolean supportsANSI92EntryLevelSQL() {
		return false;
	}

	@Override
	public boolean supportsANSI92IntermediateSQL() {
		return false;
	}

	@Override
	public boolean supportsANSI92FullSQL() {
		return false;
	}

	@Override
	public boolean supportsIntegrityEnhancementFacility() {
		return false;
	}

	@Override
	public boolean supportsOuterJoins() {
		return true;
	}

	@Override
	public boolean supportsFullOuterJoins() {
		return true;
	}

	@Override
	public boolean supportsLimitedOuterJoins() {
		return true;
	}

	@Override
	public String getSchemaTerm() {
		return "schema";
	}

	@Override
	public String getProcedureTerm() {
		return "procedure";
	}

	@Override
	public String getCatalogTerm() {
		return "catalog";
	}

	@Override
	public boolean isCatalogAtStart() {
		return true;
	}

	@Override
	public String getCatalogSeparator() {
		return ".";
	}

	@Override
	public boolean supportsSchemasInDataManipulation() {
		return true;
	}

	@Override
	public boolean supportsSchemasInProcedureCalls() {
		return false;
	}

	@Override
	public boolean supportsSchemasInTableDefinitions() {
		return true;
	}

	@Override
	public boolean supportsSchemasInIndexDefinitions() {
		return true;
	}

	@Override
	public boolean supportsSchemasInPrivilegeDefinitions() {
		return true;
	}

	@Override
	public boolean supportsCatalogsInDataManipulation() {
		return false;
	}

	@Override
	public boolean supportsCatalogsInProcedureCalls() {
		return false;
	}

	@Override
	public boolean supportsCatalogsInTableDefinitions() {
		return false;
	}

	@Override
	public boolean supportsCatalogsInIndexDefinitions() {
		return false;
	}

	@Override
	public boolean supportsCatalogsInPrivilegeDefinitions() {
		return false;
	}

	@Override
	public boolean supportsPositionedDelete() {
		return false;
	}

	@Override
	public boolean supportsPositionedUpdate() {
		return false;
	}

	@Override
	public boolean supportsSelectForUpdate() {
		return true;
	}

	@Override
	public boolean supportsStoredProcedures() {
		return false;
	}

	@Override
	public boolean supportsSubqueriesInComparisons() {
		return true;
	}

	@Override
	public boolean supportsSubqueriesInExists() {
		return false;
	}

	@Override
	public boolean supportsSubqueriesInIns() {
		return true;
	}

	@Override
	public boolean supportsSubqueriesInQuantifieds() {
		return false;
	}

	@Override
	public boolean supportsCorrelatedSubqueries() {
		return false;
	}

	@Override
	public boolean supportsUnion() {
		return true;
	}

	@Override
	public boolean supportsUnionAll() {
		return true;
	}

	@Override
	public boolean supportsOpenCursorsAcrossCommit() {
		return false;
	}

	@Override
	public boolean supportsOpenCursorsAcrossRollback() {
		return false;
	}

	@Override
	public boolean supportsOpenStatementsAcrossCommit() {
		return true;
	}

	@Override
	public boolean supportsOpenStatementsAcrossRollback() {
		return true;
	}

	@Override
	public int getMaxBinaryLiteralLength() {
		return 0;
	}

	@Override
	public int getMaxCharLiteralLength() {
		return 0;
	}

	@Override
	public int getMaxColumnNameLength() {
		return 128;
	}

	@Override
	public int getMaxColumnsInGroupBy() {
		return 0;
	}

	@Override
	public int getMaxColumnsInIndex() {
		return 0;
	}

	@Override
	public int getMaxColumnsInOrderBy() {
		return 0;
	}

	@Override
	public int getMaxColumnsInSelect() {
		return 0;
	}

	@Override
	public int getMaxColumnsInTable() {
		return 0;
	}

	@Override
	public int getMaxConnections() {
		return 0;
	}

	@Override
	public int getMaxCursorNameLength() {
		return 0;
	}

	@Override
	public int getMaxIndexLength() {
		return 0;
	}

	@Override
	public int getMaxSchemaNameLength() {
		return 128;
	}

	@Override
	public int getMaxProcedureNameLength() {
		return 0;
	}

	@Override
	public int getMaxCatalogNameLength() {
		return 128;
	}

	@Override
	public int getMaxRowSize() {
		return 0;
	}

	@Override
	public boolean doesMaxRowSizeIncludeBlobs() {
		return false;
	}

	@Override
	public int getMaxStatementLength() {
		return 0;
	}

	@Override
	public int getMaxStatements() {
		return 0;
	}

	@Override
	public int getMaxTableNameLength() {
		return 128;
	}

	@Override
	public int getMaxTablesInSelect() {
		return 0;
	}

	@Override
	public int getMaxUserNameLength() {
		return 0;
	}

	@Override
	public int getDefaultTransactionIsolation() {
		return Connection.TRANSACTION_READ_COMMITTED;
	}

	@Override
	public boolean supportsTransactions() {
		return true;
	}

	@Override
	public boolean supportsTransactionIsolationLevel(int level) {
		return level == Connection.TRANSACTION_READ_COMMITTED;
	}

	@Override
	public boolean supportsDataDefinitionAndDataManipulationTransactions() {
		return false;
	}

	@Override
	public boolean supportsDataManipulationTransactionsOnly() {
		return true;
	}

	@Override
	public boolean dataDefinitionCausesTransactionCommit() {
		return true;
	}

	@Override
	public boolean dataDefinitionIgnoredInTransactions() {
		return false;
	}

	@Override
	public ResultSet getProcedures(String catalog, String schemaPattern, String procedureNamePattern)
			throws SQLException {
		return emptyResult(List.of("PROCEDURE_CAT", "PROCEDURE_SCHEM", "PROCEDURE_NAME",
				"RESERVED1", "RESERVED2", "RESERVED3", "REMARKS", "PROCEDURE_TYPE", "SPECIFIC_NAME"));
	}

	@Override
	public ResultSet getProcedureColumns(
			String catalog,
			String schemaPattern,
			String procedureNamePattern,
			String columnNamePattern
	) throws SQLException {
		return emptyResult(List.of("PROCEDURE_CAT", "PROCEDURE_SCHEM", "PROCEDURE_NAME", "COLUMN_NAME"));
	}

	@Override
	public ResultSet getTables(
			String catalog,
			String schemaPattern,
			String tableNamePattern,
			String[] types
	) throws SQLException {
		final List<Object[]> rows = new ArrayList<>();
		try (ResultSet rs = connection.createStatement().executeQuery(SQL_TABLES)) {
			while (rs.next()) {
				final String schema = rs.getString(1);
				final String table = rs.getString(2);
				final String tableType = rs.getString(3);
				if (!GridJdbcMetaDataSupport.matchPattern(schemaPattern, schema)
						|| !GridJdbcMetaDataSupport.matchPattern(tableNamePattern, table)) {
					continue;
				}
				if (types != null && types.length > 0) {
					boolean ok = false;
					for (String t : types) {
						if (t != null && t.equalsIgnoreCase(tableType)) {
							ok = true;
							break;
						}
						if ("TABLE".equalsIgnoreCase(t)
								&& GridJdbcMetaDataSupport.INFORMATION_SCHEMA_BASE_TABLE.equals(tableType)) {
							ok = true;
							break;
						}
					}
					if (!ok) {
						continue;
					}
				}
				rows.add(new Object[]{
						CATALOG_NAME,
						schema,
						table,
						GridJdbcMetaDataSupport.mapTableType(tableType),
						null,
						null,
						null,
						null,
						null,
						null
				});
			}
		}
		return MetaResultSets.tables(connection, rows);
	}

	@Override
	public ResultSet getSchemas() throws SQLException {
		return getSchemas(null, null);
	}

	@Override
	public ResultSet getSchemas(String catalog, String schemaPattern) throws SQLException {
		final List<Object[]> rows = new ArrayList<>();
		try (ResultSet rs = connection.createStatement().executeQuery(SQL_SCHEMATA)) {
			while (rs.next()) {
				final String schema = rs.getString(1);
				if (!GridJdbcMetaDataSupport.matchPattern(schemaPattern, schema)) {
					continue;
				}
				rows.add(new Object[]{schema, CATALOG_NAME});
			}
		}
		return MetaResultSets.schemas(connection, rows);
	}

	@Override
	public ResultSet getCatalogs() throws SQLException {
		final List<Object[]> rows = new ArrayList<>(1);
		rows.add(new Object[]{CATALOG_NAME});
		return MetaResultSets.catalogs(connection, rows);
	}

	@Override
	public ResultSet getTableTypes() throws SQLException {
		final List<Object[]> rows = new ArrayList<>(2);
		rows.add(new Object[]{"TABLE"});
		rows.add(new Object[]{"VIEW"});
		return MetaResultSets.tableTypes(connection, rows);
	}

	@Override
	public ResultSet getColumns(
			String catalog,
			String schemaPattern,
			String tableNamePattern,
			String columnNamePattern
	) throws SQLException {
		final List<Object[]> rows = new ArrayList<>();
		try (ResultSet rs = connection.createStatement().executeQuery(SQL_COLUMNS)) {
			while (rs.next()) {
				final String schema = rs.getString(1);
				final String table = rs.getString(2);
				final String column = rs.getString(3);
				if (!GridJdbcMetaDataSupport.matchPattern(schemaPattern, schema)
						|| !GridJdbcMetaDataSupport.matchPattern(tableNamePattern, table)
						|| !GridJdbcMetaDataSupport.matchPattern(columnNamePattern, column)) {
					continue;
				}
				final int ordinal = rs.getInt(4);
				final String nullable = rs.getString(6);
				final String dataType = rs.getString(7);
				final String identity = rs.getString(8);
				final SqlType sqlType = GridJdbcTypeSupport.parseSqlType(dataType);
				final int jdbcType = GridJdbcTypeSupport.toJdbcType(sqlType);
				final int columnSize = GridJdbcTypeSupport.precisionFor(sqlType);
				final int decimalDigits = GridJdbcTypeSupport.scaleFor(sqlType);
				final int nullableFlag = "YES".equalsIgnoreCase(nullable)
						? DatabaseMetaData.columnNullable
						: DatabaseMetaData.columnNoNulls;
				final String autoInc = IDENTITY_YES.equalsIgnoreCase(identity)
						? IDENTITY_YES
						: "NO";
				rows.add(new Object[]{
						CATALOG_NAME,
						schema,
						table,
						column,
                        jdbcType,
						dataType,
                        columnSize,
                        0,
                        decimalDigits,
                        10,
                        nullableFlag,
						null,
						rs.getString(5),
                        0,
                        0,
                        columnSize,
                        ordinal,
						"YES".equalsIgnoreCase(nullable) ? "YES" : "NO",
						null,
						null,
						null,
						null,
						autoInc,
						autoInc
				});
			}
		}
		return MetaResultSets.columns(connection, rows);
	}

	@Override
	public ResultSet getColumnPrivileges(
			String catalog,
			String schema,
			String table,
			String columnNamePattern
	) throws SQLException {
		return emptyResult(List.of("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "PRIVILEGE"));
	}

	@Override
	public ResultSet getTablePrivileges(String catalog, String schemaPattern, String tableNamePattern)
			throws SQLException {
		final List<Object[]> rows = new ArrayList<>();
		try (ResultSet rs = connection.createStatement().executeQuery(SQL_TABLE_PRIVILEGES)) {
			while (rs.next()) {
				final String schema = rs.getString(2);
				final String table = rs.getString(3);
				if (!GridJdbcMetaDataSupport.matchPattern(schemaPattern, schema)) {
					continue;
				}
				if (!GridJdbcMetaDataSupport.matchPattern(tableNamePattern, table)) {
					continue;
				}
				rows.add(new Object[]{
						CATALOG_NAME,
						schema,
						table,
						GRANTOR_SYSTEM,
						rs.getString(1),
						rs.getString(4),
						rs.getString(5) == null ? IS_GRANTABLE_NO : rs.getString(5)
				});
			}
		}
		return MetaResultSets.tablePrivileges(connection, rows);
	}

	@Override
	public ResultSet getBestRowIdentifier(
			String catalog,
			String schema,
			String table,
			int scope,
			boolean nullable
	) throws SQLException {
		return emptyResult(List.of("SCOPE", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME"));
	}

	@Override
	public ResultSet getVersionColumns(String catalog, String schema, String table) throws SQLException {
		return emptyResult(List.of("SCOPE", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME"));
	}

	@Override
	public ResultSet getPrimaryKeys(String catalog, String schema, String table) throws SQLException {
		final Set<String> pkConstraints = primaryKeyConstraintKeys(schema, table);
		final List<Object[]> rows = new ArrayList<>();
		try (ResultSet rs = connection.createStatement().executeQuery(SQL_KEY_COLUMN_USAGE)) {
			while (rs.next()) {
				final String constraintSchema = rs.getString(1);
				final String constraintName = rs.getString(2);
				final String sch = rs.getString(3);
				final String tbl = rs.getString(4);
				if (schema != null && !schema.equalsIgnoreCase(sch)) {
					continue;
				}
				if (table != null && !table.equalsIgnoreCase(tbl)) {
					continue;
				}
				final String key = constraintKey(constraintSchema, constraintName);
				if (!pkConstraints.contains(key)) {
					continue;
				}
				rows.add(new Object[]{
						CATALOG_NAME,
						sch,
						tbl,
						rs.getString(5),
                        (short) rs.getInt(6),
						constraintName
				});
			}
		}
		return MetaResultSets.primaryKeys(connection, rows);
	}

	@Override
	public ResultSet getImportedKeys(String catalog, String schema, String table) throws SQLException {
		return MetaResultSets.importedKeys(connection, foreignKeyRows(schema, table, true));
	}

	@Override
	public ResultSet getExportedKeys(String catalog, String schema, String table) throws SQLException {
		return MetaResultSets.importedKeys(connection, foreignKeyRows(schema, table, false));
	}

	@Override
	public ResultSet getCrossReference(
			String parentCatalog,
			String parentSchema,
			String parentTable,
			String foreignCatalog,
			String foreignSchema,
			String foreignTable
	) throws SQLException {
		final List<Object[]> all = foreignKeyRows(foreignSchema, foreignTable, true);
		if (parentSchema == null && parentTable == null) {
			return MetaResultSets.importedKeys(connection, all);
		}
		final List<Object[]> filtered = new ArrayList<>();
		for (Object[] row : all) {
			final String pkSchem = (String) row[1];
			final String pkTable = (String) row[2];
			if (parentSchema != null && !parentSchema.equalsIgnoreCase(pkSchem)) {
				continue;
			}
			if (parentTable != null && !parentTable.equalsIgnoreCase(pkTable)) {
				continue;
			}
			filtered.add(row);
		}
		return MetaResultSets.importedKeys(connection, filtered);
	}

	/**
	 * @param imported {@code true} = FKs of {@code table}; {@code false} = FKs referencing {@code table}
	 */
	private List<Object[]> foreignKeyRows(String schema, String table, boolean imported) throws SQLException {
		final Map<String, ConstraintTable> constraintsByName = loadConstraintTables();
		final Map<String, RefConstraint> refs = loadReferentialConstraints();
		final Map<String, List<KeyColumn>> usageByConstraint = loadKeyColumnUsage();

		final List<Object[]> rows = new ArrayList<>();
		for (Map.Entry<String, RefConstraint> e : refs.entrySet()) {
			final RefConstraint ref = e.getValue();
			final ConstraintTable child = constraintsByName.get(constraintKey(ref.constraintSchema, ref.constraintName));
			final ConstraintTable parent = constraintsByName.get(
					constraintKey(ref.uniqueConstraintSchema, ref.uniqueConstraintName));
			if (child == null || parent == null) {
				continue;
			}
			if (imported) {
				if (schema != null && !schema.equalsIgnoreCase(child.tableSchema)) {
					continue;
				}
				if (table != null && !table.equalsIgnoreCase(child.tableName)) {
					continue;
				}
			} else {
				if (schema != null && !schema.equalsIgnoreCase(parent.tableSchema)) {
					continue;
				}
				if (table != null && !table.equalsIgnoreCase(parent.tableName)) {
					continue;
				}
			}
			final List<KeyColumn> fkCols = usageByConstraint.getOrDefault(
					constraintKey(ref.constraintSchema, ref.constraintName), List.of());
			final List<KeyColumn> pkCols = usageByConstraint.getOrDefault(
					constraintKey(ref.uniqueConstraintSchema, ref.uniqueConstraintName), List.of());
			final int n = Math.min(fkCols.size(), pkCols.size());
			for (int i = 0; i < n; i++) {
				final KeyColumn fk = fkCols.get(i);
				final KeyColumn pk = pkCols.get(i);
				rows.add(new Object[]{
						CATALOG_NAME,
						parent.tableSchema,
						parent.tableName,
						pk.columnName,
						CATALOG_NAME,
						child.tableSchema,
						child.tableName,
						fk.columnName,
                        (short) fk.ordinal,
                        GridJdbcFkMetaSupport.jdbcRule(ref.updateRule),
                        GridJdbcFkMetaSupport.jdbcRule(ref.deleteRule),
						ref.constraintName,
						ref.uniqueConstraintName,
                        (short) DatabaseMetaData.importedKeyNotDeferrable
				});
			}
		}
		return rows;
	}

	private Set<String> primaryKeyConstraintKeys(String schema, String table) throws SQLException {
		final Set<String> keys = new HashSet<>();
		try (ResultSet rs = connection.createStatement().executeQuery(SQL_TABLE_CONSTRAINTS)) {
			while (rs.next()) {
				final String constraintSchema = rs.getString(1);
				final String constraintName = rs.getString(2);
				final String sch = rs.getString(3);
				final String tbl = rs.getString(4);
				final String type = rs.getString(5);
				if (!CONSTRAINT_PRIMARY.equalsIgnoreCase(type)) {
					continue;
				}
				if (schema != null && !schema.equalsIgnoreCase(sch)) {
					continue;
				}
				if (table != null && !table.equalsIgnoreCase(tbl)) {
					continue;
				}
				keys.add(constraintKey(constraintSchema, constraintName));
			}
		}
		return keys;
	}

	private Map<String, ConstraintTable> loadConstraintTables() throws SQLException {
		final Map<String, ConstraintTable> out = new HashMap<>();
		try (ResultSet rs = connection.createStatement().executeQuery(SQL_TABLE_CONSTRAINTS)) {
			while (rs.next()) {
				final String constraintSchema = rs.getString(1);
				final String constraintName = rs.getString(2);
				final String sch = rs.getString(3);
				final String tbl = rs.getString(4);
				out.put(constraintKey(constraintSchema, constraintName),
						new ConstraintTable(sch, tbl));
			}
		}
		return out;
	}

	private Map<String, RefConstraint> loadReferentialConstraints() throws SQLException {
		final Map<String, RefConstraint> out = new HashMap<>();
		try (ResultSet rs = connection.createStatement().executeQuery(SQL_REFERENTIAL_CONSTRAINTS)) {
			while (rs.next()) {
				final String constraintSchema = rs.getString(1);
				final String constraintName = rs.getString(2);
				out.put(constraintKey(constraintSchema, constraintName), new RefConstraint(
						constraintSchema,
						constraintName,
						rs.getString(3),
						rs.getString(4),
						rs.getString(5),
						rs.getString(6)
				));
			}
		}
		return out;
	}

	private Map<String, List<KeyColumn>> loadKeyColumnUsage() throws SQLException {
		final Map<String, List<KeyColumn>> out = new HashMap<>();
		try (ResultSet rs = connection.createStatement().executeQuery(SQL_KEY_COLUMN_USAGE)) {
			while (rs.next()) {
				final String key = constraintKey(rs.getString(1), rs.getString(2));
				out.computeIfAbsent(key, ignored -> new ArrayList<>())
						.add(new KeyColumn(rs.getString(5), rs.getInt(6)));
			}
		}
		for (List<KeyColumn> cols : out.values()) {
			cols.sort(Comparator.comparingInt(c -> c.ordinal));
		}
		return out;
	}

	private static String constraintKey(String schema, String name) {
		final String s = schema == null ? "" : schema.toLowerCase(Locale.ROOT);
		final String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
		return s + '\0' + n;
	}

	private record ConstraintTable(String tableSchema, String tableName) {
	}

	private record RefConstraint(
			String constraintSchema,
			String constraintName,
			String uniqueConstraintSchema,
			String uniqueConstraintName,
			String deleteRule,
			String updateRule
	) {
	}

	private record KeyColumn(String columnName, int ordinal) {
	}

	@Override
	public ResultSet getTypeInfo() throws SQLException {
		final List<Object[]> rows = new ArrayList<>();
		for (SqlType t : SqlType.values()) {
			rows.add(new Object[]{
					t.name(),
                    GridJdbcTypeSupport.toJdbcType(t),
                    0,
					null,
					null,
					null,
                    typeNullable,
					Boolean.FALSE,
                    typeSearchable,
					Boolean.FALSE,
					Boolean.FALSE,
					Boolean.FALSE,
					t.name(),
                    0,
                    0,
                    0,
                    0,
                    10
			});
		}
		return MetaResultSets.typeInfo(connection, rows);
	}

	@Override
	public ResultSet getIndexInfo(
			String catalog,
			String schema,
			String table,
			boolean unique,
			boolean approximate
	) throws SQLException {
		final List<Object[]> rows = new ArrayList<>();
		try (ResultSet rs = connection.createStatement().executeQuery(SQL_STATISTICS)) {
			while (rs.next()) {
				final String sch = rs.getString(1);
				final String tbl = rs.getString(2);
				if (schema != null && !schema.equalsIgnoreCase(sch)) {
					continue;
				}
				if (table != null && !table.equalsIgnoreCase(tbl)) {
					continue;
				}
				final int nonUnique = rs.getInt(3);
				if (unique && nonUnique != 0) {
					continue;
				}
				rows.add(new Object[]{
						CATALOG_NAME,
						sch,
						tbl,
                        nonUnique != 0,
						null,
						rs.getString(4),
                        tableIndexOther,
                        (short) rs.getInt(5),
						rs.getString(6),
						null,
                        0,
                        0,
						null
				});
			}
		}
		return MetaResultSets.indexInfo(connection, rows);
	}

	@Override
	public boolean supportsResultSetType(int type) {
		return type == ResultSet.TYPE_FORWARD_ONLY;
	}

	@Override
	public boolean supportsResultSetConcurrency(int type, int concurrency) {
		return type == ResultSet.TYPE_FORWARD_ONLY && concurrency == ResultSet.CONCUR_READ_ONLY;
	}

	@Override
	public boolean ownUpdatesAreVisible(int type) {
		return false;
	}

	@Override
	public boolean ownDeletesAreVisible(int type) {
		return false;
	}

	@Override
	public boolean ownInsertsAreVisible(int type) {
		return false;
	}

	@Override
	public boolean othersUpdatesAreVisible(int type) {
		return false;
	}

	@Override
	public boolean othersDeletesAreVisible(int type) {
		return false;
	}

	@Override
	public boolean othersInsertsAreVisible(int type) {
		return false;
	}

	@Override
	public boolean updatesAreDetected(int type) {
		return false;
	}

	@Override
	public boolean deletesAreDetected(int type) {
		return false;
	}

	@Override
	public boolean insertsAreDetected(int type) {
		return false;
	}

	@Override
	public boolean supportsBatchUpdates() {
		return true;
	}

	@Override
	public ResultSet getUDTs(String catalog, String schemaPattern, String typeNamePattern, int[] types)
			throws SQLException {
		return emptyResult(List.of("TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "CLASS_NAME", "DATA_TYPE"));
	}

	@Override
	public Connection getConnection() {
		return connection;
	}

	@Override
	public boolean supportsSavepoints() {
		return true;
	}

	@Override
	public boolean supportsNamedParameters() {
		return false;
	}

	@Override
	public boolean supportsMultipleOpenResults() {
		return false;
	}

	@Override
	public boolean supportsGetGeneratedKeys() {
		return false;
	}

	@Override
	public ResultSet getSuperTypes(String catalog, String schemaPattern, String typeNamePattern)
			throws SQLException {
		return emptyResult(List.of("TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "SUPERTYPE_CAT"));
	}

	@Override
	public ResultSet getSuperTables(String catalog, String schemaPattern, String tableNamePattern)
			throws SQLException {
		return emptyResult(List.of("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "SUPERTABLE_NAME"));
	}

	@Override
	public ResultSet getAttributes(
			String catalog,
			String schemaPattern,
			String typeNamePattern,
			String attributeNamePattern
	) throws SQLException {
		return emptyResult(List.of("TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "ATTR_NAME"));
	}

	@Override
	public boolean supportsResultSetHoldability(int holdability) {
		return holdability == ResultSet.HOLD_CURSORS_OVER_COMMIT;
	}

	@Override
	public int getResultSetHoldability() {
		return ResultSet.HOLD_CURSORS_OVER_COMMIT;
	}

	@Override
	public int getDatabaseMajorVersion() {
		return 1;
	}

	@Override
	public int getDatabaseMinorVersion() {
		return 0;
	}

	@Override
	public int getJDBCMajorVersion() {
		return GridConnection.JDBC_MAJOR;
	}

	@Override
	public int getJDBCMinorVersion() {
		return GridConnection.JDBC_MINOR;
	}

	@Override
	public int getSQLStateType() {
		return sqlStateSQL;
	}

	@Override
	public boolean locatorsUpdateCopy() {
		return false;
	}

	@Override
	public boolean supportsStatementPooling() {
		return false;
	}

	@Override
	public RowIdLifetime getRowIdLifetime() {
		return RowIdLifetime.ROWID_UNSUPPORTED;
	}

	@Override
	public boolean supportsStoredFunctionsUsingCallSyntax() {
		return false;
	}

	@Override
	public boolean autoCommitFailureClosesAllResultSets() {
		return false;
	}

	@Override
	public ResultSet getClientInfoProperties() throws SQLException {
		return emptyResult(List.of("NAME", "MAX_LEN", "DEFAULT_VALUE", "DESCRIPTION"));
	}

	@Override
	public ResultSet getFunctions(String catalog, String schemaPattern, String functionNamePattern)
			throws SQLException {
		return emptyResult(List.of("FUNCTION_CAT", "FUNCTION_SCHEM", "FUNCTION_NAME", "REMARKS",
				"FUNCTION_TYPE", "SPECIFIC_NAME"));
	}

	@Override
	public ResultSet getFunctionColumns(
			String catalog,
			String schemaPattern,
			String functionNamePattern,
			String columnNamePattern
	) throws SQLException {
		return emptyResult(List.of("FUNCTION_CAT", "FUNCTION_SCHEM", "FUNCTION_NAME", "COLUMN_NAME"));
	}

	@Override
	public ResultSet getPseudoColumns(
			String catalog,
			String schemaPattern,
			String tableNamePattern,
			String columnNamePattern
	) throws SQLException {
		return emptyResult(List.of("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME"));
	}

	@Override
	public boolean generatedKeyAlwaysReturned() {
		return false;
	}

	@Override
	public <T> T unwrap(Class<T> iface) throws SQLException {
		if (iface.isInstance(this)) {
			return iface.cast(this);
		}
		throw new SQLException("Not a wrapper for " + iface);
	}

	@Override
	public boolean isWrapperFor(Class<?> iface) {
		return iface.isInstance(this);
	}

	private ResultSet emptyResult(List<String> columns) throws SQLException {
		return MetaResultSets.empty(connection, columns);
	}
}
