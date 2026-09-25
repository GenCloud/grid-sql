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

import java.util.Locale;

import org.genfork.grid.catalog.SqlType;

/**
 * Dialect builtin SQL function names and default return-type hints.
 * <p>
 * Evaluation is registered in {@link SqlBuiltinEvalUtil} — add a constant here and one
 * registry entry; do not grow {@code if} chains at call sites.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public enum SqlBuiltinNames {
	COALESCE(SqlType.VARCHAR),
	NOW(SqlType.TIMESTAMPTZ),
	CURRENT_TIMESTAMP(SqlType.TIMESTAMPTZ),
	CURRENT_DATE(SqlType.DATE);

	private final SqlType returnType;

	SqlBuiltinNames(SqlType returnType) {
		this.returnType = returnType;
	}

	/** Wire / SQL keyword token (enum constant name). */
	public String sqlToken() {
		return name();
	}

	public SqlType returnType() {
		return returnType;
	}

	/** Resolve dialect builtin or {@code null}. */
	public static SqlBuiltinNames find(String name) {
		if (name == null || name.isBlank()) {
			return null;
		}
		final String key = name.trim().toUpperCase(Locale.ROOT);
		for (SqlBuiltinNames b : values()) {
			if (b.name().equals(key)) {
				return b;
			}
		}
		return null;
	}

	public static boolean isBuiltin(String name) {
		return find(name) != null;
	}

	public static SqlBuiltinNames require(String name) {
		final SqlBuiltinNames b = find(name);
		if (b == null) {
			throw new IllegalArgumentException("not a builtin: " + name);
		}
		return b;
	}
}