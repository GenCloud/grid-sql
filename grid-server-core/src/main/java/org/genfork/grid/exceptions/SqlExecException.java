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
package org.genfork.grid.exceptions;

/**
 * Generic EXEC failure with SQL/bind context in the message.
 * <p>
 * Prefer {@link org.genfork.grid.sql.SqlExecErrorContextUtil#enrich} at the engine boundary —
 * typed failures (lock / replica / unique / security) keep their original runtime type.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
public class SqlExecException extends RuntimeException {
	private final String sql;
	private final int bindCount;

	public SqlExecException(String message, Throwable cause, String sql, int bindCount) {
		super(message, cause);
		this.sql = sql;
		this.bindCount = bindCount;
	}

	public String sql() {
		return sql;
	}

	public int bindCount() {
		return bindCount;
	}
}
