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

import java.util.Locale;

/**
 * Tooling helpers for JDBC {@link java.sql.DatabaseMetaData} pattern matching and
 * information_schema table-type mapping.
 * <p>
 * Lives in {@code org.genfork.grid.jdbc} (DBeaver/IDE tooling) — not on the product SQL path.
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
public final class GridJdbcMetaDataSupport {
	/** information_schema {@code table_type} for base tables. */
	public static final String INFORMATION_SCHEMA_BASE_TABLE = "BASE TABLE";
	/** information_schema {@code table_type} for views. */
	public static final String INFORMATION_SCHEMA_VIEW = "VIEW";

	private static final String JDBC_TABLE_TYPE_TABLE = "TABLE";
	private static final String JDBC_TABLE_TYPE_VIEW = "VIEW";
	private static final String PATTERN_ANY = "%";
	private static final String GLOB_ANY = ".*";
	private static final String GLOB_ONE = ".";
	private static final char LIKE_ANY = '%';
	private static final char LIKE_ONE = '_';

	private GridJdbcMetaDataSupport() {
	}

	/**
	 * JDBC catalog pattern match ({@code null}/{@code ""}/{@code "%"} = match all;
	 * {@code %} / {@code _} as SQL LIKE wildcards).
	 */
	public static boolean matchPattern(String pattern, String value) {
		if (pattern == null || pattern.isEmpty() || PATTERN_ANY.equals(pattern)) {
			return true;
		}
		if (value == null) {
			return false;
		}
		final String p = pattern.toLowerCase(Locale.ROOT);
		final String v = value.toLowerCase(Locale.ROOT);
		if (p.indexOf(LIKE_ANY) < 0 && p.indexOf(LIKE_ONE) < 0) {
			return p.equals(v);
		}
		return v.matches(p.replace(String.valueOf(LIKE_ANY), GLOB_ANY)
				.replace(String.valueOf(LIKE_ONE), GLOB_ONE));
	}

	/**
	 * Map information_schema {@code table_type} to JDBC {@code TABLE}/{@code VIEW}.
	 */
	public static String mapTableType(String informationSchemaType) {
		if (INFORMATION_SCHEMA_VIEW.equalsIgnoreCase(informationSchemaType)) {
			return JDBC_TABLE_TYPE_VIEW;
		}
		return JDBC_TABLE_TYPE_TABLE;
	}
}