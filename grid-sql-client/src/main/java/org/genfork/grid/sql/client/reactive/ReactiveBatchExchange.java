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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.client.Result;
import org.genfork.grid.sql.client.ServerMeta;
import org.genfork.grid.sql.client.transport.BatchStatementSlot;
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
	private static final String CANCEL_TAG = "CANCEL";
	private static final String ERR_AUTH = "AUTH unexpected on BATCH_EXEC";
	private static final String ERR_SESSION_OPEN = "SESSION_OPEN unexpected on BATCH_EXEC";
	private static final String ERR_SESSION_CLOSE = "SESSION_CLOSE unexpected on BATCH_EXEC";

	private final int expectedStatements;
	private final int fetchWindow;
	private final IntConsumer fetchSender;
	private final Runnable cancelSender;
	private final AtomicInteger completedStatements = new AtomicInteger();
	private final AtomicBoolean finished = new AtomicBoolean();
	private final AtomicBoolean cancelled = new AtomicBoolean();
	private final Sinks.Many<Result> sink = Sinks.many().unicast().onBackpressureBuffer();
	private volatile BatchStatementSlot current;

	public ReactiveBatchExchange(int expectedStatements, IntConsumer fetchSender, Runnable cancelSender) {
		this(expectedStatements, SqlWire.DEFAULT_FETCH_WINDOW, fetchSender, cancelSender);
	}

	public ReactiveBatchExchange(
			int expectedStatements,
			int fetchWindow,
			IntConsumer fetchSender,
			Runnable cancelSender
	) {
		this.expectedStatements = Math.max(0, expectedStatements);
		this.fetchWindow = Math.max(1, fetchWindow);
		this.fetchSender = fetchSender;
		this.cancelSender = cancelSender;
	}

	public int expectedStatements() {
		return expectedStatements;
	}

	/**
	 * Ordered statement results; cancel triggers batch CANCEL.
	 */
	public Flux<Result> flux() {
		if (expectedStatements == 0) {
			return Flux.empty();
		}
		return sink.asFlux().doOnCancel(this::cancel);
	}

	/** Best-effort CANCEL for the whole batch. */
	public void cancel() {
		if (finished.get()) {
			return;
		}
		if (!cancelled.compareAndSet(false, true)) {
			return;
		}
		if (cancelSender != null) {
			cancelSender.run();
		}
	}

	@Override
	public void completeAuth(ServerMeta serverMeta) {
		fail(new IllegalStateException(ERR_AUTH));
	}

	@Override
	public void completeSessionOpen(int sessionId) {
		fail(new IllegalStateException(ERR_SESSION_OPEN));
	}

	@Override
	public void completeSessionClose() {
		fail(new IllegalStateException(ERR_SESSION_CLOSE));
	}

	@Override
	public void onRowDesc(List<SqlResult.ColumnMeta> columns) {
		if (finished.get()) {
			return;
		}
		final BatchStatementSlot slot = new BatchStatementSlot(
				fetchWindow, fetchSender, cancelSender, cancelled);
		current = slot;
		emitOutcome(new TransportOutcome.ResultSet(columns, slot.portal()));
	}

	@Override
	public void onRowData(Object[] row) {
		final BatchStatementSlot slot = current;
		if (slot == null || finished.get()) {
			return;
		}
		slot.portal().offerRow(row);
	}

	@Override
	public boolean completeExec(long affected, String tag) {
		if (finished.get()) {
			return true;
		}

		if (tag != null && CANCEL_TAG.equalsIgnoreCase(tag)) {
			final BatchStatementSlot slot = current;
			if (slot != null) {
				slot.portal().complete();
			}
			finishOk();
			return true;
		}

		final BatchStatementSlot slot = current;
		if (slot != null) {
			slot.portal().complete();
			current = null;
		} else {
			current = null;
			emitOutcome(new TransportOutcome.Dml(affected, tag));
		}

		if (completedStatements.incrementAndGet() >= expectedStatements) {
			finishOk();
			return true;
		}
		return false;
	}

	@Override
	public void fail(Throwable t) {
		if (!finished.compareAndSet(false, true)) {
			return;
		}
		final BatchStatementSlot slot = current;
		if (slot != null) {
			slot.portal().fail(t);
		}
		sink.tryEmitError(t);
	}

	private void emitOutcome(TransportOutcome outcome) {
		sink.tryEmitNext(TransportReactive.toResult(outcome));
	}

	private void finishOk() {
		if (!finished.compareAndSet(false, true)) {
			return;
		}
		sink.tryEmitComplete();
	}
}
