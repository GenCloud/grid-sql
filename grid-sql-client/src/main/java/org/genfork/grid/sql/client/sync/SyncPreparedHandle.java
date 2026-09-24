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

import org.genfork.grid.sql.client.SqlClientSql;
import org.genfork.grid.sql.client.transport.TransportConnection;
import org.genfork.grid.sql.client.transport.TransportOutcome;

/**
 * Blocking prepared handle over transport EXECUTE / DEALLOCATE CompletionStages.
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public final class SyncPreparedHandle {
	private final TransportConnection transport;
	private final String name;
	private final TreeMap<Integer, Object> binds = new TreeMap<>();
	private final Duration timeout;
	private final Executor syncExecutor;

	SyncPreparedHandle(TransportConnection transport, String name, Duration timeout, Executor syncExecutor) {
		this.transport = Objects.requireNonNull(transport, "transport");
		this.name = Objects.requireNonNull(name, "name");
		this.timeout = timeout == null ? SyncAwait.DEFAULT_TIMEOUT : timeout;
		this.syncExecutor = syncExecutor;
	}

	public String name() {
		return name;
	}

	public SyncPreparedHandle bind(int index, Object value) {
		if (index < 0) {
			throw new IllegalArgumentException("bind index must be >= 0");
		}
		binds.put(index, value);
		return this;
	}

	public List<SyncResult> execute() {
		final Object[] args = SyncConnection.denseBinds(binds);
		final TransportOutcome outcome = SyncAwait.await(
				transport.execAutocommit(SqlClientSql.executeSql(name), args, 0),
				timeout, null, syncExecutor);
		return SyncConnection.wrapOutcomes(List.of(outcome), timeout, syncExecutor);
	}

	public void deallocate() {
		SyncAwait.await(
				transport.execAutocommit(SqlClientSql.deallocateSql(name), null, 0),
				timeout, null, syncExecutor);
	}
}
