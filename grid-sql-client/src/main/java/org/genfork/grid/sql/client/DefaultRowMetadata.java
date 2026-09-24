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
package org.genfork.grid.sql.client;

import org.genfork.grid.catalog.SqlType;
import org.genfork.grid.sql.SqlResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Default {@link RowMetadata} from {@link SqlResult.ColumnMeta}.
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public final class DefaultRowMetadata implements RowMetadata {
	private final List<SqlResult.ColumnMeta> columns;
	private final List<String> names;

	public DefaultRowMetadata(List<SqlResult.ColumnMeta> columns) {
		this.columns = List.copyOf(columns);

		names = new ArrayList<>(columns.size());
		for (SqlResult.ColumnMeta c : columns) {
			names.add(c.name());
		}
	}

	@Override
	public List<String> getColumnNames() {
		return names;
	}

	@Override
	public SqlType getColumnType(int index) {
		return columns.get(index).type();
	}

	@Override
	public SqlType getColumnType(String name) {
		return columns.get(indexOf(name)).type();
	}

	@Override
	public int getColumnCount() {
		return columns.size();
	}

	@Override
	public Boolean getNullable(int index) {
		return columns.get(index).nullableOrNull();
	}

	@Override
	public int getPrecision(int index) {
		return columns.get(index).precision();
	}

	@Override
	public String getTableName(int index) {
		return columns.get(index).tableName();
	}

	@Override
	public String getSchemaName(int index) {
		return columns.get(index).schemaName();
	}

	@Override
	public SqlResult.ColumnMeta getColumnMeta(int index) {
		return columns.get(index);
	}

	int indexOf(String name) {
		for (int i = 0; i < names.size(); i++) {
			if (names.get(i).equalsIgnoreCase(name)) {
				return i;
			}
		}
		throw new IllegalArgumentException("Unknown column: " + name);
	}
}
