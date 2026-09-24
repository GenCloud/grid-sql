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
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.client.DefaultRowMetadata;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds in-memory JDBC metadata {@link ResultSet}s (no second wire round-trip).
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
final class MetaResultSets {
    private MetaResultSets() {
    }

    static ResultSet empty(GridConnection connection, List<String> columns) {
        return of(connection, columns, List.of());
    }

    static ResultSet catalogs(GridConnection connection, List<Object[]> rows) {
        return of(connection, List.of("TABLE_CAT"), rows);
    }

    static ResultSet schemas(GridConnection connection, List<Object[]> rows) {
        return of(connection, List.of("TABLE_SCHEM", "TABLE_CATALOG"), rows);
    }

    static ResultSet tableTypes(GridConnection connection, List<Object[]> rows) {
        return of(connection, List.of("TABLE_TYPE"), rows);
    }

    static ResultSet tables(GridConnection connection, List<Object[]> rows) {
        return of(connection, List.of(
                "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "TABLE_TYPE", "REMARKS",
                "TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "SELF_REFERENCING_COL_NAME", "REF_GENERATION"
        ), rows);
    }

    static ResultSet columns(GridConnection connection, List<Object[]> rows) {
        return of(connection, List.of(
                "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME",
                "COLUMN_SIZE", "BUFFER_LENGTH", "DECIMAL_DIGITS", "NUM_PREC_RADIX", "NULLABLE",
                "REMARKS", "COLUMN_DEF", "SQL_DATA_TYPE", "SQL_DATETIME_SUB", "CHAR_OCTET_LENGTH",
                "ORDINAL_POSITION", "IS_NULLABLE", "SCOPE_CATALOG", "SCOPE_SCHEMA", "SCOPE_TABLE",
                "SOURCE_DATA_TYPE", "IS_AUTOINCREMENT", "IS_GENERATEDCOLUMN"
        ), rows);
    }

    static ResultSet primaryKeys(GridConnection connection, List<Object[]> rows) {
        return of(connection, List.of(
                "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "KEY_SEQ", "PK_NAME"
        ), rows);
    }

    static ResultSet importedKeys(GridConnection connection, List<Object[]> rows) {
        return of(connection, List.of(
                "PKTABLE_CAT", "PKTABLE_SCHEM", "PKTABLE_NAME", "PKCOLUMN_NAME",
                "FKTABLE_CAT", "FKTABLE_SCHEM", "FKTABLE_NAME", "FKCOLUMN_NAME",
                "KEY_SEQ", "UPDATE_RULE", "DELETE_RULE", "FK_NAME", "PK_NAME", "DEFERRABILITY"
        ), rows);
    }

    static ResultSet indexInfo(GridConnection connection, List<Object[]> rows) {
        return of(connection, List.of(
                "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "NON_UNIQUE", "INDEX_QUALIFIER",
                "INDEX_NAME", "TYPE", "ORDINAL_POSITION", "COLUMN_NAME", "ASC_OR_DESC",
                "CARDINALITY", "PAGES", "FILTER_CONDITION"
        ), rows);
    }

    static ResultSet typeInfo(GridConnection connection, List<Object[]> rows) {
        return of(connection, List.of(
                "TYPE_NAME", "DATA_TYPE", "PRECISION", "LITERAL_PREFIX", "LITERAL_SUFFIX",
                "CREATE_PARAMS", "NULLABLE", "CASE_SENSITIVE", "SEARCHABLE", "UNSIGNED_ATTRIBUTE",
                "FIXED_PREC_SCALE", "AUTO_INCREMENT", "LOCAL_TYPE_NAME", "MINIMUM_SCALE",
                "MAXIMUM_SCALE", "SQL_DATA_TYPE", "SQL_DATETIME_SUB", "NUM_PREC_RADIX"
        ), rows);
    }

    private static ResultSet of(GridConnection connection, List<String> names, List<Object[]> rows) {
        final List<SqlResult.ColumnMeta> metas = new ArrayList<>(names.size());
        for (String name : names) {
            metas.add(SqlResult.ColumnMeta.of(name, SqlType.VARCHAR));
        }
        final DefaultRowMetadata meta = new DefaultRowMetadata(metas);
        final GridStatement st = new GridStatement(connection);
        return new GridResultSet(st, meta, rows);
    }
}
