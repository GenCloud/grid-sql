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

/**
 * Renders SAVEPOINT control SQL for the product wire (simple unquoted identifiers).
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
final class SqlSavepointSql {
	private static final String SAVEPOINT = "SAVEPOINT ";
	private static final String ROLLBACK_TO = "ROLLBACK TO SAVEPOINT ";
	private static final String RELEASE = "RELEASE SAVEPOINT ";
	private static final String ERR_NAME = "savepoint name required";
	private static final String ERR_IDENT =
			"savepoint name must be a simple SQL identifier [A-Za-z_][A-Za-z0-9_]*";

	private SqlSavepointSql() {
	}

	static String savepoint(String name) {
		return SAVEPOINT + requireIdent(name);
	}

	static String rollbackTo(String name) {
		return ROLLBACK_TO + requireIdent(name);
	}

	static String release(String name) {
		return RELEASE + requireIdent(name);
	}

	static String requireIdent(String name) {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException(ERR_NAME);
		}
		final String trimmed = name.trim();
		if (!isSimpleIdent(trimmed)) {
			throw new IllegalArgumentException(ERR_IDENT);
		}
		return trimmed;
	}

	private static boolean isSimpleIdent(String name) {
		final char first = name.charAt(0);
		if (!(first == '_' || (first >= 'A' && first <= 'Z') || (first >= 'a' && first <= 'z'))) {
			return false;
		}
		for (int i = 1; i < name.length(); i++) {
			final char c = name.charAt(i);
			if (!(c == '_'
					|| (c >= 'A' && c <= 'Z')
					|| (c >= 'a' && c <= 'z')
					|| (c >= '0' && c <= '9'))) {
				return false;
			}
		}
		return true;
	}
}