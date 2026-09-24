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
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.SqlSession;
import org.genfork.grid.sql.SqlStatementTag;
import org.genfork.grid.sql.ast.DmlAst.UpdateSql;
import org.genfork.grid.store.TableStore;

/**
 * RETURNING / UPDATE key-list helpers for {@link SqlDmlExecutor}.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlDmlReturningOps {
	private SqlDmlReturningOps() {
	}

	static List<byte[]> updateKeys(TableStore store, UpdateSql s, String pkName) {
		if (s.rmw() != null) {
			return List.of(store.keyBytesForPk(s.rmw().pkValue()));
		}
		if (s.pkColumnOrNull() != null && pkName.equalsIgnoreCase(s.pkColumnOrNull())) {
			return List.of(store.keyBytesForPk(s.pkValueOrNull()));
		}
		return List.copyOf(store.keysMatching(s.whereSql()));
	}

	static SqlResult updateResult(
			SqlSession session,
			String table,
			TableStore store,
			UpdateSql s,
			List<byte[]> keys,
			long affected
	) {
		if (s.returning().isEmpty()) {
			return SqlResult.affected(SqlStatementTag.UPDATE, affected);
		}
		final List<byte[]> values = new ArrayList<>(keys.size());
		for (byte[] key : keys) {
			final byte[] value = SqlDmlLockOps.existingBytes(session, table, store, key);
			if (value != null) {
				values.add(value);
			}
		}
		return returningResult(store, SqlStatementTag.UPDATE, s.returning(), values);
	}

	static SqlResult returningResult(
			TableStore store,
			SqlStatementTag tag,
			List<String> requested,
			List<byte[]> values
	) {
		final boolean star = requested.size() == 1 && "*".equals(requested.getFirst());
		final List<String> projection = star
				? store.schema().columns().stream().map(ColumnDef::name).toList()
				: requested;
		final List<SqlResult.ColumnMeta> columns = new ArrayList<>(projection.size());
		for (String name : projection) {
			final ColumnDef column = store.schema().requireColumn(name);
			columns.add(SqlResult.ColumnMeta.ofCatalog(
					column.name(), column.type(), column.nullable(),
					store.schema().tableName(), null));
		}
		final List<Object[]> rows = new ArrayList<>(values.size());
		for (byte[] value : values) {
			rows.add(store.projectBytes(value, projection));
		}
		return SqlResult.resultSet(tag, columns, rows);
	}
}
