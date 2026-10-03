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
package org.genfork.grid.sql.client.sync;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import org.genfork.grid.sql.SqlStatementTag;
import org.genfork.grid.sql.client.Savepoint;
import org.genfork.grid.sql.client.SqlClientMessages;
import org.genfork.grid.sql.client.SqlSavepointSql;
import org.genfork.grid.sql.client.transport.TransportConnection;
import org.genfork.grid.sql.client.transport.TransportOutcome;

/**
 * Blocking TX over {@link TransportConnection} EXEC + closeSession CompletionStages.
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public final class SyncTxContext implements AutoCloseable {
	private final TransportConnection transport;
	private final int sessionId;
	private final long prepareHandle;
	private final Duration timeout;
	private final Executor syncExecutor;
	private final AtomicBoolean closed = new AtomicBoolean();
	private final AtomicBoolean completed = new AtomicBoolean();

	SyncTxContext(
			TransportConnection transport,
			int sessionId,
			long prepareHandle,
			Duration timeout,
			Executor syncExecutor
	) {
		this.transport = Objects.requireNonNull(transport, "transport");
		this.sessionId = sessionId;
		this.prepareHandle = prepareHandle;
		this.timeout = timeout == null ? SyncAwait.DEFAULT_TIMEOUT : timeout;
		this.syncExecutor = syncExecutor;
	}

	public long prepareHandle() {
		return prepareHandle;
	}

	public SyncStatement statement(String sql) {
		return statement(sql, timeout);
	}

	/**
	 * TX statement with an explicit await timeout (JDBC {@code queryTimeout}).
	 */
	public SyncStatement statement(String sql, Duration execTimeout) {
		final Duration effective = execTimeout == null || execTimeout.isZero() || execTimeout.isNegative()
				? timeout
				: execTimeout;
		return new SyncStatement(transport, sessionId, sql, closed, effective, syncExecutor);
	}

	public List<SyncResult> executeBatch(List<String> sqls) {
		return executeBatch(sqls, timeout);
	}

	public List<SyncResult> executeBatch(List<String> sqls, Duration execTimeout) {
		ensureOpen();
		final Duration effective = execTimeout == null || execTimeout.isZero() || execTimeout.isNegative()
				? timeout
				: execTimeout;
		final List<TransportOutcome> outcomes =
				SyncAwait.await(transport.batchExec(sessionId, sqls), effective, null, syncExecutor);
		return SyncConnection.wrapOutcomes(outcomes, effective, syncExecutor);
	}

	public List<SyncResult> execute(String sql) {
		ensureOpen();
		final TransportOutcome outcome =
				SyncAwait.await(transport.exec(sessionId, sql, null, 0), timeout, null, syncExecutor);
		return SyncConnection.wrapOutcomes(List.of(outcome), timeout, syncExecutor);
	}

	public long executeUpdate(String sql) {
		ensureOpen();
		final TransportOutcome outcome =
				SyncAwait.await(transport.exec(sessionId, sql, null, 0), timeout, null, syncExecutor);
		if (outcome instanceof TransportOutcome.Dml dml) {
			return dml.affected();
		}
		return 0L;
	}

	/**
	 * In-TX SELECT (or other result-set SQL) → dense cell rows.
	 */
	public List<Object[]> query(String sql) {
		final List<SyncResult> results = execute(sql);
		if (results.isEmpty()) {
			return List.of();
		}
		return results.getFirst().objectRows();
	}

	public void commit() {
		finish(SqlStatementTag.COMMIT.wire());
	}

	public void rollback() {
		finish(SqlStatementTag.ROLLBACK.wire());
	}

	public Savepoint savepoint(String name) {
		ensureOpen();
		final Savepoint handle = Savepoint.of(name);
		SyncAwait.await(
				transport.exec(sessionId, SqlSavepointSql.savepoint(handle.name()), null, 0),
				timeout, null, syncExecutor);
		return handle;
	}

	public void rollbackTo(Savepoint savepoint) {
		ensureOpen();
		if (savepoint == null) {
			throw new IllegalArgumentException(SqlClientMessages.SAVEPOINT_REQUIRED);
		}
		SyncAwait.await(
				transport.exec(sessionId, SqlSavepointSql.rollbackTo(savepoint.name()), null, 0),
				timeout, null, syncExecutor);
	}

	public void release(Savepoint savepoint) {
		ensureOpen();
		if (savepoint == null) {
			throw new IllegalArgumentException(SqlClientMessages.SAVEPOINT_REQUIRED);
		}
		SyncAwait.await(
				transport.exec(sessionId, SqlSavepointSql.release(savepoint.name()), null, 0),
				timeout, null, syncExecutor);
	}

	@Override
	public void close() {
		if (completed.get()) {
			return;
		}
		try {
			rollback();
		} catch (RuntimeException ignored) {
			closed.set(true);
			SyncAwait.awaitVoid(transport.closeSession(sessionId), timeout, syncExecutor);
		}
	}

	private void finish(String sql) {
		if (!completed.compareAndSet(false, true)) {
			return;
		}
		try {
			SyncAwait.await(transport.exec(sessionId, sql, null, 0), timeout, null, syncExecutor);
			closed.set(true);
			SyncAwait.awaitVoid(transport.closeSession(sessionId), timeout, syncExecutor);
		} catch (RuntimeException err) {
			closed.set(true);
			try {
				SyncAwait.awaitVoid(transport.closeSession(sessionId), timeout, syncExecutor);
			} catch (RuntimeException ignored) {
				// prefer original error
			}
			throw err;
		}
	}

	private void ensureOpen() {
		if (closed.get() || completed.get()) {
			throw new IllegalStateException(SqlClientMessages.TX_CLOSED);
		}
	}
}
