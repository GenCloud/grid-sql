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

import java.sql.DatabaseMetaData;
import java.util.Locale;

/**
 * Maps information_schema FK rule tokens to JDBC {@link DatabaseMetaData} imported-key constants.
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
final class GridJdbcFkMetaSupport {
	private static final String TOKEN_CASCADE = "CASCADE";
	private static final String TOKEN_SET_NULL = "SET NULL";
	private static final String TOKEN_SETNULL = "SETNULL";

	private GridJdbcFkMetaSupport() {
	}

	static short jdbcRule(String sqlToken) {
		if (sqlToken == null || sqlToken.isBlank()) {
			return DatabaseMetaData.importedKeyRestrict;
		}
		final String t = sqlToken.trim().toUpperCase(Locale.ROOT);
		return switch (t) {
			case TOKEN_CASCADE -> DatabaseMetaData.importedKeyCascade;
			case TOKEN_SET_NULL, TOKEN_SETNULL -> DatabaseMetaData.importedKeySetNull;
            default -> DatabaseMetaData.importedKeyRestrict;
		};
	}
}
