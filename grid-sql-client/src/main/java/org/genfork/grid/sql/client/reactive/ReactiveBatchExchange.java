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
package org.genfork.grid.sql.client.reactive;

import java.util.List;
import java.util.function.IntConsumer;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.client.Result;
import org.genfork.grid.sql.client.ServerMeta;
import org.genfork.grid.sql.client.transport.BatchExchangeSupport;
import org.genfork.grid.sql.client.transport.PendingExchange;
import org.genfork.grid.sql.client.transport.TransportOutcome;
import org.genfork.grid.sql.netty.SqlWire;

/**
 * Reactive BATCH_EXEC exchange: ordered {@link Result} stream via {@link Sinks.Many}.
 * <p>
 * No {@link java.util.concurrent.CompletableFuture}. Write stays outside (subscribe-driven).
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public final class ReactiveBatchExchange implements PendingExchange {
	private final BatchExchangeSupport.State state;
	private final Sinks.Many<Result> sink = Sinks.many().unicast().onBackpressureBuffer();

	public ReactiveBatchExchange(int expectedStatements, IntConsumer fetchSender, Runnable cancelSender) {
		this(expectedStatements, SqlWire.DEFAULT_FETCH_WINDOW, fetchSender, cancelSender);
	}

	public ReactiveBatchExchange(
			int expectedStatements,
			int fetchWindow,
			IntConsumer fetchSender,
			Runnable cancelSender
	) {
		this.state = new BatchExchangeSupport.State(
				expectedStatements, fetchWindow, fetchSender, cancelSender);
	}

	/**
	 * Ordered statement results; cancel triggers batch CANCEL.
	 */
	public Flux<Result> flux() {
		if (state.expectedStatements() == 0) {
			return Flux.empty();
		}
		return sink.asFlux().doOnCancel(this::cancel);
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
		state.onRowDesc(columns, this::emitOutcome);
	}

	@Override
	public void onRowData(Object[] row) {
		state.onRowData(row);
	}

	@Override
	public boolean completeExec(long affected, String tag) {
		return state.completeExec(affected, tag, this::emitOutcome, this::finishOk);
	}

	@Override
	public void fail(Throwable t) {
		if (state.failPortal(t)) {
			sink.tryEmitError(t);
		}
	}

	private void emitOutcome(TransportOutcome outcome) {
		sink.tryEmitNext(TransportReactive.toResult(outcome));
	}

	private void finishOk() {
		if (!state.finished().compareAndSet(false, true)) {
			return;
		}
		sink.tryEmitComplete();
	}
}
