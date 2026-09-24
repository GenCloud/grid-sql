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

import java.util.NavigableMap;

/**
 * Positional ? bind materialization into SQL literals (injection-safe quoting).
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlBinds {
	private SqlBinds() {
	}

	public static String materialize(String sql, Object[] binds) {
		if (binds == null || binds.length == 0) {
			return sql;
		}

		final StringBuilder out = new StringBuilder(sql.length() + 16);
		int bi = 0;
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

		if (bi != binds.length) {
			throw new IllegalArgumentException(
					"Bind count mismatch: placeholders=" + bi + " binds=" + binds.length);
		}

		return out.toString();
	}

	public static String toSqlLiteral(Object value) {
        switch (value) {
            case null -> {
                return "NULL";
            }
            case Boolean b -> {
                return b ? "TRUE" : "FALSE";
            }
            case Number _ -> {
                return value.toString();
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