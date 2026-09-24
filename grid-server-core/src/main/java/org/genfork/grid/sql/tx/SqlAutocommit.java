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
package org.genfork.grid.sql.tx;

import org.genfork.grid.sql.SqlSession;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Ephemeral TX unit for SQL autocommit DML: stage then {@link SqlTxCommitter#commit}.
 * <p>
 * Routes product UPSERT/UPDATE/DELETE through the same durable OpLog/orchid path as
 * explicit {@code BEGIN}/{@code COMMIT}, instead of {@code TableStore.upsert} to
 * per-op {@code recordCommittedBlocking}.
 *
 * @author: GenCloud
 * @date: 2026/01
 * @since: 1.0
 */
public final class SqlAutocommit {
	private SqlAutocommit() {
	}

	/**
	 * If a TX is already open, runs {@code body} only. Otherwise begin, body, then commit
	 * (rollback on failure).
	 */
	public static <T> T runInUnit(SqlSession session, SqlTxCommitter committer, Supplier<T> body) {
		Objects.requireNonNull(session, "session");
		Objects.requireNonNull(committer, "committer");
		Objects.requireNonNull(body, "body");
		if (session.inTransaction()) {
			return body.get();
		}
		session.beginTx();
		try {
			final T result = body.get();
			committer.commit(session);
			return result;
		} catch (RuntimeException ex) {
			if (session.inTransaction()) {
				try {
					committer.rollback(session);
				} catch (RuntimeException ignored) {
					// preserve original failure
				}
			}
			throw ex;
		}
	}
}
