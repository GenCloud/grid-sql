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
import org.genfork.grid.sql.ColumnMetas;
import org.genfork.grid.sql.SqlResult;

import java.util.List;

/**
 * Column names/types for a result set.
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public interface RowMetadata {
	List<String> getColumnNames();

	SqlType getColumnType(int index);

	SqlType getColumnType(String name);

	int getColumnCount();

	default Boolean getNullable(int index) {
		return null;
	}

	default int getPrecision(int index) {
		return ColumnMetas.precision(getColumnType(index));
	}

	default String getTableName(int index) {
		return "";
	}

	default String getSchemaName(int index) {
		return "";
	}

	default SqlResult.ColumnMeta getColumnMeta(int index) {
		return null;
	}
}
