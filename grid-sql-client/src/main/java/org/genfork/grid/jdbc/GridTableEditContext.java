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

import java.util.List;

/**
 * Optional single-table edit context for scrollable / updatable JDBC result sets (DBeaver Data Editor).
 * <p>
 * {@link #pkColumn()} is the leading PRIMARY KEY column ({@code ordinal_position = 1}).
 * {@code deleteRow}/{@code updateRow} use that leading column in WHERE — documented partial-PK
 * mode for composite keys (matches {@code DELETE … WHERE pk1=?} Data Editor Save).
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
public record GridTableEditContext(
		String schema,
		String table,
		String pkColumn,
		int pkIndex,
		List<String> pkColumns
) {
	public GridTableEditContext {
		schema = schema == null || schema.isBlank() ? "public" : schema;
		pkColumns = pkColumns == null || pkColumns.isEmpty()
				? (pkColumn == null ? List.of() : List.of(pkColumn))
				: List.copyOf(pkColumns);
	}

	public GridTableEditContext(String schema, String table, String pkColumn, int pkIndex) {
		this(schema, table, pkColumn, pkIndex, pkColumn == null ? List.of() : List.of(pkColumn));
	}

	/**
	 * Always {@code schema.table} — {@code public} is a normal schema (no bare-name special case).
	 */
	public String qualifiedTable() {
		return schema + "." + table;
	}
}
