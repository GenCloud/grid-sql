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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import org.genfork.grid.catalog.SqlType;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.SqlStatementTag;
import org.genfork.grid.sql.client.EngineResult;
import org.genfork.grid.sql.client.Result;
import org.genfork.grid.sql.client.ServerMeta;
import org.genfork.grid.sql.client.StreamingResult;
import org.genfork.grid.sql.client.reactive.ReactiveBatchExchange;
import org.genfork.grid.sql.client.reactive.ReactiveExecExchange;
import org.genfork.grid.sql.netty.SqlFrame;
import org.genfork.grid.sql.netty.SqlOpcode;
import org.genfork.grid.sql.netty.SqlWire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dual exchange demux smoke: Sync CF + Reactive Sinks (EmbeddedChannel).
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
class TransportExecExchangeTest {
	private static final int REQUEST_ID = 7;
	private static final int FETCH_WINDOW = 2;
	private static final Duration AWAIT = Duration.ofSeconds(5);

	@Test
	void demuxAuthOkCompletesSyncOutcome() {
		final Map<Integer, PendingExchange> pending = new ConcurrentHashMap<>();
		final AtomicReference<ServerMeta> meta = new AtomicReference<>(ServerMeta.EMPTY);
		final SqlClientInboundHandler handler = new SqlClientInboundHandler(pending, meta::set);
		final EmbeddedChannel channel = new EmbeddedChannel(handler);
		final SyncExecExchange exchange = new SyncExecExchange();
		pending.put(REQUEST_ID, exchange);
		try {
			final ServerMeta expected = new ServerMeta("n1", true, "n1", false, 1L);
			channel.writeInbound(new SqlFrame(
					SqlOpcode.AUTH_OK, REQUEST_ID, SqlWire.serverMeta(expected)));
			final TransportOutcome outcome = exchange.outcome().toCompletableFuture().join();
			assertInstanceOf(TransportOutcome.Auth.class, outcome);
			assertEquals(expected, meta.get());
			assertTrue(pending.isEmpty());
		} finally {
			channel.finishAndReleaseAll();
		}
	}

	@Test
	void reactiveExecResultSetFetch() {
		final Map<Integer, PendingExchange> pending = new ConcurrentHashMap<>();
		final AtomicInteger fetchDemand = new AtomicInteger();
		final SqlClientInboundHandler handler = new SqlClientInboundHandler(pending);
		final EmbeddedChannel channel = new EmbeddedChannel(handler);
		final ReactiveExecExchange exchange = new ReactiveExecExchange(
				FETCH_WINDOW,
				fetchDemand::addAndGet,
				() -> {
				}
		);
		pending.put(REQUEST_ID, exchange);
		try {
			final List<Integer> collected = new CopyOnWriteArrayList<>();
			final AtomicReference<Throwable> error = new AtomicReference<>();
			final AtomicReference<Boolean> completed = new AtomicReference<>(false);

			exchange.mono()
					.flatMapMany(result -> {
						assertInstanceOf(StreamingResult.class, result);
						return result.map((row, meta) -> (Integer) row.get(0));
					})
					.subscribe(
							collected::add,
							error::set,
							() -> completed.set(true)
					);

			channel.writeInbound(new SqlFrame(
					SqlOpcode.ROW_DESC, REQUEST_ID,
					SqlWire.rowDesc(List.of(SqlResult.ColumnMeta.of("id", SqlType.INT)))));
			channel.writeInbound(new SqlFrame(
					SqlOpcode.ROW_DATA, REQUEST_ID, SqlWire.rowData(new Object[]{11})));
			channel.writeInbound(new SqlFrame(
					SqlOpcode.ROW_DATA, REQUEST_ID, SqlWire.rowData(new Object[]{22})));
			channel.writeInbound(new SqlFrame(
					SqlOpcode.EXEC_DONE, REQUEST_ID,
					SqlWire.execDone(0L, SqlStatementTag.SELECT.wire())));

			awaitUntil(() -> Boolean.TRUE.equals(completed.get()) || error.get() != null);
			assertEquals(null, error.get());
			assertEquals(List.of(11, 22), collected);
			assertTrue(fetchDemand.get() > 0);
			assertTrue(pending.isEmpty());
		} finally {
			channel.finishAndReleaseAll();
		}
	}

