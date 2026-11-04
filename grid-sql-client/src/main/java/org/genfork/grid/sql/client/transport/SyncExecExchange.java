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
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.IntConsumer;

import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.client.ServerMeta;
import org.genfork.grid.sql.netty.SqlWire;

/**
 * Sync / JDBC EXEC / AUTH / SESSION exchange: {@link CompletableFuture} outcome + lazy {@link FetchPortal}.
 * <p>
 * Portal is allocated only on {@link #onRowDesc} (RESULT_SET). Pure DML / AUTH / SESSION skip portal
 * queues. Reactive SPI uses {@code ReactiveExecExchange} — never this type.
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public final class SyncExecExchange implements PendingExchange {
	private final ExecExchangeSupport.State state;
	private final CompletableFuture<TransportOutcome> outcome = new CompletableFuture<>();

	public SyncExecExchange() {
		this(SqlWire.DEFAULT_FETCH_WINDOW, null, null);
	}

	/**
	 * @param fetchSender write FETCH(n) for this requestId (may be null for AUTH/SESSION)
	 * @param cancelSender write CANCEL for this requestId (may be null)
	 */
	public SyncExecExchange(IntConsumer fetchSender, Runnable cancelSender) {
		this(SqlWire.DEFAULT_FETCH_WINDOW, fetchSender, cancelSender);
	}

	public SyncExecExchange(int fetchWindow, IntConsumer fetchSender, Runnable cancelSender) {
		final int window = fetchWindow > 0 ? fetchWindow : SqlWire.DEFAULT_FETCH_WINDOW;
		this.state = new ExecExchangeSupport.State(window, fetchSender, cancelSender);
		outcome.whenComplete((value, error) -> {
			if (error instanceof CancellationException || outcome.isCancelled()) {
				cancel();
			}
		});
	}

	public CompletionStage<TransportOutcome> outcome() {
		return outcome;
	}

	/** Best-effort CANCEL when the sync consumer abandons the exchange. */
	public void cancel() {
		state.cancel();
	}

	@Override
	public void completeAuth(ServerMeta serverMeta) {
		emitOutcome(new TransportOutcome.Auth(serverMeta));
	}

	@Override
	public void completeSessionOpen(int sessionId) {
		emitOutcome(new TransportOutcome.SessionOpen(sessionId));
	}

	@Override
	public void completeSessionClose() {
		emitOutcome(new TransportOutcome.SessionClose());
	}

	@Override
	public void onRowDesc(List<SqlResult.ColumnMeta> columns) {
		state.onRowDesc(columns, this::deliverOutcome);
	}

	@Override
	public void onRowData(Object[] row) {
		state.onRowData(row);
	}

	@Override
	public boolean completeExec(long affected, String tag) {
		return state.completeExec(affected, tag, this::deliverOutcome);
	}

	@Override
	public void fail(Throwable t) {
		state.failPortal(t);
		outcome.completeExceptionally(t);
	}

	private void emitOutcome(TransportOutcome value) {
		state.emitOutcomeOnce(value, this::deliverOutcome);
	}

	private void deliverOutcome(TransportOutcome value) {
		outcome.complete(value);
	}
}
