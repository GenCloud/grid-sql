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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.client.Result;
import org.genfork.grid.sql.client.ServerMeta;
import org.genfork.grid.sql.client.transport.FetchPortal;
import org.genfork.grid.sql.client.transport.PendingExchange;
import org.genfork.grid.sql.client.transport.TransportOutcome;
import org.genfork.grid.sql.netty.SqlWire;

/**
 * Reactive EXEC / AUTH / SESSION exchange: {@link Sinks.One} + lazy {@link FetchPortal}.
 * <p>
 * Portal allocated only on ROW_DESC. No {@link java.util.concurrent.CompletableFuture}.
 * Write stays outside (subscribe-driven); cancel maps to portal CANCEL or wire CANCEL.
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public final class ReactiveExecExchange implements PendingExchange {
	private static final String CANCEL_TAG = "CANCEL";

	private final int fetchWindow;
	private final IntConsumer fetchSender;
	private final Runnable cancelSender;
	private final Sinks.One<Result> sink = Sinks.one();
	private final AtomicBoolean done = new AtomicBoolean();
	private final AtomicBoolean resultEmitted = new AtomicBoolean();
	private volatile boolean streaming;
	private volatile FetchPortal portal;
	private final Mono<Result> mono;

	public ReactiveExecExchange() {
		this(SqlWire.DEFAULT_FETCH_WINDOW, null, null);
	}

	/**
	 * @param fetchSender write FETCH(n) for this requestId (may be null for AUTH/SESSION)
	 * @param cancelSender write CANCEL for this requestId (may be null)
	 */
	public ReactiveExecExchange(IntConsumer fetchSender, Runnable cancelSender) {
		this(SqlWire.DEFAULT_FETCH_WINDOW, fetchSender, cancelSender);
	}

	public ReactiveExecExchange(int fetchWindow, IntConsumer fetchSender, Runnable cancelSender) {
		this.fetchWindow = fetchWindow > 0 ? fetchWindow : SqlWire.DEFAULT_FETCH_WINDOW;
		this.fetchSender = fetchSender;
		this.cancelSender = cancelSender;
		this.mono = sink.asMono().doOnCancel(this::cancel);
	}

	/**
	 * Single-subscriber Mono; cancel triggers portal / wire CANCEL.
	 */
	public Mono<Result> mono() {
		return mono;
	}

	/** Best-effort CANCEL when the subscriber abandons the exchange. */
	public void cancel() {
		final FetchPortal live = portal;
		if (live != null) {
			live.cancel();
		} else if (cancelSender != null) {
			cancelSender.run();
		}
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
		streaming = true;
		final FetchPortal created = new FetchPortal(fetchWindow, fetchSender, cancelSender);
		portal = created;
		emitOutcome(new TransportOutcome.ResultSet(columns, created));
	}

	@Override
	public void onRowData(Object[] row) {
		if (done.get()) {
			return;
		}
		final FetchPortal live = portal;
		if (live != null) {
			live.offerRow(row);
		}
	}

	@Override
	public boolean completeExec(long affected, String tag) {
		if (!done.compareAndSet(false, true)) {
			return true;
		}

		if (streaming) {
			final FetchPortal live = portal;
			if (live != null) {
				live.complete();
			}
			return true;
		}

		if (tag != null && CANCEL_TAG.equalsIgnoreCase(tag)) {
			emitOutcome(new TransportOutcome.Dml(0L, CANCEL_TAG));
			return true;
		}

		emitOutcome(new TransportOutcome.Dml(affected, tag));
		return true;
	}

	@Override
	public void fail(Throwable t) {
		done.set(true);
		final FetchPortal live = portal;
		if (live != null) {
			live.fail(t);
		}
		if (resultEmitted.compareAndSet(false, true)) {
			sink.tryEmitError(t);
		}
	}

	private void emitOutcome(TransportOutcome value) {
		if (resultEmitted.compareAndSet(false, true)) {
			sink.tryEmitValue(TransportReactive.toResult(value));
		}
	}
}