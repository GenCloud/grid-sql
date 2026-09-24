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
package org.genfork.grid.sql.client.ops;

import java.util.Arrays;
import java.util.Objects;

/**
 * SQL text plus positional binds for ordered multi-step execution.
 *
 * @param sql   non-blank SQL
 * @param binds positional args (defensive copy; never {@code null})
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public record BoundSql(String sql, Object[] binds) {
	private static final Object[] EMPTY_BINDS = new Object[0];
	private static final String ERR_SQL_REQUIRED = "sql required";

	/**
	 * Compact constructor: copies {@code binds}, treats {@code null} as empty, requires non-blank SQL.
	 */
	public BoundSql {
		if (sql == null || sql.isBlank()) {
			throw new IllegalArgumentException(ERR_SQL_REQUIRED);
		}
		binds = binds == null || binds.length == 0 ? EMPTY_BINDS : Arrays.copyOf(binds, binds.length);
	}

	/**
	 * Factory with varargs binds ({@code null} array to empty).
	 */
	public static BoundSql of(String sql, Object... binds) {
		return new BoundSql(sql, binds);
	}

	@Override
	public Object[] binds() {
		return binds.length == 0 ? EMPTY_BINDS : Arrays.copyOf(binds, binds.length);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof BoundSql other)) {
			return false;
		}
		return Objects.equals(sql, other.sql) && Arrays.equals(binds, other.binds);
	}

	@Override
	public int hashCode() {
		return 31 * Objects.hashCode(sql) + Arrays.hashCode(binds);
	}

	@Override
	public String toString() {
		return "BoundSql[sql=" + sql + ", binds=" + Arrays.toString(binds) + "]";
	}
}