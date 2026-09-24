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

/**
 * Optional single-table edit context for scrollable / updatable JDBC result sets (DBeaver Data Editor).
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
public record GridTableEditContext(String schema, String table, String pkColumn, int pkIndex) {
    public GridTableEditContext(String schema, String table, String pkColumn, int pkIndex) {
        this.schema = schema == null || schema.isBlank() ? "public" : schema;
        this.table = table;
        this.pkColumn = pkColumn;
        this.pkIndex = pkIndex;
    }

    public String qualifiedTable() {
        if ("public".equalsIgnoreCase(schema)) {
            return table;
        }
        return schema + "." + table;
    }
}
