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

import org.genfork.grid.sql.client.DefaultRowMetadata;
import org.genfork.grid.sql.client.Row;
import org.genfork.grid.sql.client.RowMetadata;
import org.genfork.grid.sql.client.transport.TransportOutcome;

/**
 * Blocking result over {@link TransportOutcome} + {@link org.genfork.grid.sql.client.transport.RowPortal}.
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public final class SyncResult {
	private final TransportOutcome outcome;
	private final Duration timeout;
	private final Executor syncExecutor;

	SyncResult(TransportOutcome outcome, Duration timeout, Executor syncExecutor) {
		this.outcome = Objects.requireNonNull(outcome, "outcome");
		this.timeout = timeout == null ? SyncAwait.DEFAULT_TIMEOUT : timeout;
		this.syncExecutor = syncExecutor;
	}

	public boolean isResultSet() {
		return outcome instanceof TransportOutcome.ResultSet;
	}

	public RowMetadata rowMetadata() {
		if (outcome instanceof TransportOutcome.ResultSet rs) {
			return new DefaultRowMetadata(rs.columns());
		}
		return null;
	}

	public long rowsUpdated() {
		if (outcome instanceof TransportOutcome.Dml dml) {
			return dml.affected();
		}
		return 0L;
	}

	public List<Row> rows() {
		if (!(outcome instanceof TransportOutcome.ResultSet rs)) {
			return List.of();
		}
		final Executor executor = syncExecutor == null ? SyncExecutors.executor() : syncExecutor;
		return SyncExecutors.call(executor, () -> SyncConnection.drainPortal(rs, timeout));
	}

	public void cancel() {
		if (outcome instanceof TransportOutcome.ResultSet rs) {
			rs.portal().cancel();
		}
	}

	TransportOutcome outcome() {
		return outcome;
	}
}
