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

/**
 * JDBC URL helpers for {@code jdbc:grid://} tooling URLs.
 * <p>
 * Canonical multi-host form (same authority as SPI):
 * {@code jdbc:grid://user:pass@h1:15432,h2:15433/public}. No {@code ?hosts=}.
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
public final class GridJdbcUrls {
	public static final String JDBC_PREFIX = "jdbc:";
	public static final String GRID_SCHEME_PREFIX = "grid://";
	public static final String JDBC_GRID_PREFIX = JDBC_PREFIX + GRID_SCHEME_PREFIX;

	private GridJdbcUrls() {
	}

	public static boolean accepts(String url) {
		if (url == null) {
			return false;
		}
		final String trimmed = url.trim();
		return trimmed.regionMatches(true, 0, JDBC_GRID_PREFIX, 0, JDBC_GRID_PREFIX.length())
				|| trimmed.regionMatches(true, 0, GRID_SCHEME_PREFIX, 0, GRID_SCHEME_PREFIX.length());
	}

	/**
	 * Strip optional jdbc: so GridSqlUri can parse.
	 */
	public static String toGridUrl(String url) {
		if (url == null) {
			throw new IllegalArgumentException("url required");
		}
		final String trimmed = url.trim();
		if (trimmed.regionMatches(true, 0, JDBC_PREFIX, 0, JDBC_PREFIX.length())) {
			return trimmed.substring(JDBC_PREFIX.length());
		}
		return trimmed;
	}
}
