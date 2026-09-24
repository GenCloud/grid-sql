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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;

import org.genfork.grid.sql.client.ConnectionOptions;
import org.genfork.grid.sql.client.ServerMeta;
import org.genfork.grid.sql.client.SessionRole;
import org.genfork.grid.sql.client.reactive.ReactiveBatchExchange;
import org.genfork.grid.sql.client.reactive.ReactiveExecExchange;
import org.genfork.grid.sql.netty.SqlFrame;
import org.genfork.grid.sql.netty.SqlFrames;
import org.genfork.grid.sql.netty.SqlOpcode;
import org.genfork.grid.sql.netty.SqlWire;

/**
 * Reactor-free wire / session mux for one TCP channel.
 * <p>
 * Dual prepare APIs:
 * <ul>
 *   <li>Reactive {@code prepareReactive*} — {@link ReactiveExecExchange} / {@link ReactiveBatchExchange},
 *       no write (subscribe-driven)</li>
 *   <li>Sync {@code prepare*} / {@link CompletionStage} methods — {@link SyncExecExchange} /
 *       {@link SyncBatchExchange}, write-immediate</li>
 * </ul>
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public final class TransportConnection {
	private static final int AUTOCOMMIT_IDLE_POOL_CAP = 32;
	private static final String ERR_NOT_CONNECTED = "SQL channel not connected";
	private static final String ERR_MAX_TX = "maxTxContexts exhausted";
	private static final String ERR_CHANNEL_CLOSED = "SQL channel closed";
	private static final String ERR_WRITE_FAILED = "SQL write failed";
	private static final String ERR_AUTH_WRITE_FAILED = "AUTH write failed";
	private static final String ERR_BEGIN_ON_READ_REPLICA =
			"BEGIN not supported on READ_REPLICA connection";
	private static final String DEFAULT_TIMEZONE = ConnectionOptions.DEFAULT_TIMEZONE;
	private static final String ERR_TIMEZONE_REQUIRED = "timezone required";
	private static final String ERR_INVALID_TIMEZONE = "invalid timezone: ";
	private static final String ERR_UNEXPECTED_SESSION_OPEN = "unexpected SESSION_OPEN outcome: ";
	private static final Object[] EMPTY_BINDS = new Object[0];

	private final Channel channel;
	private final AtomicInteger requestIds;
	private final Map<Integer, PendingExchange> pending;
	private final Runnable onPark;
	private final Runnable onDead;
	private final AtomicInteger openContexts;
	private final int maxTxContexts;
	private final String defaultSchema;
	private final Duration execTimeout;
	private final int fetchWindow;
	private final AtomicReference<ServerMeta> serverMeta;
	private final SessionRole sessionRole;
	private volatile String sessionTimezone;
	private final AtomicInteger activeRequests = new AtomicInteger();
	private final AtomicBoolean closed = new AtomicBoolean();
	private final AtomicBoolean dead = new AtomicBoolean();
	private final AtomicBoolean parked = new AtomicBoolean();
	private final ConcurrentLinkedQueue<Integer> autocommitIdle = new ConcurrentLinkedQueue<>();

	public TransportConnection(
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
		this.channel = channel;
		this.requestIds = requestIds;
		this.pending = pending;
		this.onPark = onPark == null ? () -> {
		} : onPark;
		this.onDead = onDead == null ? () -> {
		} : onDead;
		this.openContexts = openContexts;
		this.maxTxContexts = maxTxContexts <= 0 ? Integer.MAX_VALUE : maxTxContexts;
		this.defaultSchema = defaultSchema == null || defaultSchema.isBlank()
				? ConnectionOptions.DEFAULT_SCHEMA
				: defaultSchema;
		this.execTimeout = options == null ? Duration.ZERO : options.execTimeout();
		this.fetchWindow = options == null
				? ConnectionOptions.DEFAULT_FETCH_WINDOW
				: options.fetchWindow();
		this.serverMeta = serverMeta == null ? new AtomicReference<>(ServerMeta.EMPTY) : serverMeta;
		this.sessionRole = sessionRole == null ? SessionRole.PRIMARY : sessionRole;
		this.sessionTimezone = options == null ? DEFAULT_TIMEZONE : options.timezone();
	}

	/**
	 * Lazy EXEC / AUTH / SESSION registration (no write).
	 *
	 * @author: GenCloud
	 * @date: 2026/08
	 * @since: 1.0
	 */
	public record PreparedExec(
			int requestId,
			PendingExchange exchange,
			byte opcode,
			byte[] payload,
			int execSessionId,
			String execSql,
			Object[] execBinds
	) {
		public PreparedExec(int requestId, PendingExchange exchange, byte opcode, byte[] payload) {
			this(requestId, exchange, opcode, payload, 0, null, EMPTY_BINDS);
		}

		public boolean deferredExecEncode() {
			return opcode == SqlOpcode.EXEC && execSql != null;
		}

		public SyncExecExchange syncExchange() {
			return (SyncExecExchange) exchange;
		}

		public ReactiveExecExchange reactiveExchange() {
			return (ReactiveExecExchange) exchange;
		}
	}

	/**
	 * Lazy BATCH_EXEC registration (no write).
	 *
	 * @author: GenCloud
	 * @date: 2026/08
	 * @since: 1.0
	 */
	public record PreparedBatch(
			int requestId,
			PendingExchange exchange,
			int sessionId,
			List<String> sqls
	) {
		public SyncBatchExchange syncExchange() {
			return (SyncBatchExchange) exchange;
		}

		public ReactiveBatchExchange reactiveExchange() {
			return (ReactiveBatchExchange) exchange;
		}
	}

	public boolean isOpen() {
		return !closed.get() && !dead.get() && channel != null && channel.isActive();
	}

	public Channel channel() {
		return channel;
	}

	public ServerMeta serverMeta() {
		return serverMeta.get();
	}

	public SessionRole sessionRole() {
		return sessionRole;
	}

	public Duration execTimeout() {
		return execTimeout;
	}

	public int fetchWindow() {
		return fetchWindow;
	}

	public int activeRequests() {
		return activeRequests.get();
	}

	public void noteActiveStart() {
		activeRequests.incrementAndGet();
	}

	public void noteActiveEnd() {
		activeRequests.updateAndGet(n -> Math.max(0, n - 1));
	}

	public boolean writerEligible() {
		return serverMeta().writerEligible();
	}

	public String promoteHint() {
		return serverMeta().promoteHint();
	}

	public void markDead() {
		if (!dead.compareAndSet(false, true)) {
			return;
		}
		closed.set(true);
		autocommitIdle.clear();
		activeRequests.set(0);
		for (PendingExchange ex : pending.values()) {
			ex.fail(new IllegalStateException(ERR_CHANNEL_CLOSED));
		}
		pending.clear();
		onDead.run();
	}

	public void markClosed() {
		closed.set(true);
	}

	public boolean tryMarkParked() {
		return parked.compareAndSet(false, true);
	}

	public void clearParked() {
		parked.set(false);
	}

	/**
	 * Park this channel back to the factory idle pool (sync / reactive close).
	 */
	public void park() {
		if (dead.get()) {
			return;
		}
		onPark.run();
	}

	public CompletionStage<Void> parkStage() {
		park();
		return CompletableFuture.completedFuture(null);
	}

	public boolean beginAllowed() {
		return !sessionRole.isReadReplica();
	}

	public UnsupportedOperationException beginNotAllowed() {
		return new UnsupportedOperationException(ERR_BEGIN_ON_READ_REPLICA);
	}

	public Integer pollAutocommitIdle() {
		return autocommitIdle.poll();
	}

	/**
	 * Offer session back to idle pool; {@code false} when at capacity (caller should close).
	 */
	public boolean tryOfferAutocommitIdle(int sessionId) {
		final int idleCap = Math.min(AUTOCOMMIT_IDLE_POOL_CAP, maxTxContexts);
		if (autocommitIdle.size() >= idleCap) {
			return false;
		}
		autocommitIdle.offer(sessionId);
		return true;
	}

	public List<Integer> drainAutocommitIdleIds() {
		final List<Integer> ids = new ArrayList<>();
		Integer id;
		while ((id = autocommitIdle.poll()) != null) {
			ids.add(id);
		}
		return ids;
	}

	public void onSessionOpened() {
		openContexts.incrementAndGet();
	}

	public void onSessionClosed() {
		openContexts.updateAndGet(n -> Math.max(0, n - 1));
	}

	public boolean sessionOpenBudgetAvailable() {
		return openContexts.get() < maxTxContexts;
	}

	public int maxTxContexts() {
		return maxTxContexts;
	}

	public String defaultSchema() {
		return defaultSchema;
	}

	public String sessionTimezone() {
		return sessionTimezone;
	}

	public void applyTimezone(String zoneId) {
		sessionTimezone = zoneId;
	}

	// --- Reactive prepare (no write) ---

	public PreparedExec prepareReactiveExec(
			int sessionId,
			String sql,
			Object[] binds,
			int fetchWindowOverride
	) {
		requireOpen();
		final Object[] safeBinds = binds == null ? EMPTY_BINDS : binds;
		final int window = fetchWindowOverride > 0 ? fetchWindowOverride : fetchWindow;
		return prepareReactiveExecBody(sessionId, sql, safeBinds, window);
	}

	public PreparedBatch prepareReactiveBatch(int sessionId, List<String> sqls) {
		requireOpen();
		final List<String> list = sqls == null ? List.of() : List.copyOf(sqls);
		return prepareReactiveBatchExchange(sessionId, list);
	}

	public PreparedExec prepareReactiveSessionOpen() {
		requireOpen();
		if (!sessionOpenBudgetAvailable()) {
			throw new IllegalStateException(ERR_MAX_TX + " maxTxContexts=" + maxTxContexts);
		}
		return prepareReactiveRequest(
				SqlOpcode.SESSION_OPEN,
				SqlWire.sessionOpen(sessionRole, defaultSchema, sessionTimezone),
				fetchWindow);
	}

	public PreparedExec prepareReactiveSessionClose(int sessionId) {
		requireOpen();
		return prepareReactiveRequest(SqlOpcode.SESSION_CLOSE, SqlWire.sessionId(sessionId), fetchWindow);
	}

	public static PreparedExec prepareReactiveAuth(
			Channel channel,
			AtomicInteger requestIds,
			Map<Integer, PendingExchange> pending,
			String user,
			String password
	) {
		final int authId = requestIds.getAndIncrement();
		final ReactiveExecExchange auth = new ReactiveExecExchange();
		pending.put(authId, auth);
		return new PreparedExec(authId, auth, SqlOpcode.AUTH, SqlWire.auth(user, password));
	}

	// --- Sync prepare (no write; Sync CompletionStage path writes immediately) ---

	public PreparedExec prepareExec(int sessionId, String sql, Object[] binds, int fetchWindowOverride) {
		requireOpen();
		final Object[] safeBinds = binds == null ? EMPTY_BINDS : binds;
		final int window = fetchWindowOverride > 0 ? fetchWindowOverride : fetchWindow;
		return prepareSyncExecBody(sessionId, sql, safeBinds, window);
	}

	public PreparedBatch prepareBatch(int sessionId, List<String> sqls) {
		requireOpen();
		final List<String> list = sqls == null ? List.of() : List.copyOf(sqls);
		return prepareSyncBatchExchange(sessionId, list);
	}

	public PreparedExec prepareSessionOpen() {
		requireOpen();
		if (!sessionOpenBudgetAvailable()) {
			throw new IllegalStateException(ERR_MAX_TX + " maxTxContexts=" + maxTxContexts);
		}
		return prepareSyncRequest(
				SqlOpcode.SESSION_OPEN,
				SqlWire.sessionOpen(sessionRole, defaultSchema, sessionTimezone),
				fetchWindow);
	}

	public PreparedExec prepareSessionClose(int sessionId) {
		requireOpen();
		return prepareSyncRequest(SqlOpcode.SESSION_CLOSE, SqlWire.sessionId(sessionId), fetchWindow);
	}

	public static PreparedExec prepareAuth(
			Channel channel,
			AtomicInteger requestIds,
			Map<Integer, PendingExchange> pending,
			String user,
			String password
	) {
		final int authId = requestIds.getAndIncrement();
		final SyncExecExchange auth = new SyncExecExchange();
		pending.put(authId, auth);
		return new PreparedExec(authId, auth, SqlOpcode.AUTH, SqlWire.auth(user, password));
	}

	public void writePrepared(PreparedExec prepared) {
		writePrepared(channel, pending, prepared);
	}

	public void writePrepared(PreparedBatch prepared) {
		try {
			SqlFrames.writeAndFlush(channel, SqlOpcode.BATCH_EXEC, prepared.requestId(),
					out -> SqlWire.batchExecInto(out, prepared.sessionId(), prepared.sqls()));
		} catch (RuntimeException ex) {
			pending.remove(prepared.requestId());
			prepared.exchange().fail(ex);
		}
	}

	public static void writePrepared(
			Channel channel,
			Map<Integer, PendingExchange> pending,
			PreparedExec prepared
	) {
		if (prepared.deferredExecEncode()) {
			writeExecInto(channel, pending, prepared);
			return;
		}
		channel.writeAndFlush(new SqlFrame(prepared.opcode(), prepared.requestId(), prepared.payload()))
				.addListener(writeFailListener(pending, prepared));
	}

	private static void writeExecInto(
			Channel channel,
			Map<Integer, PendingExchange> pending,
			PreparedExec prepared
	) {
		final ByteBuf buf = channel.alloc().buffer(64);
		try {
			final int start = buf.writerIndex();
			buf.writeIntLE(0);
			buf.writeByte(SqlOpcode.EXEC);
			buf.writeIntLE(prepared.requestId());
			SqlWire.execInto(buf, prepared.execSessionId(), prepared.execSql(), prepared.execBinds());
			final int frameLen = buf.writerIndex() - start - Integer.BYTES;
			if (frameLen <= 0 || frameLen > SqlOpcode.MAX_FRAME) {
				buf.release();
				pending.remove(prepared.requestId());
				prepared.exchange().fail(new IllegalArgumentException("bad frameLen " + frameLen));
				return;
			}
			buf.setIntLE(start, frameLen);
		} catch (RuntimeException ex) {
			buf.release();
			pending.remove(prepared.requestId());
			prepared.exchange().fail(ex);
			return;
		}
		channel.writeAndFlush(buf).addListener(writeFailListener(pending, prepared));
	}

	private static ChannelFutureListener writeFailListener(
			Map<Integer, PendingExchange> pending,
			PreparedExec prepared
	) {
		return f -> {
			if (!f.isSuccess()) {
				pending.remove(prepared.requestId());
				final String msg = prepared.opcode() == SqlOpcode.AUTH
						? ERR_AUTH_WRITE_FAILED
						: ERR_WRITE_FAILED + " opcode=" + prepared.opcode();
				prepared.exchange().fail(f.cause() != null ? f.cause()
						: new IllegalStateException(msg));
			}
		};
	}

	// --- Sync immediate (prepare + write + outcome CF) ---

	public CompletionStage<Integer> openSession() {
		if (!isOpen()) {
			return CompletableFuture.failedFuture(new IllegalStateException(ERR_NOT_CONNECTED));
		}
		if (!sessionOpenBudgetAvailable()) {
			return CompletableFuture.failedFuture(new IllegalStateException(
					ERR_MAX_TX + " maxTxContexts=" + maxTxContexts));
		}
		final PreparedExec prepared = prepareSyncRequest(
				SqlOpcode.SESSION_OPEN,
				SqlWire.sessionOpen(sessionRole, defaultSchema, sessionTimezone),
				fetchWindow);
		writePrepared(prepared);
		return track(prepared.syncExchange().outcome().thenApply(outcome -> {
			if (outcome instanceof TransportOutcome.SessionOpen open) {
				onSessionOpened();
				return open.sessionId();
			}
			if (outcome instanceof TransportOutcome.Dml dml) {
				onSessionOpened();
				return (int) dml.affected();
			}
			throw new IllegalStateException(ERR_UNEXPECTED_SESSION_OPEN + outcome);
		}));
	}

	public CompletionStage<Void> closeSession(int sessionId) {
		if (!isOpen()) {
			onSessionClosed();
			return CompletableFuture.completedFuture(null);
		}
		final PreparedExec prepared = prepareSessionClose(sessionId);
		writePrepared(prepared);
		return track(prepared.syncExchange().outcome().handle((outcome, err) -> {
			onSessionClosed();
			if (err != null) {
				if (err instanceof RuntimeException re) {
					throw re;
				}
				throw new IllegalStateException(err);
			}
			return null;
		}));
	}

	public CompletionStage<TransportOutcome> exec(
			int sessionId,
			String sql,
			Object[] binds,
			int fetchWindowOverride
	) {
		if (!isOpen()) {
			return CompletableFuture.failedFuture(new IllegalStateException(ERR_NOT_CONNECTED));
		}
		final PreparedExec prepared = prepareExec(sessionId, sql, binds, fetchWindowOverride);
		writePrepared(prepared);
		return track(prepared.syncExchange().outcome());
	}

	public CompletionStage<List<TransportOutcome>> batchExec(int sessionId, List<String> sqls) {
		final List<String> list = sqls == null ? List.of() : List.copyOf(sqls);
		if (list.isEmpty()) {
			return CompletableFuture.completedFuture(List.of());
		}
		if (!isOpen()) {
			return CompletableFuture.failedFuture(new IllegalStateException(ERR_NOT_CONNECTED));
		}
		final PreparedBatch prepared = prepareBatch(sessionId, list);
		writePrepared(prepared);
		return track(prepared.syncExchange().outcomes());
	}

	public CompletionStage<Integer> acquireAutocommitSession() {
		if (!isOpen()) {
			return CompletableFuture.failedFuture(new IllegalStateException(ERR_NOT_CONNECTED));
		}
		final Integer idle = pollAutocommitIdle();
		if (idle != null) {
			return CompletableFuture.completedFuture(idle);
		}
		return openSession();
	}

	public CompletionStage<Void> releaseAutocommitSession(Integer sessionId) {
		if (sessionId == null) {
			return CompletableFuture.completedFuture(null);
		}
		if (!isOpen()) {
			return closeSession(sessionId).exceptionally(err -> null);
		}
		if (tryOfferAutocommitIdle(sessionId)) {
			return CompletableFuture.completedFuture(null);
		}
		return closeSession(sessionId).exceptionally(err -> null);
	}

	public CompletionStage<TransportOutcome> execAutocommit(String sql, Object[] binds, int window) {
		if (!isOpen()) {
			return CompletableFuture.failedFuture(new IllegalStateException(ERR_NOT_CONNECTED));
		}
		final Integer idle = pollAutocommitIdle();
		if (idle != null) {
			return execAndRelease(idle, sql, binds, window);
		}
		final CompletableFuture<TransportOutcome> result = new CompletableFuture<>();
		openSession().whenComplete((sessionId, acquireErr) -> {
			if (acquireErr != null) {
				result.completeExceptionally(acquireErr);
				return;
			}
			execAndRelease(sessionId, sql, binds, window).whenComplete((outcome, err) -> {
				if (err != null) {
					result.completeExceptionally(err);
				} else {
					result.complete(outcome);
				}
			});
		});
		return result;
	}

	private CompletionStage<TransportOutcome> execAndRelease(
			int sessionId,
			String sql,
			Object[] binds,
			int window
	) {
		final CompletableFuture<TransportOutcome> result = new CompletableFuture<>();
		exec(sessionId, sql, binds, window).whenComplete((outcome, err) ->
				releaseAutocommitSession(sessionId).whenComplete((ignored, releaseErr) -> {
					if (err != null) {
						result.completeExceptionally(err);
					} else if (releaseErr != null) {
						result.completeExceptionally(releaseErr);
					} else {
						result.complete(outcome);
					}
				}));
		return result;
	}

	public CompletionStage<List<TransportOutcome>> batchAutocommit(List<String> sqls) {
		final CompletableFuture<List<TransportOutcome>> result = new CompletableFuture<>();
		acquireAutocommitSession().whenComplete((sessionId, acquireErr) -> {
			if (acquireErr != null) {
				result.completeExceptionally(acquireErr);
				return;
			}
			batchExec(sessionId, sqls).whenComplete((outcomes, err) ->
					releaseAutocommitSession(sessionId).whenComplete((ignored, releaseErr) -> {
						if (err != null) {
							result.completeExceptionally(err);
						} else if (releaseErr != null) {
							result.completeExceptionally(releaseErr);
						} else {
							result.complete(outcomes);
						}
					}));
		});
		return result;
	}

	/**
	 * AUTH write-immediate helper for Sync factory connect path.
	 */
	public static CompletionStage<TransportOutcome> auth(
			Channel channel,
			AtomicInteger requestIds,
			Map<Integer, PendingExchange> pending,
			String user,
			String password
	) {
		final PreparedExec prepared = prepareAuth(channel, requestIds, pending, user, password);
		writePrepared(channel, pending, prepared);
		return prepared.syncExchange().outcome();
	}

	public CompletionStage<Void> setTimezone(String zoneId) {
		if (zoneId == null || zoneId.isBlank()) {
			return CompletableFuture.failedFuture(new IllegalArgumentException(ERR_TIMEZONE_REQUIRED));
		}
		final ZoneId parsed;
		try {
			parsed = ZoneId.of(zoneId.trim());
		} catch (RuntimeException ex) {
			return CompletableFuture.failedFuture(
					new IllegalArgumentException(ERR_INVALID_TIMEZONE + zoneId, ex));
		}
		applyTimezone(parsed.getId());
		return drainAutocommitIdle();
	}

	public CompletionStage<Void> drainAutocommitIdle() {
		final List<CompletionStage<Void>> closes = new ArrayList<>();
		Integer id;
		while ((id = autocommitIdle.poll()) != null) {
			closes.add(closeSession(id).exceptionally(err -> null));
		}
		if (closes.isEmpty()) {
			return CompletableFuture.completedFuture(null);
		}
		CompletableFuture<Void> all = CompletableFuture.completedFuture(null);
		for (CompletionStage<Void> close : closes) {
			all = all.thenCombine(close, (a, b) -> null);
		}
		return all;
	}

	private void requireOpen() {
		if (!isOpen()) {
			throw new IllegalStateException(ERR_NOT_CONNECTED);
		}
	}

	private PreparedBatch prepareReactiveBatchExchange(int sessionId, List<String> sqls) {
		final int id = requestIds.getAndIncrement();
		final IntConsumer fetchSender = fetchSender(id);
		final Runnable cancelSender = cancelSender(id);
		final ReactiveBatchExchange collector =
				new ReactiveBatchExchange(sqls.size(), fetchWindow, fetchSender, cancelSender);
		pending.put(id, collector);
		return new PreparedBatch(id, collector, sessionId, sqls);
	}

	private PreparedBatch prepareSyncBatchExchange(int sessionId, List<String> sqls) {
		final int id = requestIds.getAndIncrement();
		final IntConsumer fetchSender = fetchSender(id);
		final Runnable cancelSender = cancelSender(id);
		final SyncBatchExchange collector =
				new SyncBatchExchange(sqls.size(), fetchWindow, fetchSender, cancelSender);
		pending.put(id, collector);
		return new PreparedBatch(id, collector, sessionId, sqls);
	}

	private PreparedExec prepareReactiveExecBody(int sessionId, String sql, Object[] binds, int window) {
		final int effectiveWindow = window > 0 ? window : fetchWindow;
		final int id = requestIds.getAndIncrement();
		final ReactiveExecExchange collector =
				new ReactiveExecExchange(effectiveWindow, fetchSender(id), cancelSender(id));
		pending.put(id, collector);
		return new PreparedExec(id, collector, SqlOpcode.EXEC, null, sessionId, sql, binds);
	}

	private PreparedExec prepareSyncExecBody(int sessionId, String sql, Object[] binds, int window) {
		final int effectiveWindow = window > 0 ? window : fetchWindow;
		final int id = requestIds.getAndIncrement();
		final SyncExecExchange collector =
				new SyncExecExchange(effectiveWindow, fetchSender(id), cancelSender(id));
		pending.put(id, collector);
		return new PreparedExec(id, collector, SqlOpcode.EXEC, null, sessionId, sql, binds);
	}

	private PreparedExec prepareReactiveRequest(byte opcode, byte[] payload, int window) {
		final int effectiveWindow = window > 0 ? window : fetchWindow;
		final int id = requestIds.getAndIncrement();
		final ReactiveExecExchange collector;
		if (opcode == SqlOpcode.EXEC) {
			collector = new ReactiveExecExchange(effectiveWindow, fetchSender(id), cancelSender(id));
		} else {
			collector = new ReactiveExecExchange();
		}
		pending.put(id, collector);
		return new PreparedExec(id, collector, opcode, payload);
	}

	private PreparedExec prepareSyncRequest(byte opcode, byte[] payload, int window) {
		final int effectiveWindow = window > 0 ? window : fetchWindow;
		final int id = requestIds.getAndIncrement();
		final SyncExecExchange collector;
		if (opcode == SqlOpcode.EXEC) {
			collector = new SyncExecExchange(effectiveWindow, fetchSender(id), cancelSender(id));
		} else {
			collector = new SyncExecExchange();
		}
		pending.put(id, collector);
		return new PreparedExec(id, collector, opcode, payload);
	}

	private IntConsumer fetchSender(int requestId) {
		return n -> {
			if (channel.isActive()) {
				channel.writeAndFlush(new SqlFrame(SqlOpcode.FETCH, requestId, SqlWire.fetch(n)));
			}
		};
	}

	private Runnable cancelSender(int requestId) {
		return () -> {
			if (channel.isActive()) {
				channel.writeAndFlush(new SqlFrame(SqlOpcode.CANCEL, requestId, SqlWire.cancel()));
			}
		};
	}

	private <T> CompletionStage<T> track(CompletionStage<T> stage) {
		noteActiveStart();
		return stage.whenComplete((v, err) -> noteActiveEnd());
	}
}
