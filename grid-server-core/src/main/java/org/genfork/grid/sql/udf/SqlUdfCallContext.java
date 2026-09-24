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
package org.genfork.grid.sql.udf;

import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.SqlSession;

import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Per-statement call context for scalar UDFs (session + nested SQL runner).
 * <p>
 * Bound on the logic VT that runs {@code SqlEngine.execute}; cleared in finally.
 * Nested {@link #execute(String)} reuses the same session / TX unit.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlUdfCallContext {
	private static final ThreadLocal<SqlUdfCallContext> CURRENT = new ThreadLocal<>();
	private static final String ERR_UNBOUND = "SqlUdfCallContext not bound for UDF evaluation";

	private final SqlSession session;
	private final BiFunction<SqlSession, String, SqlResult> sqlRunner;

	private SqlUdfCallContext(SqlSession session, BiFunction<SqlSession, String, SqlResult> sqlRunner) {
		this.session = Objects.requireNonNull(session, "session");
		this.sqlRunner = Objects.requireNonNull(sqlRunner, "sqlRunner");
	}

	/**
	 * Push a new context; returns the previous value (nullable) for {@link #restore}.
	 */
	public static SqlUdfCallContext push(
			SqlSession session,
			BiFunction<SqlSession, String, SqlResult> sqlRunner
	) {
		final SqlUdfCallContext prev = CURRENT.get();
		CURRENT.set(new SqlUdfCallContext(session, sqlRunner));
		return prev;
	}

	/** Restore previous context after nested execute (null clears). */
	public static void restore(SqlUdfCallContext previous) {
		if (previous == null) {
			CURRENT.remove();
		} else {
			CURRENT.set(previous);
		}
	}

	/** Current context or illegal state when unbound. */
	public static SqlUdfCallContext require() {
		final SqlUdfCallContext ctx = CURRENT.get();
		if (ctx == null) {
			throw new IllegalStateException(ERR_UNBOUND);
		}
		return ctx;
	}

	/** Nullable peek (tests / diagnostics). */
	public static SqlUdfCallContext currentOrNull() {
		return CURRENT.get();
	}

	public SqlSession session() {
		return session;
	}

	/**
	 * Run SQL on the bound session (same TX / autocommit unit as the outer statement).
	 */
	public SqlResult execute(String sql) {
		Objects.requireNonNull(sql, "sql");
		return sqlRunner.apply(session, sql);
	}
}