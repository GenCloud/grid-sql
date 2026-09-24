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

import java.util.Objects;

import org.genfork.grid.sql.client.Statement;

/**
 * Positional bind helpers for {@link Statement}.
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public final class SqlBind {
	private static final String ERR_STATEMENT_REQUIRED = "statement required";

	private SqlBind() {
	}

	/**
	 * Binds {@code binds[i]} at index {@code i} (0-based). Null values are still bound as {@code null}.
	 *
	 * @param st    target statement
	 * @param binds positional values; {@code null} or empty is a no-op
	 * @return {@code st}
	 */
	public static Statement bindAll(Statement st, Object... binds) {
		Objects.requireNonNull(st, ERR_STATEMENT_REQUIRED);
		if (binds == null || binds.length == 0) {
			return st;
		}
		for (int i = 0; i < binds.length; i++) {
			st.bind(i, binds[i]);
		}
		return st;
	}
}