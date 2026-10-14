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
package org.genfork.grid.sql;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.NavigableMap;

import org.genfork.grid.catalog.SqlTypeCoercion;

/**
 * Positional ? bind materialization into SQL literals (injection-safe quoting).
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlBinds {
	private static final String LIT_TIMESTAMP_PREFIX = "TIMESTAMP '";
	private static final String LIT_TIMESTAMPTZ_PREFIX = "TIMESTAMPTZ '";
	private static final String LIT_DATE_PREFIX = "DATE '";
	private static final String LIT_TIME_PREFIX = "TIME '";
	private static final char LIT_SUFFIX = '\'';
	private static final DateTimeFormatter TIME_LIT = DateTimeFormatter.ISO_LOCAL_TIME;

	private SqlBinds() {
	}

	public static String materialize(String sql, Object[] binds) {
		if (binds == null || binds.length == 0) {
			return sql;
		}
		final StringBuilder out = new StringBuilder(sql.length() + 16);
		final int consumed = appendMaterialized(sql, binds, 0, out);
		if (consumed != binds.length) {
			throw new IllegalArgumentException(
					"Bind count mismatch: placeholders=" + consumed + " binds=" + binds.length);
		}
		return out.toString();
	}

	/**
	 * Replace {@code ?} in a SQL fragment using binds from {@code startIndex}, without requiring
	 * that all bind slots are consumed (SET binds may precede a WHERE fragment).
	 */
	public static String materializeRange(String sql, Object[] binds, int startIndex) {
		if (sql == null) {
			return null;
		}
		if (binds == null || binds.length == 0 || sql.indexOf('?') < 0) {
			return sql;
		}
		if (startIndex < 0) {
			throw new IllegalArgumentException("bind startIndex must be >= 0");
		}
		final StringBuilder out = new StringBuilder(sql.length() + 16);
		appendMaterialized(sql, binds, startIndex, out);
		return out.toString();
	}

	/**
	 * @return number of placeholders replaced
	 */
	private static int appendMaterialized(String sql, Object[] binds, int startIndex, StringBuilder out) {
		int bi = startIndex;
		boolean inStr = false;
		for (int i = 0; i < sql.length(); i++) {
			final char c = sql.charAt(i);
			if (c == '\'') {
				out.append(c);
				if (inStr && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
					out.append('\'');
					i++;
				} else {
					inStr = !inStr;
				}
				continue;
			}

			if (!inStr && c == '?') {
				if (bi >= binds.length) {
					throw new IllegalArgumentException("Not enough bind values for SQL placeholders");
				}
				out.append(toSqlLiteral(binds[bi++]));
				continue;
			}
			out.append(c);
		}
		return bi - startIndex;
	}

	public static String toSqlLiteral(Object value) {
		switch (value) {
			case null -> {
				return "NULL";
			}
			case Boolean b -> {
				return b ? "TRUE" : "FALSE";
			}
			case Number n -> {
				// Prefer signed decimal form once grammar accepts '-'? INT (VALUES + predicates).
				return n.toString();
			}
			case LocalDateTime ldt -> {
				return LIT_TIMESTAMP_PREFIX + SqlTypeCoercion.toTimestampString(ldt) + LIT_SUFFIX;
			}
			case Instant i -> {
				return LIT_TIMESTAMPTZ_PREFIX + i.toString() + LIT_SUFFIX;
			}
			case LocalDate d -> {
				return LIT_DATE_PREFIX + d + LIT_SUFFIX;
			}
			case LocalTime t -> {
				return LIT_TIME_PREFIX + t.format(TIME_LIT) + LIT_SUFFIX;
			}
			case byte[] bytes -> {
				return SqlBytesLiterals.formatXHex(bytes);
			}
			default -> {
			}
		}

		final String s = String.valueOf(value);
		return "'" + s.replace("'", "''") + "'";
	}

	public static Object[] dense(NavigableMap<Integer, Object> byIndex) {
		if (byIndex == null || byIndex.isEmpty()) {
			return new Object[0];
		}

		final int max = byIndex.lastKey();
		if (max < 0) {
			throw new IllegalArgumentException("bind index must be >= 0");
		}

		final Object[] out = new Object[max + 1];
		for (int i = 0; i <= max; i++) {
			if (!byIndex.containsKey(i)) {
				throw new IllegalArgumentException("Missing bind at index " + i);
			}
			out[i] = byIndex.get(i);
		}

		return out;
	}
}
