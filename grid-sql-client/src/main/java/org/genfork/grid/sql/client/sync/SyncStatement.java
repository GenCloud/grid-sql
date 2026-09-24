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
import java.util.TreeMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.genfork.grid.sql.client.RemoteConnection;
import org.genfork.grid.sql.client.RemoteConnectionFactory;
import org.genfork.grid.sql.client.Row;
import org.genfork.grid.sql.client.transport.TransportConnection;
import org.genfork.grid.sql.client.transport.TransportOutcome;

/**
 * Blocking statement over transport CompletionStage exec / autocommit.
 * <p>
 * Optional READ_REPLICA path borrows a channel from {@link RemoteConnectionFactory}
 * for the duration of {@link #execute()} then parks it.
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public final class SyncStatement {
	private final TransportConnection transport;
	private final RemoteConnectionFactory readFactory;
	private final Integer sessionId;
	private final AtomicBoolean txClosed;
	private final String sql;
	private final TreeMap<Integer, Object> binds = new TreeMap<>();
	private int fetchWindowOverride;
	private final Duration timeout;
	private final Executor syncExecutor;
	/** Optional JDBC cancel hook registration during {@link #execute()}. */
	private Consumer<Runnable> cancelSlot;

	/** Autocommit statement on the writer transport. */
	SyncStatement(TransportConnection transport, String sql, Duration timeout, Executor syncExecutor) {
		this(transport, null, null, sql, null, timeout, syncExecutor);
	}

	/** Autocommit statement that borrows a READ_REPLICA channel per execute. */
	static SyncStatement autocommitRead(
			RemoteConnectionFactory readFactory,
			String sql,
			Duration timeout,
			Executor syncExecutor
	) {
		return new SyncStatement(null, Objects.requireNonNull(readFactory, "readFactory"),
				null, sql, null, timeout, syncExecutor);
	}

	/** TX-scoped statement. */
	SyncStatement(
			TransportConnection transport,
			int sessionId,
			String sql,
			AtomicBoolean txClosed,
			Duration timeout,
			Executor syncExecutor
	) {
		this(transport, null, Integer.valueOf(sessionId), sql, txClosed, timeout, syncExecutor);
	}

	private SyncStatement(
			TransportConnection transport,
			RemoteConnectionFactory readFactory,
			Integer sessionId,
			String sql,
			AtomicBoolean txClosed,
			Duration timeout,
			Executor syncExecutor
	) {
		if (transport == null && readFactory == null) {
			throw new IllegalArgumentException("transport or readFactory required");
		}
		this.transport = transport;
		this.readFactory = readFactory;
		this.sessionId = sessionId;
		this.txClosed = txClosed;
		this.sql = sql;
		this.fetchWindowOverride = 0;
		this.timeout = timeout == null ? SyncAwait.DEFAULT_TIMEOUT : timeout;
		this.syncExecutor = syncExecutor;
	}

	public SyncStatement bind(int index, Object value) {
		if (index < 0) {
			throw new IllegalArgumentException("bind index must be >= 0");
		}
		binds.put(index, value);
		return this;
	}

	public SyncStatement bind(String name, Object value) {
		throw new UnsupportedOperationException("named bind not supported; use positional ?");
	}

	public SyncStatement fetchWindow(int rows) {
		if (rows < 1) {
			throw new IllegalArgumentException("fetchWindow must be >= 1");
		}
		this.fetchWindowOverride = rows;
		return this;
	}

	/**
	 * Register a cancel hook for the in-flight SyncAwait (JDBC {@code Statement.cancel}).
	 */
	public SyncStatement cancelSlot(Consumer<Runnable> slot) {
		this.cancelSlot = slot;
		return this;
	}

	public List<SyncResult> execute() {
		if (txClosed != null && txClosed.get()) {
			throw new IllegalStateException("TxContext closed");
		}
		final Object[] args = SyncConnection.denseBinds(binds);
		if (readFactory != null) {
			final org.genfork.grid.sql.client.Connection borrowed =
					SyncAwait.await(readFactory.obtainStage(), timeout, cancelSlot, syncExecutor);
			if (!(borrowed instanceof RemoteConnection readRemote)) {
				throw new IllegalStateException("read obtainStage must return RemoteConnection");
			}
			try {
				final TransportOutcome outcome = SyncAwait.await(
						readRemote.transport().execAutocommit(sql, args, fetchWindowOverride),
						timeout, cancelSlot, syncExecutor);
				return SyncConnection.wrapOutcomes(List.of(outcome), timeout, syncExecutor);
			} finally {
				SyncAwait.awaitVoid(readRemote.transport().parkStage(), timeout, syncExecutor);
			}
		}
		final TransportOutcome outcome;
		if (sessionId == null) {
			outcome = SyncAwait.await(
					transport.execAutocommit(sql, args, fetchWindowOverride),
					timeout, cancelSlot, syncExecutor);
		} else {
			outcome = SyncAwait.await(
					transport.exec(sessionId, sql, args, fetchWindowOverride),
					timeout, cancelSlot, syncExecutor);
		}
		return SyncConnection.wrapOutcomes(List.of(outcome), timeout, syncExecutor);
	}

	public SyncResult executeOne() {
		final List<SyncResult> all = execute();
		return all.isEmpty() ? null : all.getFirst();
	}

	public long executeUpdate() {
		final SyncResult first = executeOne();
		return first == null ? 0L : first.rowsUpdated();
	}

	public Row fetchOne() {
		final SyncResult first = executeOne();
		if (first == null || !first.isResultSet()) {
			return null;
		}
		final List<Row> rows = first.rows();
		return rows.isEmpty() ? null : rows.getFirst();
	}
}
