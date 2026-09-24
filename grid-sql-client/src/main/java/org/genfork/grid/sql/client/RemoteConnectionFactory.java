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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.genfork.grid.common.WriterFenceSignals;
import org.genfork.grid.sql.client.reactive.ReactiveExecExchange;
import org.genfork.grid.sql.client.transport.PendingExchange;
import org.genfork.grid.sql.client.transport.RemoteConnectSupport;
import org.genfork.grid.sql.client.transport.SqlClientInboundHandler;
import org.genfork.grid.sql.client.transport.SqlClientPipeline;
import org.genfork.grid.sql.client.transport.TransportConnection;
import org.genfork.grid.sql.client.transport.TransportConnection.PreparedExec;

/**
 * Owns up to {@link ConnectionOptions#maxConnections()} TCP channels + shared ELG.
 * Each channel multiplexes up to {@link ConnectionOptions#maxTxContexts()} logical sessions.
 * <p>
 * Selection prefers the live channel with the lowest in-flight request count.
 * Multi-host: sticky endpoint, then next on connect fail / dead channel; connect failures
 * also retry per {@link ConnectionOptions#retryMode()} / {@code maxRetries}.
 * <p>
 * Reactive {@link #obtain()} / {@link #warmup()} are subscribe-driven Mono chains (AUTH write on
 * subscribe). Sync / JDBC use {@link #obtainStage()} (CompletionStage, write-immediate AUTH).
 * Pool helpers ({@link #purgeClosed}, idle / least-loaded) are shared; neither path wraps the other.
 * <p>
 * Warm / first {@link #obtain()} fills {@link ConnectionOptions#minConnections()} into idle;
 * further {@code obtain()} may lazy-open up to {@code maxConnections}. Optional
 * {@link #warmup()} when {@code warmup=true} shares the same min-fill Mono (does not gate obtain).
 * Convenience ctors that take {@code maxConnections} + {@code warmup=true} also set
 * {@code minConnections=maxConnections} so warmup opens N sockets.
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public final class RemoteConnectionFactory implements ConnectionFactory {
	/** Fallback rediscover timeout when connect+exec budgets are zero/negative. */
	private static final Duration REDISCOVER_FALLBACK_TIMEOUT = Duration.ofSeconds(10);
	private static final String ERR_READ_ENDPOINTS_REQUIRED =
			"createReadFactory requires readEndpoints=host:port,...";
	private static final String ERR_REPLICA_APPLY_LAG_STALE = "replica apply lag stale";

	private final List<HostEndpoint> endpoints;
	private final AtomicInteger stickyIndex = new AtomicInteger(0);
	private final String user;
	private final String password;
	private final String defaultSchema;
	private final ConnectionOptions options;
	private final SessionRole factoryRole;
	private final ReadEndpointSelector readSelector;
	private final EventLoopGroup group;
	private final Bootstrap bootstrapTemplate;
	private final AtomicInteger requestIds = new AtomicInteger(1);
	private final AtomicBoolean disposed = new AtomicBoolean();
	private final ConcurrentLinkedQueue<RemoteConnection> idle = new ConcurrentLinkedQueue<>();
	private final ConcurrentLinkedQueue<RemoteConnection> all = new ConcurrentLinkedQueue<>();
	private final AtomicInteger liveCount = new AtomicInteger();
	private final AtomicInteger idleCount = new AtomicInteger();
	private final AtomicReference<Mono<Void>> warmupMono = new AtomicReference<>();
	private final AtomicReference<CompletableFuture<Void>> warmupStage = new AtomicReference<>();
	private final AtomicReference<ServerMeta> lastServerMeta = new AtomicReference<>(ServerMeta.EMPTY);
	/** Sticky pin epoch: writerEligible ∧ regionEpoch (0 = no pin / region disabled). */
	private final AtomicLong pinnedRegionEpoch = new AtomicLong(0L);
	/** Coalesce concurrent PROMOTE_NOTIFY-driven rediscovers (never on Netty EL). */
	private final AtomicBoolean rediscoverInFlight = new AtomicBoolean(false);
	/** Off-EL min-pool refill after destroy (daemon). */
	private static final Executor POOL_REFILL_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
		final Thread t = new Thread(r, "sql-client-pool-refill");
		t.setDaemon(true);
		return t;
	});

	public RemoteConnectionFactory(String host, int port, String user, String password) {
		this(List.of(new HostEndpoint(host, port)), user, password, ConnectionOptions.defaults(),
				ConnectionOptions.DEFAULT_SCHEMA);
	}

	public RemoteConnectionFactory(String host, int port, String user, String password, int maxTxContexts) {
		this(List.of(new HostEndpoint(host, port)), user, password, ConnectionOptions.builder()
				.maxTxContexts(maxTxContexts)
				.build(), ConnectionOptions.DEFAULT_SCHEMA);
	}

	public RemoteConnectionFactory(
			String host,
			int port,
			String user,
			String password,
			int maxTxContexts,
			int maxConnections,
			String defaultSchema,
			boolean warmup
	) {
		this(List.of(new HostEndpoint(host, port)), user, password, ConnectionOptions.builder()
				.maxTxContexts(maxTxContexts)
				.maxConnections(maxConnections)
				.minConnections(warmup ? maxConnections : ConnectionOptions.DEFAULT_MIN_CONNECTIONS)
				.warmup(warmup)
				.build(), defaultSchema);
	}

	public RemoteConnectionFactory(
			String host,
			int port,
			String user,
			String password,
			int maxTxContexts,
			int maxConnections,
			String defaultSchema,
			boolean warmup,
			Duration execTimeout
	) {
		this(List.of(new HostEndpoint(host, port)), user, password, ConnectionOptions.builder()
				.maxTxContexts(maxTxContexts)
				.maxConnections(maxConnections)
				.minConnections(warmup ? maxConnections : ConnectionOptions.DEFAULT_MIN_CONNECTIONS)
				.warmup(warmup)
				.execTimeout(execTimeout == null ? Duration.ZERO : execTimeout)
				.build(), defaultSchema);
	}

	public RemoteConnectionFactory(String host, int port, String user, String password, ConnectionOptions options) {
		this(List.of(new HostEndpoint(host, port)), user, password, options, ConnectionOptions.DEFAULT_SCHEMA);
	}

	public RemoteConnectionFactory(
			String host,
			int port,
			String user,
			String password,
			ConnectionOptions options,
			String defaultSchema
	) {
		this(List.of(new HostEndpoint(host, port)), user, password, options, defaultSchema);
	}

	public RemoteConnectionFactory(
			List<HostEndpoint> endpoints,
			String user,
			String password,
			ConnectionOptions options,
			String defaultSchema
	) {
		this(endpoints, user, password, options, defaultSchema, SessionRole.PRIMARY);
	}

	public RemoteConnectionFactory(
			List<HostEndpoint> endpoints,
			String user,
			String password,
			ConnectionOptions options,
			String defaultSchema,
			SessionRole factoryRole
	) {
		Objects.requireNonNull(endpoints, "endpoints");
		if (endpoints.isEmpty()) {
			throw new IllegalArgumentException("endpoints must not be empty");
		}

		final List<HostEndpoint> copy = new ArrayList<>(endpoints.size());
		for (HostEndpoint ep : endpoints) {
			Objects.requireNonNull(ep, "endpoint");
			copy.add(ep);
		}

		this.endpoints = Collections.unmodifiableList(copy);
		this.user = user == null ? "" : user;
		this.password = password == null ? "" : password;
		this.options = options == null ? ConnectionOptions.defaults() : options;
		this.defaultSchema = defaultSchema == null || defaultSchema.isBlank()
				? ConnectionOptions.DEFAULT_SCHEMA
				: defaultSchema;
		this.factoryRole = factoryRole == null ? SessionRole.PRIMARY : factoryRole;
		this.readSelector = this.factoryRole.isReadReplica()
				? new ReadEndpointSelector(this.endpoints)
				: null;
		this.group = new NioEventLoopGroup(1, Thread.ofPlatform().name("sql-client-io-", 0).factory());
		final Bootstrap boot = new Bootstrap()
				.group(group)
				.channel(NioSocketChannel.class);
		final Duration connectTimeout = this.options.connectTimeout();
		if (!connectTimeout.isZero() && !connectTimeout.isNegative()) {
			final long ms = Math.min(Integer.MAX_VALUE, Math.max(1L, connectTimeout.toMillis()));
			boot.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) ms);
		}
		this.bootstrapTemplate = boot;
	}

	/**
	 * Writer-only pool from URL (ignores routing). Apps with optional {@code readEndpoints}
	 * should use {@link ConnectionFactory#fromUrl(String)} so replica auto-route engages.
	 */
	public static RemoteConnectionFactory fromUrl(String url) {
		final GridSqlUri u = GridSqlUri.parse(url);
		return new RemoteConnectionFactory(
				u.endpoints(), u.user(), u.password(), u.options(), u.schema(), SessionRole.PRIMARY);
	}

	/**
	 * Read pool: connects to {@code readEndpoints}, opens {@link SessionRole#READ_REPLICA} sessions.
	 * Fail-fast when readEndpoints empty.
	 */
	public static RemoteConnectionFactory createReadFactory(String url) {
		final GridSqlUri u = GridSqlUri.parse(url);
		final List<HostEndpoint> readEps = u.options().readEndpoints();
		if (readEps.isEmpty()) {
			throw new IllegalArgumentException(ERR_READ_ENDPOINTS_REQUIRED);
		}
		final ConnectionOptions readOpts = ConnectionOptions.builder()
				.minConnections(u.options().minConnections())
				.maxConnections(u.options().maxReadConnections())
				.maxTxContexts(u.options().maxTxContexts())
				.warmup(u.options().warmup())
				.execTimeout(u.options().execTimeout())
				.connectTimeout(u.options().connectTimeout())
				.readTimeout(u.options().readTimeout())
				.writeTimeout(u.options().writeTimeout())
				.maxRetries(u.options().maxRetries())
				.retryDelay(u.options().retryDelay())
				.retryMode(u.options().retryMode())
				.timezone(u.options().timezone())
				.readPreference(ReadPreference.REPLICA)
				.staleReadPolicy(u.options().staleReadPolicy())
				.maxReadConnections(u.options().maxReadConnections())
				.readEndpoints(readEps)
				.build();
		return new RemoteConnectionFactory(
				readEps, u.user(), u.password(), readOpts, u.schema(), SessionRole.READ_REPLICA);
	}

	public SessionRole factoryRole() {
		return factoryRole;
	}

	public ConnectionOptions options() {
		return options;
	}

	/** Immutable endpoint list from URL / ctor. */
	public List<HostEndpoint> endpoints() {
		return endpoints;
	}

	/** Current sticky endpoint index (for tests / diagnostics). */
	public int stickyIndex() {
		return stickyIndex.get();
	}

	/** Last ServerMeta observed during AUTH or an ERROR response. */
	public ServerMeta lastServerMeta() {
		return lastServerMeta.get();
	}

	public boolean writerEligible() {
		return lastServerMeta().writerEligible();
	}

	public long pinnedRegionEpoch() {
		return pinnedRegionEpoch.get();
	}

	public long regionEpoch() {
		return lastServerMeta().regionEpoch();
	}

	/**
	 * Mid-op orchid / region fence reject: close live channels and AUTH-rediscover the
	 * writer-eligible peer matching promoteHint / region epoch. Does not silent-rotate sticky.
	 */
	public Mono<Connection> rediscoverWriter() {
		purgeClosed();
		for (RemoteConnection c : all) {
			destroy(c);
		}
		return openNewOnce(true);
	}

	/**
	 * One transparent retry after writer rediscover on orchid / region fence.
	 * Elle-safe when meta already shows the prior sticky is ineligible.
	 */
	public <T> Mono<T> withWriterRediscoverRetry(Mono<T> operation) {
		Objects.requireNonNull(operation, "operation");
		return operation.onErrorResume(ex -> {
			if (!WriterFenceSignals.requiresWriterRediscover(ex)) {
				return Mono.error(ex);
			}
			return rediscoverWriter().then(operation);
		});
	}

	/**
	 * True when sticky pin is valid for the given meta (writerEligible ∧ epoch match).
	 */
	public boolean stickyPinMatches(ServerMeta meta) {
		if (meta == null || !meta.writerEligible()) {
			return false;
		}
		final long pinned = pinnedRegionEpoch.get();
		if (pinned <= 0L || !meta.hasRegionEpoch()) {
			return true;
		}
		return meta.regionEpoch() == pinned;
	}

	public String promoteHint() {
		return lastServerMeta().promoteHint();
	}

	@Override
	public Mono<Void> warmup() {
		if (!options.warmup()) {
			return Mono.empty();
		}
		return ensureMinPool();
	}

	@Override
	public Mono<Connection> obtain() {
		return Mono.defer(() -> {
			if (disposed.get()) {
				return Mono.error(new IllegalStateException("connection factory disposed"));
			}
			return ensureMinPool().then(Mono.defer(this::acquireFromPool));
		});
	}

	/**
	 * Sync / JDBC obtain path — {@link CompletionStage} only (never {@code Mono.toFuture}).
	 */
	public CompletionStage<Connection> obtainStage() {
		if (disposed.get()) {
			return CompletableFuture.failedFuture(new IllegalStateException("connection factory disposed"));
		}
		return ensureMinPoolStage().thenCompose(ignored -> acquireFromPoolStage());
	}

	/**
	 * Shared min-fill Mono: open until {@link ConnectionOptions#minConnections()} live channels,
	 * parking each into idle. In-flight coalescer only — cleared after complete so destroy
	 * below min can refill.
	 */
	private Mono<Void> ensureMinPool() {
		for (; ; ) {
			final Mono<Void> existing = warmupMono.get();
			if (existing != null) {
				return existing;
			}
			final int target = options.minConnections();
			if (liveCount.get() >= target) {
				return Mono.empty();
			}
			final AtomicReference<Mono<Void>> createdHolder = new AtomicReference<>();
			final Mono<Void> created = Flux.range(0, target)
					.concatMap(_ -> Mono.defer(() -> {
						if (disposed.get() || liveCount.get() >= target) {
							return Mono.empty();
						}
						return openNewWithRetry().flatMap(c -> {
							park((RemoteConnection) c);
							return Mono.empty();
						});
					}))
					.then()
					.doFinally(_ -> warmupMono.compareAndSet(createdHolder.get(), null));
			createdHolder.set(created);
			if (warmupMono.compareAndSet(null, created)) {
				return created;
			}
		}
	}

	/**
	 * Sync min-fill CompletionStage (parallel to {@link #ensureMinPool()}, does not wrap it).
	 * In-flight coalescer only — cleared after complete for self-heal refill.
	 */
	private CompletionStage<Void> ensureMinPoolStage() {
		for (; ; ) {
			final CompletableFuture<Void> existing = warmupStage.get();
			if (existing != null) {
				return existing;
			}
			final int target = options.minConnections();
			if (liveCount.get() >= target) {
				return CompletableFuture.completedFuture(null);
			}
			final CompletableFuture<Void> created = new CompletableFuture<>();
			if (warmupStage.compareAndSet(null, created)) {
				created.whenComplete((ignored, err) -> warmupStage.compareAndSet(created, null));
				fillMinPool(0, target, created);
				return created;
			}
		}
	}

	private void fillMinPool(int opened, int target, CompletableFuture<Void> done) {
		if (disposed.get() || liveCount.get() >= target || opened >= target) {
			done.complete(null);
			return;
		}
		openNewWithRetryStage().whenComplete((conn, err) -> {
			if (err != null) {
				done.completeExceptionally(err);
				return;
			}
			park((RemoteConnection) conn);
			fillMinPool(opened + 1, target, done);
		});
	}

	/**
	 * After destroy drops below min, schedule async refill off the Netty event loop.
	 */
	private void maybeRefillMinPoolAsync() {
		if (disposed.get()) {
			return;
		}
		if (liveCount.get() >= options.minConnections()) {
			return;
		}
		POOL_REFILL_EXECUTOR.execute(() -> {
			if (disposed.get() || liveCount.get() >= options.minConnections()) {
				return;
			}
			ensureMinPoolStage().whenComplete((ignored, err) -> {
				if (err != null && !disposed.get()) {
					// best-effort: next obtain will retry ensureMinPool
				}
			});
		});
	}

	/**
	 * Pick an idle / least-loaded channel or open a new one (after min pool is ready).
	 */
	private Mono<Connection> acquireFromPool() {
		final RemoteConnection taken = takeIdleOrLeastLoaded();
		if (taken != null) {
			return Mono.just(taken);
		}
		if (liveCount.get() >= options.maxConnections()) {
			final RemoteConnection least = findLeastLoaded();
			if (least != null) {
				least.clearParked();
				return Mono.just(least);
			}
			return Mono.error(new IllegalStateException(
					"maxConnections=" + options.maxConnections() + " exhausted"));
		}
		return openNewWithRetry();
	}

	private CompletionStage<Connection> acquireFromPoolStage() {
		final RemoteConnection taken = takeIdleOrLeastLoaded();
		if (taken != null) {
			return CompletableFuture.completedFuture(taken);
		}
		if (liveCount.get() >= options.maxConnections()) {
			final RemoteConnection least = findLeastLoaded();
			if (least != null) {
				least.clearParked();
				return CompletableFuture.completedFuture(least);
			}
			return CompletableFuture.failedFuture(new IllegalStateException(
					"maxConnections=" + options.maxConnections() + " exhausted"));
		}
		return openNewWithRetryStage();
	}

	/**
	 * Poll best idle channel (shared by Mono / Stage acquire). Null when none open.
	 */
	private RemoteConnection takeIdleOrLeastLoaded() {
		purgeClosed();
		RemoteConnection bestIdle = null;
		int bestIdleLoad = Integer.MAX_VALUE;
		for (RemoteConnection c : idle) {
			if (!c.isOpen()) {
				continue;
			}
			final int load = c.activeRequests();
			if (load < bestIdleLoad) {
				bestIdleLoad = load;
				bestIdle = c;
			}
		}
		if (bestIdle != null && idle.remove(bestIdle)) {
			bestIdle.clearParked();
			idleCount.updateAndGet(n -> Math.max(0, n - 1));
			if (bestIdle.isOpen()) {
				return bestIdle;
			}
			destroy(bestIdle);
		}
		return null;
	}

	private void purgeClosed() {
		for (RemoteConnection c : all) {
			if (!c.isOpen()) {
				destroy(c);
			}
		}
	}

	private RemoteConnection findLeastLoaded() {
		RemoteConnection best = null;
		int bestLoad = Integer.MAX_VALUE;
		for (RemoteConnection c : all) {
			if (!c.isOpen()) {
				continue;
			}
			final int load = c.activeRequests();
			if (load < bestLoad) {
				bestLoad = load;
				best = c;
			}
		}
		return best;
	}

	private Mono<Connection> openNewWithRetry() {
		final int maxAttempts = options.retryMode() == RetryMode.OFF
				? 1
				: 1 + options.maxRetries();
		return attemptOpen(0, maxAttempts);
	}

	private Mono<Connection> attemptOpen(int attempt, int maxAttempts) {
		return openNewOnce().onErrorResume(err -> {
			if (RemoteConnectSupport.isNonRetryable(err) || attempt + 1 >= maxAttempts) {
				return Mono.error(err);
			}
			advanceStickyPastFailed();
			final Duration delay = RemoteConnectSupport.backoffDelay(options, attempt);
			final Mono<Connection> next = attemptOpen(attempt + 1, maxAttempts);
			if (delay.isZero() || delay.isNegative()) {
				return next;
			}
			return Mono.delay(delay).then(next);
		});
	}

	private CompletionStage<Connection> openNewWithRetryStage() {
		final int maxAttempts = options.retryMode() == RetryMode.OFF
				? 1
				: 1 + options.maxRetries();
		return attemptOpenStage(0, maxAttempts);
	}

	private CompletionStage<Connection> attemptOpenStage(int attempt, int maxAttempts) {
		return openNewOnceStage(true).handle((conn, err) -> {
			if (err == null) {
				return CompletableFuture.completedFuture(conn);
			}
			if (RemoteConnectSupport.isNonRetryable(err) || attempt + 1 >= maxAttempts) {
				return CompletableFuture.<Connection>failedFuture(err);
			}
			advanceStickyPastFailed();
			final Duration delay = RemoteConnectSupport.backoffDelay(options, attempt);
			if (delay.isZero() || delay.isNegative()) {
				return attemptOpenStage(attempt + 1, maxAttempts);
			}
			final CompletableFuture<Connection> delayed = new CompletableFuture<>();
			group.schedule(() -> attemptOpenStage(attempt + 1, maxAttempts).whenComplete((c, e) -> {
				if (e != null) {
					delayed.completeExceptionally(e);
				} else {
					delayed.complete(c);
				}
			}), delay.toMillis(), TimeUnit.MILLISECONDS);
			return delayed;
		}).thenCompose(stage -> stage);
	}

	/**
	 * Connect sticky-first, then remaining endpoints; AUTH write on subscribe.
	 */
	private Mono<Connection> openNewOnce() {
		return openNewOnce(true);
	}

	private Mono<Connection> openNewOnce(boolean allowAuthRedirect) {
		final Map<Integer, PendingExchange> pending =
				new ConcurrentHashMap<>(Math.max(16, options.maxTxContexts() * 4));
		final AtomicReference<ServerMeta> connectionMeta =
				new AtomicReference<>(ServerMeta.EMPTY);
		final SqlClientInboundHandler inboundHandler = new SqlClientInboundHandler(pending, meta -> {
			connectionMeta.set(meta);
			onServerMeta(meta);
		});
		final Bootstrap bootstrap = bootstrapTemplate.clone()
				.handler(new SqlClientPipeline(inboundHandler, options.readTimeout(), options.writeTimeout()));

		return connectStickyThenNext(bootstrap)
				.flatMap(connected -> {
					final Channel ch = connected.channel();
					final AtomicInteger openContexts = new AtomicInteger(0);
					final AtomicReference<RemoteConnection> holder = new AtomicReference<>();
					final RemoteConnection conn = new RemoteConnection(
							ch, requestIds, pending,
							() -> park(holder.get()),
							() -> destroy(holder.get()),
							openContexts, options.maxTxContexts(), defaultSchema, options, connectionMeta,
							factoryRole);
					holder.set(conn);
					liveCount.incrementAndGet();
					all.add(conn);
					ch.closeFuture().addListener(_ -> conn.markDead());

					final PreparedExec auth = TransportConnection.prepareReactiveAuth(
							ch, requestIds, pending, user, password);
					final ReactiveExecExchange authExchange = auth.reactiveExchange();
					return authExchange.mono()
							.doOnSubscribe(_ -> TransportConnection.writePrepared(ch, pending, auth))
							.doOnError(_ -> {
								destroy(conn);
								ch.close();
							})
							.then(Mono.defer(() ->
									redirectAfterAuth(conn, connected.endpointIndex(), allowAuthRedirect)));
				});
	}

	/**
	 * Sync connect + AUTH write-immediate.
	 */
	private CompletionStage<Connection> openNewOnceStage(boolean allowAuthRedirect) {
		final Map<Integer, PendingExchange> pending =
				new ConcurrentHashMap<>(Math.max(16, options.maxTxContexts() * 4));
		final AtomicReference<ServerMeta> connectionMeta =
				new AtomicReference<>(ServerMeta.EMPTY);
		final SqlClientInboundHandler inboundHandler = new SqlClientInboundHandler(pending, meta -> {
			connectionMeta.set(meta);
			onServerMeta(meta);
		});
		final Bootstrap bootstrap = bootstrapTemplate.clone()
				.handler(new SqlClientPipeline(inboundHandler, options.readTimeout(), options.writeTimeout()));

		return connectStickyThenNextStage(bootstrap)
				.thenCompose(connected -> {
					final Channel ch = connected.channel();
					final AtomicInteger openContexts = new AtomicInteger(0);
					final AtomicReference<RemoteConnection> holder = new AtomicReference<>();
					final RemoteConnection conn = new RemoteConnection(
							ch, requestIds, pending,
							() -> park(holder.get()),
							() -> destroy(holder.get()),
							openContexts, options.maxTxContexts(), defaultSchema, options, connectionMeta,
							factoryRole);
					holder.set(conn);
					liveCount.incrementAndGet();
					all.add(conn);
					ch.closeFuture().addListener(_ -> conn.markDead());

					return TransportConnection.auth(ch, requestIds, pending, user, password)
							.exceptionallyCompose(err -> {
								destroy(conn);
								ch.close();
								return CompletableFuture.failedFuture(err);
							})
							.thenCompose(ignored ->
									redirectAfterAuthStage(conn, connected.endpointIndex(), allowAuthRedirect));
				});
	}

	/**
	 * Prefer promoteHint and fire-and-forget {@link #rediscoverWriter()} (subscribe, never
	 * {@code .block()}) when this channel is no longer the writer. Netty EL only enqueues;
	 * Reactor subscribe runs off the event loop via the factory pipeline.
	 */
	private void onServerMeta(ServerMeta meta) {
		if (meta == null) {
			return;
		}
		lastServerMeta.set(meta);
		if (factoryRole.isReadReplica()) {
			return;
		}
		if (meta.writerEligible()) {
			if (meta.hasRegionEpoch()) {
				pinnedRegionEpoch.set(meta.regionEpoch());
			}
			return;
		}
		// Blank ineligible meta without promote hint = no HA signal (legacy EMPTY); do not
		// tear down the live channel via rediscoverWriter().
		if (meta.nodeId().isBlank() && !meta.hasPromoteHint()) {
			return;
		}
		preferHintEndpoint(meta);
		scheduleRediscoverWriter();
	}

	/**
	 * Point sticky at promoteHint (or advance past dead writer) before rediscover connect.
	 */
	private void preferHintEndpoint(ServerMeta meta) {
		if (meta.hasPromoteHint()) {
			final int hintIndex = endpointIndexForHint(meta.promoteHint());
			if (hintIndex >= 0) {
				stickyIndex.set(hintIndex);
				pinnedRegionEpoch.set(0L);
				return;
			}
		}
		if (!meta.writerEligible()) {
			advanceStickyPastFailed();
		} else {
			pinnedRegionEpoch.set(0L);
		}
	}

	private void scheduleRediscoverWriter() {
		if (disposed.get()) {
			return;
		}
		if (!rediscoverInFlight.compareAndSet(false, true)) {
			return;
		}
		Duration budget = options.connectTimeout().plus(options.execTimeout());
		if (budget.isZero() || budget.isNegative()) {
			budget = REDISCOVER_FALLBACK_TIMEOUT;
		}
		rediscoverWriter()
				.timeout(budget)
				.doFinally(_ -> rediscoverInFlight.set(false))
				.subscribe(
						ignored -> {
							// Connection remains in factory live set for subsequent create().
						},
						ignored -> {
							// next create() / mid-op rediscover retries
						});
	}

	private Mono<Connection> redirectAfterAuth(
			RemoteConnection connection,
			int connectedIndex,
			boolean allowAuthRedirect
	) {
		final ServerMeta meta = connection.serverMeta();
		lastServerMeta.set(meta);

		if (factoryRole.isReadReplica()) {
			if (meta.applyLagStale()) {
				if (readSelector != null && connectedIndex >= 0 && connectedIndex < endpoints.size()) {
					readSelector.markStale(endpoints.get(connectedIndex), true);
				}
				advanceStickyPastFailed();
				destroy(connection);
				if (!allowAuthRedirect) {
					return Mono.error(new IllegalStateException(ERR_REPLICA_APPLY_LAG_STALE));
				}
				return openNewOnce(false);
			}

			if (readSelector != null && connectedIndex >= 0 && connectedIndex < endpoints.size()) {
				readSelector.markStale(endpoints.get(connectedIndex), false);
			}

			stickyIndex.set(connectedIndex);
			return Mono.just(connection);
		}

		if (meta.writerEligible()) {
			pinSticky(connectedIndex, meta);
			return Mono.just(connection);
		}

		if (!allowAuthRedirect || !meta.hasPromoteHint()) {
			return Mono.just(connection);
		}

		final int targetIndex = endpointIndexForHint(meta.promoteHint());
		if (targetIndex < 0 || targetIndex == connectedIndex) {
			return Mono.just(connection);
		}

		stickyIndex.set(targetIndex);
		destroy(connection);
		return openNewOnce(false);
	}

	private CompletionStage<Connection> redirectAfterAuthStage(
			RemoteConnection connection,
			int connectedIndex,
			boolean allowAuthRedirect
	) {
		final ServerMeta meta = connection.serverMeta();
		lastServerMeta.set(meta);

		if (factoryRole.isReadReplica()) {
			if (meta.applyLagStale()) {
				if (readSelector != null && connectedIndex >= 0 && connectedIndex < endpoints.size()) {
					readSelector.markStale(endpoints.get(connectedIndex), true);
				}
				advanceStickyPastFailed();
				destroy(connection);
				if (!allowAuthRedirect) {
					return CompletableFuture.failedFuture(
							new IllegalStateException(ERR_REPLICA_APPLY_LAG_STALE));
				}
				return openNewOnceStage(false);
			}

			if (readSelector != null && connectedIndex >= 0 && connectedIndex < endpoints.size()) {
				readSelector.markStale(endpoints.get(connectedIndex), false);
			}

			stickyIndex.set(connectedIndex);
			return CompletableFuture.completedFuture(connection);
		}

		if (meta.writerEligible()) {
			pinSticky(connectedIndex, meta);
			return CompletableFuture.completedFuture(connection);
		}

		if (!allowAuthRedirect || !meta.hasPromoteHint()) {
			return CompletableFuture.completedFuture(connection);
		}

		final int targetIndex = endpointIndexForHint(meta.promoteHint());
		if (targetIndex < 0 || targetIndex == connectedIndex) {
			return CompletableFuture.completedFuture(connection);
		}

		stickyIndex.set(targetIndex);
		destroy(connection);
		return openNewOnceStage(false);
	}

	private void pinSticky(int endpointIndex, ServerMeta meta) {
		stickyIndex.set(endpointIndex);
		if (meta.hasRegionEpoch()) {
			pinnedRegionEpoch.set(meta.regionEpoch());
		}
	}

	private int endpointIndexForHint(String hint) {
		if (hint == null || hint.isBlank()) {
			return -1;
		}

		final String normalizedHint = hint.trim();
		final int hintedOrdinal = RemoteConnectSupport.trailingOrdinal(normalizedHint);
		for (int i = 0; i < endpoints.size(); i++) {
			final String host = endpoints.get(i).host();
			final String shortHost = host.contains(".") ? host.substring(0, host.indexOf('.')) : host;
			if (host.equalsIgnoreCase(normalizedHint) || shortHost.equalsIgnoreCase(normalizedHint)) {
				return i;
			}
			final int hostOrdinal = RemoteConnectSupport.trailingOrdinal(shortHost);
			if (hintedOrdinal > 0 && hostOrdinal == hintedOrdinal) {
				return i;
			}
		}

		final int orderedIndex = hintedOrdinal - 1;
		return orderedIndex >= 0 && orderedIndex < endpoints.size() ? orderedIndex : -1;
	}

	private Mono<ConnectedChannel> connectStickyThenNext(Bootstrap bootstrap) {
		final int n = endpoints.size();
		if (factoryRole.isReadReplica() && readSelector != null) {
			final HostEndpoint preferred = readSelector.acquire(true);
			int start = 0;
			for (int i = 0; i < n; i++) {
				if (endpoints.get(i).equals(preferred)) {
					start = i;
					break;
				}
			}

			return connectFrom(bootstrap, start, 0, n, null)
					.doFinally(_ -> readSelector.release(preferred));
		}

		final ServerMeta meta = lastServerMeta.get();
		if (meta != null && !stickyPinMatches(meta) && meta.hasPromoteHint()) {
			final int hintIndex = endpointIndexForHint(meta.promoteHint());
			if (hintIndex >= 0) {
				return connectFrom(bootstrap, hintIndex, 0, n, null);
			}
		}

		final int start = Math.floorMod(stickyIndex.get(), n);
		return connectFrom(bootstrap, start, 0, n, null);
	}

	private Mono<ConnectedChannel> connectFrom(
			Bootstrap bootstrap,
			int start,
			int tried,
			int n,
			Throwable last
	) {
		if (tried >= n) {
			return Mono.error(last != null ? last : new IllegalStateException("all endpoints failed"));
		}

		final int idx = (start + tried) % n;
		final HostEndpoint ep = endpoints.get(idx);
		return RemoteConnectSupport.connectOnce(bootstrap, ep, options.connectTimeout())
				.map(ch -> {
					stickyIndex.set(idx);
					return new ConnectedChannel(ch, idx);
				})
				.onErrorResume(err -> connectFrom(bootstrap, start, tried + 1, n, err));
	}

	private CompletionStage<ConnectedChannel> connectStickyThenNextStage(Bootstrap bootstrap) {
		final int n = endpoints.size();
		if (factoryRole.isReadReplica() && readSelector != null) {
			final HostEndpoint preferred = readSelector.acquire(true);
			int start = 0;
			for (int i = 0; i < n; i++) {
				if (endpoints.get(i).equals(preferred)) {
					start = i;
					break;
				}
			}

			return connectFromStage(bootstrap, start, 0, n, null)
					.whenComplete((ch, err) -> readSelector.release(preferred));
		}

		final ServerMeta meta = lastServerMeta.get();
		if (meta != null && !stickyPinMatches(meta) && meta.hasPromoteHint()) {
			final int hintIndex = endpointIndexForHint(meta.promoteHint());
			if (hintIndex >= 0) {
				return connectFromStage(bootstrap, hintIndex, 0, n, null);
			}
		}

		final int start = Math.floorMod(stickyIndex.get(), n);
		return connectFromStage(bootstrap, start, 0, n, null);
	}

	private CompletionStage<ConnectedChannel> connectFromStage(
			Bootstrap bootstrap,
			int start,
			int tried,
			int n,
			Throwable last
	) {
		if (tried >= n) {
			return CompletableFuture.failedFuture(
					last != null ? last : new IllegalStateException("all endpoints failed"));
		}

		final int idx = (start + tried) % n;
		final HostEndpoint ep = endpoints.get(idx);
		return RemoteConnectSupport.connectOnceStage(bootstrap, ep, options.connectTimeout())
				.thenApply(ch -> {
					stickyIndex.set(idx);
					return new ConnectedChannel(ch, idx);
				})
				.exceptionallyCompose(err -> connectFromStage(bootstrap, start, tried + 1, n, err));
	}

	/**
	 * Rotate sticky past the current endpoint (connect fail / dead channel only).
	 * Mid-op orchid / region fence must use {@link #rediscoverWriter()} - never silent rotate.
	 */
	public void advanceStickyPastFailed() {
		final int n = endpoints.size();
		if (n <= 1) {
			return;
		}
		stickyIndex.updateAndGet(i -> (Math.floorMod(i, n) + 1) % n);
		pinnedRegionEpoch.set(0L);
	}

	void park(RemoteConnection conn) {
		if (conn == null || disposed.get()) {
			destroy(conn);
			return;
		}

		if (!conn.isOpen()) {
			destroy(conn);
			return;
		}

		if (conn.activeRequests() > 0) {
			return;
		}

		if (conn.tryMarkParked()) {
			idle.offer(conn);
			idleCount.incrementAndGet();
		}
	}

	void destroy(RemoteConnection conn) {
		if (conn == null) {
			return;
		}

		if (idle.remove(conn)) {
			conn.clearParked();
			idleCount.updateAndGet(n -> Math.max(0, n - 1));
		}

		final boolean removed = all.remove(conn);
		if (removed) {
			liveCount.updateAndGet(n -> Math.max(0, n - 1));
		}

		conn.markClosed();

		final Channel ch = conn.channel();
		if (ch != null && ch.isActive()) {
			ch.close();
		}
		maybeRefillMinPoolAsync();
	}

	public void dispose() {
		disposed.set(true);
		RemoteConnection c;
		while ((c = all.poll()) != null) {
			c.markClosed();
			final Channel ch = c.channel();
			if (ch != null) {
				ch.close();
			}
		}
		idle.clear();
		liveCount.set(0);
		idleCount.set(0);
		group.shutdownGracefully();
	}

	public int maxTxContexts() {
		return options.maxTxContexts();
	}

	public int maxConnections() {
		return options.maxConnections();
	}

	public int minConnections() {
		return options.minConnections();
	}

	public String defaultSchema() {
		return defaultSchema;
	}

	/**
	 * AUTH user from URL / connect properties (may be empty when open-auth).
	 */
	public String user() {
		return user;
	}

	public Duration execTimeout() {
		return options.execTimeout();
	}

	public int activeChannels() {
		int n = 0;
		for (RemoteConnection c : all) {
			if (c.isOpen()) {
				n++;
			}
		}
		return n;
	}

	public int idleChannels() {
		return idleCount.get();
	}

	/**
	 * Return a borrowed channel to the idle pool (shared DataSource / multiplex).
	 * No-op when disposed or the channel is closed / still has in-flight requests.
	 */
	public void release(Connection conn) {
		if (conn instanceof RemoteConnection remote) {
			park(remote);
		}
	}

	/**
	 * Connected channel paired with its configured endpoint index.
	 *
	 * @author: GenCloud
	 * @date: 2026/08
	 * @since: 1.0
	 */
	private record ConnectedChannel(Channel channel, int endpointIndex) {
	}
}
