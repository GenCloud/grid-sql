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

import java.util.Objects;

import org.genfork.grid.sql.SqlSession;

/**
 * Temporary {@link SqlSession#currentSchema()} push/pop for nested SQL (trigger bodies, etc.).
 * <p>
 * Bare table refs resolve only against session schema; callers that need a table's schema
 * for nested DML must scope via this util — never ad-hoc try/finally around
 * {@link SqlSession#setCurrentSchema(String)}.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
public final class SqlSessionSchemaScopeUtil {
	private static final String ERR_SESSION = "session";
	private static final String ERR_SCHEMA = "schemaName";
	private static final String ERR_ACTION = "action";

	private SqlSessionSchemaScopeUtil() {
	}

	/**
	 * Run {@code action} with {@code schemaName} as session schema, then restore the previous value.
	 */
	public static void runWithSchema(SqlSession session, String schemaName, Runnable action) {
		Objects.requireNonNull(session, ERR_SESSION);
		Objects.requireNonNull(schemaName, ERR_SCHEMA);
		Objects.requireNonNull(action, ERR_ACTION);
		final String previous = session.currentSchema();
		session.setCurrentSchema(schemaName);
		try {
			action.run();
		} finally {
			session.setCurrentSchema(previous);
		}
	}
}
