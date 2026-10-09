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

import java.sql.SQLException;

/**
 * JDBC exception mapping for Sync* / runtime failures.
 * <p>
 * Blocking awaits live in {@link org.genfork.grid.sql.client.sync.SyncAwait} —
 * this type only maps throwables to {@link SQLException}.
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
public final class JdbcSync {
	private static final String SQLSTATE_CHECK = "23514";
	private static final String SQLSTATE_UNIQUE = "23505";
	private static final String SQLSTATE_FK = "23503";
	private static final String SQLSTATE_INVALID_TX_STATE = "25001";
	private static final String SQLSTATE_GENERAL = "HY000";
	private static final String MARKER_CHECK = "CHECK violation";
	private static final String MARKER_UNIQUE = "UNIQUE";
	private static final String MARKER_DUPLICATE = "duplicate";
	private static final String MARKER_FK = "FOREIGN KEY";
	private static final String MARKER_FK_VIOLATION = "foreign key";
	private static final String MARKER_DDL_IN_TX = "DDL not allowed inside an open transaction";
	private static final String HINT_DDL_IN_TX =
			"DDL not allowed inside an open transaction "
					+ "(use connection autocommit ON or COMMIT before DDL; DBeaver: enable Auto-commit)";

	private JdbcSync() {
	}

	public static SQLException toSqlException(Throwable cause) {
		if (cause instanceof SQLException sql) {
			return sql;
		}
		final String raw = cause == null || cause.getMessage() == null
				? String.valueOf(cause)
				: cause.getMessage();
		final String message = clarifyMessage(raw);
		return new SQLException(message, mapSqlState(raw), cause);
	}

	static String clarifyMessage(String message) {
		if (message != null && message.contains(MARKER_DDL_IN_TX)) {
			return HINT_DDL_IN_TX;
		}
		return message;
	}

	static String mapSqlState(String message) {
		if (message == null) {
			return SQLSTATE_GENERAL;
		}
		if (message.contains(MARKER_DDL_IN_TX)) {
			return SQLSTATE_INVALID_TX_STATE;
		}
		if (message.contains(MARKER_CHECK)) {
			return SQLSTATE_CHECK;
		}
		if (message.contains(MARKER_FK) || message.toLowerCase().contains(MARKER_FK_VIOLATION)) {
			return SQLSTATE_FK;
		}
		final String lower = message.toLowerCase();
		if (message.contains(MARKER_UNIQUE) || lower.contains(MARKER_DUPLICATE)) {
			return SQLSTATE_UNIQUE;
		}
		return SQLSTATE_GENERAL;
	}
}