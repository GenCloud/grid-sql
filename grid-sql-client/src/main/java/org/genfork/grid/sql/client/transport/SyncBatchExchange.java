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
package org.genfork.grid.sql.client.transport;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.IntConsumer;

import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.client.ServerMeta;
import org.genfork.grid.sql.netty.SqlWire;

/**
 * Sync / JDBC BATCH_EXEC exchange: ordered {@link TransportOutcome} via {@link CompletableFuture}.
 * <p>
 * Reactive SPI uses {@code ReactiveBatchExchange} — never this type.
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public final class SyncBatchExchange implements PendingExchange {
	private final BatchExchangeSupport.State state;
	private final ConcurrentLinkedQueue<TransportOutcome> orderedOutcomes = new ConcurrentLinkedQueue<>();
	private final CompletableFuture<List<TransportOutcome>> outcomesFuture = new CompletableFuture<>();

	public SyncBatchExchange(int expectedStatements, IntConsumer fetchSender, Runnable cancelSender) {
		this(expectedStatements, SqlWire.DEFAULT_FETCH_WINDOW, fetchSender, cancelSender);
	}

	public SyncBatchExchange(
			int expectedStatements,
			int fetchWindow,
			IntConsumer fetchSender,
			Runnable cancelSender
	) {
		this.state = new BatchExchangeSupport.State(
				expectedStatements, fetchWindow, fetchSender, cancelSender);
	}

	/**
	 * Ordered statement outcomes; completes when the batch finishes (or fails).
	 */
	public CompletionStage<List<TransportOutcome>> outcomes() {
		return outcomesFuture;
	}

	public int expectedStatements() {
		return state.expectedStatements();
	}

	/** Best-effort CANCEL for the whole batch. */
	public void cancel() {
		state.cancel();
	}

	@Override
	public void completeAuth(ServerMeta serverMeta) {
		fail(BatchExchangeSupport.unexpectedAuth());
	}

	@Override
	public void completeSessionOpen(int sessionId) {
		fail(BatchExchangeSupport.unexpectedSessionOpen());
	}

	@Override
	public void completeSessionClose() {
		fail(BatchExchangeSupport.unexpectedSessionClose());
	}

	@Override
	public void onRowDesc(List<SqlResult.ColumnMeta> columns) {
		state.onRowDesc(columns, orderedOutcomes::offer);
	}

	@Override
	public void onRowData(Object[] row) {
		state.onRowData(row);
	}

	@Override
	public boolean completeExec(long affected, String tag) {
		return state.completeExec(affected, tag, orderedOutcomes::offer, this::finishOk);
	}

	@Override
	public void fail(Throwable t) {
		if (state.failPortal(t)) {
			outcomesFuture.completeExceptionally(t);
		}
	}

	private void finishOk() {
		if (!state.finished().compareAndSet(false, true)) {
			return;
		}
		outcomesFuture.complete(List.copyOf(orderedOutcomes));
	}
}