	@Test
	void reactiveExecDmlCompletesEngineResult() {
		final Map<Integer, PendingExchange> pending = new ConcurrentHashMap<>();
		final SqlClientInboundHandler handler = new SqlClientInboundHandler(pending);
		final EmbeddedChannel channel = new EmbeddedChannel(handler);
		final ReactiveExecExchange exchange = new ReactiveExecExchange();
		pending.put(REQUEST_ID, exchange);
		try {
			final Result result = exchange.mono()
					.doOnSubscribe(_ -> channel.writeInbound(new SqlFrame(
							SqlOpcode.EXEC_DONE, REQUEST_ID,
							SqlWire.execDone(3L, SqlStatementTag.INSERT.wire()))))
					.block(AWAIT);
			assertInstanceOf(EngineResult.class, result);
			assertEquals(3L, result.getRowsUpdated().block(AWAIT));
			assertTrue(pending.isEmpty());
		} finally {
			channel.finishAndReleaseAll();
		}
	}

	@Test
	void syncBatchOutcomesCollectsOrderedDml() {
		final Map<Integer, PendingExchange> pending = new ConcurrentHashMap<>();
		final SqlClientInboundHandler handler = new SqlClientInboundHandler(pending);
		final EmbeddedChannel channel = new EmbeddedChannel(handler);
		final SyncBatchExchange exchange = new SyncBatchExchange(
				2,
				FETCH_WINDOW,
				n -> {
				},
				() -> {
				}
		);
		pending.put(REQUEST_ID, exchange);
		try {
			final java.util.concurrent.CompletableFuture<List<TransportOutcome>> outcomes =
					exchange.outcomes().toCompletableFuture();
			channel.writeInbound(new SqlFrame(
					SqlOpcode.EXEC_DONE, REQUEST_ID,
					SqlWire.execDone(1L, SqlStatementTag.INSERT.wire())));
			channel.writeInbound(new SqlFrame(
					SqlOpcode.EXEC_DONE, REQUEST_ID,
					SqlWire.execDone(2L, SqlStatementTag.UPDATE.wire())));
			final List<TransportOutcome> list = outcomes.join();
			assertEquals(2, list.size());
			assertInstanceOf(TransportOutcome.Dml.class, list.get(0));
			assertInstanceOf(TransportOutcome.Dml.class, list.get(1));
			assertEquals(1L, ((TransportOutcome.Dml) list.get(0)).affected());
			assertEquals(2L, ((TransportOutcome.Dml) list.get(1)).affected());
			assertTrue(pending.isEmpty());
		} finally {
			channel.finishAndReleaseAll();
		}
	}

	@Test
	void reactiveBatchFluxCollectsOrderedDml() {
		final Map<Integer, PendingExchange> pending = new ConcurrentHashMap<>();
		final SqlClientInboundHandler handler = new SqlClientInboundHandler(pending);
		final EmbeddedChannel channel = new EmbeddedChannel(handler);
		final ReactiveBatchExchange exchange = new ReactiveBatchExchange(
				2,
				FETCH_WINDOW,
				n -> {
				},
				() -> {
				}
		);
		pending.put(REQUEST_ID, exchange);
		try {
			final List<Long> affected = new CopyOnWriteArrayList<>();
			final AtomicReference<Throwable> error = new AtomicReference<>();
			final AtomicReference<Boolean> completed = new AtomicReference<>(false);
			exchange.flux()
					.concatMap(Result::getRowsUpdated)
					.subscribe(
							v -> affected.add(v == null ? 0L : v),
							error::set,
							() -> completed.set(true)
					);
			channel.writeInbound(new SqlFrame(
					SqlOpcode.EXEC_DONE, REQUEST_ID,
					SqlWire.execDone(1L, SqlStatementTag.INSERT.wire())));
			channel.writeInbound(new SqlFrame(
					SqlOpcode.EXEC_DONE, REQUEST_ID,
					SqlWire.execDone(2L, SqlStatementTag.UPDATE.wire())));
			awaitUntil(() -> Boolean.TRUE.equals(completed.get()) || error.get() != null);
			assertEquals(null, error.get());
			assertEquals(List.of(1L, 2L), affected);
			assertTrue(pending.isEmpty());
		} finally {
			channel.finishAndReleaseAll();
		}
	}

	private static void awaitUntil(BooleanSupplier cond) {
		final long deadline = System.nanoTime() + AWAIT.toNanos();
		while (!cond.getAsBoolean()) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError("condition not met within " + AWAIT);
			}
			Thread.onSpinWait();
		}
	}
}
