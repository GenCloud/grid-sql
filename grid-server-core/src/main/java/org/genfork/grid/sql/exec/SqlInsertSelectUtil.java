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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.genfork.grid.catalog.ColumnDef;
import org.genfork.grid.catalog.TableSchema;

/**
 * Column mapping for {@code INSERT … SELECT} at the DML edge (named map for encode / FK / conflict).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.1
 */
public final class SqlInsertSelectUtil {
	private SqlInsertSelectUtil() {
	}

	/**
	 * Build insert named values from one SELECT result row.
	 * <p>
	 * When {@code insertColumns} is empty, SELECT width must match table column count
	 * and values bind in table ordinal order.
	 */
	public static Map<String, Object> namedFromSelectRow(
			TableSchema schema,
			List<String> insertColumns,
			Object[] selectRow
	) {
		Objects.requireNonNull(schema, "schema");
		Objects.requireNonNull(selectRow, "selectRow");
		final List<String> targets = resolveTargetColumns(schema, insertColumns, selectRow.length);
		if (targets.size() != selectRow.length) {
			throw new IllegalArgumentException(
					"INSERT SELECT column/value count mismatch: expected "
							+ targets.size() + ", got " + selectRow.length);
		}
		final Map<String, Object> named = new LinkedHashMap<>(targets.size());
		for (int i = 0; i < targets.size(); i++) {
			named.put(targets.get(i), selectRow[i]);
		}
		return named;
	}

	private static List<String> resolveTargetColumns(
			TableSchema schema,
			List<String> insertColumns,
			int selectWidth
	) {
		if (insertColumns != null && !insertColumns.isEmpty()) {
			return insertColumns;
		}
		final List<ColumnDef> cols = schema.columns();
		if (cols.size() != selectWidth) {
			throw new IllegalArgumentException(
					"INSERT SELECT without column list requires SELECT width = table columns ("
							+ cols.size() + "), got " + selectWidth);
		}
		final List<String> names = new ArrayList<>(cols.size());
		for (ColumnDef col : cols) {
			names.add(col.name());
		}
		return names;
	}
}
