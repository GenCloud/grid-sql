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
package org.genfork.grid.sql.client;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.netty.channel.Channel;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.genfork.grid.sql.client.reactive.ReactiveBatchExchange;
import org.genfork.grid.sql.client.reactive.ReactiveExecExchange;
import org.genfork.grid.sql.client.transport.PendingExchange;
import org.genfork.grid.sql.client.transport.TransportConnection;
import org.genfork.grid.sql.client.transport.TransportConnection.PreparedBatch;
import org.genfork.grid.sql.client.transport.TransportConnection.PreparedExec;

/**
 * One TCP transport; multiplexes {@link TxContext} via hidden sessionId.
 * <p>
 * Wire mux lives on {@link TransportConnection}. Reactive SPI uses
 * {@link ReactiveExecExchange} / {@link ReactiveBatchExchange} (Sinks, write on subscribe).
 * Sync / JDBC use {@link #transport()} {@code CompletionStage} APIs (write-immediate Sync*
 * exchanges) — never {@code Mono.toFuture} and never a CF→Mono bridge.
 * <p>
 * {@code maxTxContexts} is a soft backpressure cap on concurrent {@code SESSION_OPEN}
 * (not a TCP pool size - see {@link ConnectionOptions#maxConnections()}). Independent
 * {@link #begin()} TX and autocommit statements run concurrently on different sessions;
 * isolation is record locks + commit protocol, not channel-wide serialization.
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public final class RemoteConnection implements Connection {
	private static final String ERR_TIMEZONE_REQUIRED = "timezone required";
	private static final String ERR_INVALID_TIMEZONE = "invalid timezone: ";
	private static final String ERR_NOT_CONNECTED = "SQL channel not connected";
	private static final String ERR_MAX_TX = "maxTxContexts exhausted";

	private final TransportConnection transport;

	RemoteConnection(TransportConnection transport) {
		this.transport = transport;
	}

	/**
	 * Preferred ctor: builds {@link TransportConnection} for the live channel.
	 */
	RemoteConnection(
			Channel channel,
			AtomicInteger requestIds,
			Map<Integer, PendingExchange> pending,
			Runnable onPark,
			Runnable onDead,
			AtomicInteger openContexts,
			int maxTxContexts,
			String defaultSchema,
			ConnectionOptions options,
			AtomicReference<ServerMeta> serverMeta
	) {
		this(channel, requestIds, pending, onPark, onDead, openContexts, maxTxContexts,
				defaultSchema, options, serverMeta, SessionRole.PRIMARY);
	}

	RemoteConnection(
			Channel channel,
			AtomicInteger requestIds,
			Map<Integer, PendingExchange> pending,
			Runnable onPark,
			Runnable onDead,
			AtomicInteger openContexts,
			int maxTxContexts,
			String defaultSchema,
			ConnectionOptions options,
			AtomicReference<ServerMeta> serverMeta,
			SessionRole sessionRole
	) {
		this(new TransportConnection(
				channel, requestIds, pending, onPark, onDead, openContexts, maxTxContexts,
				defaultSchema, options, serverMeta, sessionRole));
	}

	public TransportConnection transport() {
		return transport;
	}

	public boolean isOpen() {
		return transport.isOpen();
	}

	public Channel channel() {
		return transport.channel();
	}

	@Override
	public ServerMeta serverMeta() {
		return transport.serverMeta();
	}

	public boolean writerEligible() {
		return transport.writerEligible();
	}

	public String promoteHint() {
		return transport.promoteHint();
	}

	int activeRequests() {
		return transport.activeRequests();
	}

	void markDead() {
		transport.markDead();
	}

	void markClosed() {
		transport.markClosed();
	}

	boolean tryMarkParked() {
		return transport.tryMarkParked();
	}

	void clearParked() {
		transport.clearParked();
	}

	@Override
	public Mono<TxContext> begin() {
		if (!transport.beginAllowed()) {
			return Mono.error(transport.beginNotAllowed());
		}
		return openSession()
				.flatMap(sessionId -> applyTimeout(execRaw(sessionId, "BEGIN", null, 0))
						.flatMap(Result::getRowsUpdated)
						.map(handle -> (TxContext) new RemoteTxContext(
								this, sessionId, handle == null ? 0L : handle)));
	}

	@Override
	public Statement createStatement(String sql) {
		return new RemoteAutocommitStatement(this, sql);
	}

	@Override
	public Flux<Result> executeBatch(List<String> sqls) {
		final List<String> list = sqls == null ? List.of() : List.copyOf(sqls);
		if (list.isEmpty()) {
			return Flux.empty();
		}
		return Flux.usingWhen(
				acquireAutocommitSession(),
				sessionId -> applyTimeout(batchRaw(sessionId, list)),
				this::releaseAutocommitSession,
				(sessionId, _) -> releaseAutocommitSession(sessionId),
				this::releaseAutocommitSession
		);
	}

	@Override
	public Mono<Void> close() {
		return Mono.fromRunnable(transport::park);
	}

	@Override
	public Mono<Void> setTimezone(String zoneId) {
		if (zoneId == null || zoneId.isBlank()) {
			return Mono.error(new IllegalArgumentException(ERR_TIMEZONE_REQUIRED));
		}
		final ZoneId parsed;
		try {
			parsed = ZoneId.of(zoneId.trim());
		} catch (RuntimeException ex) {
			return Mono.error(new IllegalArgumentException(ERR_INVALID_TIMEZONE + zoneId, ex));
		}
		return Mono.defer(() -> {
			transport.applyTimezone(parsed.getId());
			return drainAutocommitIdle();
		});
	}

	Mono<Integer> openSession() {
		return Mono.defer(() -> {
			if (!transport.isOpen()) {
				return Mono.error(new IllegalStateException(ERR_NOT_CONNECTED));
			}
			if (!transport.sessionOpenBudgetAvailable()) {
				return Mono.error(new IllegalStateException(
						ERR_MAX_TX + " maxTxContexts=" + transport.maxTxContexts()));
			}
			final PreparedExec prepared = transport.prepareReactiveSessionOpen();
			final ReactiveExecExchange exchange = prepared.reactiveExchange();
			return track(exchange.mono()
					.doOnSubscribe(_ -> transport.writePrepared(prepared))
					.flatMap(Result::getRowsUpdated)
					.map(v -> v == null ? 0 : v.intValue())
					.doOnSuccess(_ -> transport.onSessionOpened()));
		});
	}

	Mono<Void> closeSession(int sessionId) {
		return track(Mono.defer(() -> {
			if (!transport.isOpen()) {
				transport.onSessionClosed();
				return Mono.empty();
			}
			final PreparedExec prepared = transport.prepareReactiveSessionClose(sessionId);
			final ReactiveExecExchange exchange = prepared.reactiveExchange();
			return exchange.mono()
					.doOnSubscribe(_ -> transport.writePrepared(prepared))
					.then()
					.doFinally(_ -> transport.onSessionClosed());
		}));
	}

	Flux<Result> exec(int sessionId, String sql, Object[] binds) {
		return exec(sessionId, sql, binds, 0);
	}

	Flux<Result> exec(int sessionId, String sql, Object[] binds, int fetchWindowOverride) {
		return applyTimeout(execRaw(sessionId, sql, binds, fetchWindowOverride)).flux();
	}

	Flux<Result> execBatch(int sessionId, List<String> sqls) {
		final List<String> list = sqls == null ? List.of() : List.copyOf(sqls);
		if (list.isEmpty()) {
			return Flux.empty();
		}
		return applyTimeout(batchRaw(sessionId, list));
	}

	Flux<Result> execAutocommit(String sql, Object[] binds) {
		return execAutocommit(sql, binds, 0);
	}

	Flux<Result> execAutocommit(String sql, Object[] binds, int fetchWindowOverride) {
		return Mono.usingWhen(
				acquireAutocommitSession(),
				sessionId -> applyTimeout(execRaw(sessionId, sql, binds, fetchWindowOverride)),
				this::releaseAutocommitSession,
				(sessionId, _) -> releaseAutocommitSession(sessionId),
				this::releaseAutocommitSession
		).flux();
	}

	/**
	 * Autocommit DML/DDL rows-affected without Flux materialization (hot {@code executeUpdate} path).
	 */
	Mono<Long> execAutocommitUpdate(String sql, Object[] binds, int fetchWindowOverride) {
		return Mono.usingWhen(
				acquireAutocommitSession(),
				sessionId -> applyTimeout(execRaw(sessionId, sql, binds, fetchWindowOverride))
						.flatMap(Result::getRowsUpdated),
				this::releaseAutocommitSession,
				(sessionId, _) -> releaseAutocommitSession(sessionId),
				this::releaseAutocommitSession
		);
	}

	private Mono<Integer> acquireAutocommitSession() {
		return Mono.defer(() -> {
			if (!transport.isOpen()) {
				return Mono.error(new IllegalStateException(ERR_NOT_CONNECTED));
			}
			final Integer idle = transport.pollAutocommitIdle();
			if (idle != null) {
				return Mono.just(idle);
			}
			return openSession();
		});
	}

	private Mono<Void> releaseAutocommitSession(Integer sessionId) {
		if (sessionId == null) {
			return Mono.empty();
		}
		if (!transport.isOpen()) {
			return closeSession(sessionId).onErrorResume(err -> Mono.empty());
		}
		if (transport.tryOfferAutocommitIdle(sessionId)) {
			return Mono.empty();
		}
		return closeSession(sessionId).onErrorResume(err -> Mono.empty());
	}

	private Mono<Void> drainAutocommitIdle() {
		final List<Integer> ids = transport.drainAutocommitIdleIds();
		if (ids.isEmpty()) {
			return Mono.empty();
		}
		return Flux.fromIterable(ids)
				.concatMap(id -> closeSession(id).onErrorResume(err -> Mono.empty()))
				.then();
	}

	private Mono<Result> execRaw(int sessionId, String sql, Object[] binds, int fetchWindowOverride) {
		return Mono.defer(() -> {
			if (!transport.isOpen()) {
				return Mono.error(new IllegalStateException(ERR_NOT_CONNECTED));
			}
			final PreparedExec prepared =
					transport.prepareReactiveExec(sessionId, sql, binds, fetchWindowOverride);
			final ReactiveExecExchange exchange = prepared.reactiveExchange();
			transport.noteActiveStart();
			return exchange.mono()
					.doOnSubscribe(_ -> transport.writePrepared(prepared))
					.doFinally(_ -> transport.noteActiveEnd());
		});
	}

	private Flux<Result> batchRaw(int sessionId, List<String> sqls) {
		return Flux.defer(() -> {
			if (!transport.isOpen()) {
				return Flux.error(new IllegalStateException(ERR_NOT_CONNECTED));
			}
			final PreparedBatch prepared = transport.prepareReactiveBatch(sessionId, sqls);
			final ReactiveBatchExchange exchange = prepared.reactiveExchange();
			transport.noteActiveStart();
			return exchange.flux()
					.doOnSubscribe(_ -> transport.writePrepared(prepared))
					.doFinally(_ -> transport.noteActiveEnd());
		});
	}

	private <T> Mono<T> track(Mono<T> mono) {
		return Mono.defer(() -> {
			transport.noteActiveStart();
			return mono.doFinally(_ -> transport.noteActiveEnd());
		});
	}

	private <T> Flux<T> trackFlux(Flux<T> flux) {
		return Flux.defer(() -> {
			transport.noteActiveStart();
			return flux.doFinally(_ -> transport.noteActiveEnd());
		});
	}

	private <T> Mono<T> applyTimeout(Mono<T> mono) {
		final Duration execTimeout = transport.execTimeout();
		if (execTimeout.isZero() || execTimeout.isNegative()) {
			return mono;
		}
		return mono.timeout(execTimeout);
	}

	private <T> Flux<T> applyTimeout(Flux<T> flux) {
		final Duration execTimeout = transport.execTimeout();
		if (execTimeout.isZero() || execTimeout.isNegative()) {
			return flux;
		}
		return flux.timeout(execTimeout);
	}
}
