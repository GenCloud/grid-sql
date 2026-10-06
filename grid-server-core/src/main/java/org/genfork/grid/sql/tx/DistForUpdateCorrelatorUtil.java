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
import java.util.concurrent.atomic.AtomicLong;

/**
 * Peer-lock correlator ids for Dist FOR UPDATE Netty waiters.
 * <p>
 * Open TX reuses {@link SqlTxBuffer#txId()}; autocommit / statement-scoped locks get a
 * unique negative id so concurrent {@code FOR_UPDATE_LOCK_REQ} on the same peer do not
 * collide on {@code peerId@txId} (never {@code 0}).
 *
 * @author: GenCloud
 * @date: 2026/01
 * @since: 1.0
 */
public final class DistForUpdateCorrelatorUtil {
	/** First statement-scoped correlator magnitude (stored as negative). */
	private static final long INITIAL_STATEMENT_SEQ = 1L;
	private static final AtomicLong STATEMENT_SEQ = new AtomicLong(INITIAL_STATEMENT_SEQ);

	private DistForUpdateCorrelatorUtil() {
	}

	/**
	 * Correlator for peer lock / unlock RPCs for this session statement.
	 */
	public static long peerLockCorrelator(SqlSession session) {
		Objects.requireNonNull(session, "session");
		if (session.inTransaction()) {
			return session.requireTx().txId();
		}
		return nextStatementScopedId();
	}

	/**
	 * Allocate a unique statement-scoped correlator (negative, never zero).
	 */
	public static long nextStatementScopedId() {
		final long seq = STATEMENT_SEQ.getAndIncrement();
		return -seq;
	}
}
