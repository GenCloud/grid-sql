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

import java.sql.ResultSet;
import java.sql.SQLException;

import org.genfork.grid.sql.client.sync.SyncResult;

/**
 * JDBC helpers for multi-result {@code BATCH_EXEC} sync outcomes.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
public final class GridJdbcResults {
	private GridJdbcResults() {
	}

	/**
	 * Wrap one {@link SyncResult} as a forward-only {@link ResultSet} (restore / tooling).
	 */
	public static ResultSet asResultSet(GridConnection connection, SyncResult syncResult) throws SQLException {
		return asResultSet(connection, syncResult, null);
	}

	/**
	 * Same as {@link #asResultSet(GridConnection, SyncResult)} with optional SQL hint for edit metadata.
	 */
	public static ResultSet asResultSet(GridConnection connection, SyncResult syncResult, String sqlHint)
			throws SQLException {
		if (connection == null) {
			throw new SQLException("connection required", "HY000");
		}
		final GridStatement statement = new GridStatement(connection, true);
		return statement.openSyncResultSet(syncResult, sqlHint);
	}
}
