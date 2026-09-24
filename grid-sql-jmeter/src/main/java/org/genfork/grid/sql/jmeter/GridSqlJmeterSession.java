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
package org.genfork.grid.sql.jmeter;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.genfork.grid.sql.client.Connection;
import org.genfork.grid.sql.client.ConnectionOptions;
import org.genfork.grid.sql.client.GridSqlUri;
import org.genfork.grid.sql.client.RemoteConnection;
import org.genfork.grid.sql.client.RemoteConnectionFactory;
import org.genfork.grid.sql.client.Result;
import org.genfork.grid.sql.client.Row;
import org.genfork.grid.sql.client.RowMetadata;
import org.genfork.grid.sql.client.TxContext;
import reactor.core.publisher.Mono;

/**
 * Per-JMeter-thread session: one TCP + FIFO of inflight op futures (WebSocket-sampler model).
 * <p>
 * Write enqueues a {@link CompletableFuture}; Read awaits the head. No sampler thread pools.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
public final class GridSqlJmeterSession {

	public static final String VAR_SESSION = "grid.sql.jmeter.session";

	public static final String PARAM_GRID_URL = "GRID_URL";
	public static final String PARAM_USER = "USER";
	public static final String PARAM_PASSWORD = "PASSWORD";
	public static final String PARAM_CONNECT_TIMEOUT_MS = "CONNECT_TIMEOUT_MS";
	public static final String PARAM_OP_TIMEOUT_MS = "OP_TIMEOUT_MS";
	public static final String PARAM_KEY_SPACE = "KEY_SPACE";
	public static final String PARAM_SEED_ROWS = "SEED_ROWS";
	public static final String PARAM_MIX_PROFILE = "MIX_PROFILE";
	public static final String PARAM_STRIPE_THREADS = "STRIPE_THREADS";
	/** WRITE_ONLY: upserts per TX unit (one OpLog/orchid force for the batch). */
	public static final String PARAM_WRITE_BATCH_SIZE = "WRITE_BATCH_SIZE";

	public static final String DEFAULT_GRID_URL = "grid://@127.0.0.1:15432/public";
	public static final int DEFAULT_CONNECT_TIMEOUT_MS = 5_000;
	public static final int DEFAULT_OP_TIMEOUT_MS = 10_000;
	public static final int DEFAULT_WRITE_BATCH_SIZE = 1;
	public static final int MAX_WRITE_BATCH_SIZE = 64;
	/** Default GUI/CLI key space = capacity (low PK collision). Use CONTENTION_KEY_SPACE for lock stress. */
	public static final int DEFAULT_KEY_SPACE = 1_000_000;
	/**
	 * Capacity seed working set (eq-limit / join). Caps at {@link #keySpace}.
	 * Keep small: setup is sequential RTT; 10k rows ≈ 50s and bloated tables crush load TPS.
	 */
	public static final int DEFAULT_SEED_ROWS = 1_000;

	/** Multi-row INSERT chunk size for setUp (grammar allows {@code VALUES (...), (...)}). */
	private static final int SEED_BATCH_SIZE = 100;

	/** Capacity profile key space (low contention under 64 threads). */
	public static final int CAPACITY_KEY_SPACE = 1_000_000;
	/** Contention-stress profile. */
	public static final int CONTENTION_KEY_SPACE = 10_000;

	/**
	 * Per-suite SQL mix. Capacity has no short-TX (locks); chaos/stress keep it.
	 */
	public enum MixProfile {
		/** EQ 50 / UPSERT 40 / JOIN 10 — max TPS, no record-lock TX. */
		CAPACITY(50, 40, 0, 10),
		/** EQ 40 / UPSERT 30 / short-TX 20 / JOIN 10 — lock-collision chaos. */
		CHAOS(40, 30, 20, 10),
		/** EQ 30 / UPSERT 50 / short-TX 10 / JOIN 10 — overload soak. */
		STRESS(30, 50, 10, 10),
		/** EQ 90 / JOIN 10 — read-only capacity stamp (no writes). */
		READ_ONLY(90, 0, 0, 10),
		/** UPSERT 100 — write-only capacity stamp (no reads / TX). */
		WRITE_ONLY(0, 100, 0, 0);

		private final int weightEqLimit;
		private final int weightUpsert;
		private final int weightShortTx;
		private final int weightCountJoin;

		MixProfile(int weightEqLimit, int weightUpsert, int weightShortTx, int weightCountJoin) {
			this.weightEqLimit = weightEqLimit;
			this.weightUpsert = weightUpsert;
			this.weightShortTx = weightShortTx;
			this.weightCountJoin = weightCountJoin;
		}

		public int weightEqLimit() {
			return weightEqLimit;
		}

		public int weightUpsert() {
			return weightUpsert;
		}

		public int weightShortTx() {
			return weightShortTx;
		}

		public int weightCountJoin() {
			return weightCountJoin;
		}

		public int weightTotal() {
			return weightEqLimit + weightUpsert + weightShortTx + weightCountJoin;
		}

		public static MixProfile parse(String raw) {
			if (raw == null || raw.isBlank()) {
				return CAPACITY;
			}
			return MixProfile.valueOf(raw.trim().toUpperCase(Locale.ROOT));
		}
	}

	private static final String JDBC_SCHEME_PREFIX = "jdbc:";

	private final RemoteConnectionFactory factory;
	private final AtomicReference<Connection> connection = new AtomicReference<>();
	private final Duration opTimeout;
	private final int keySpace;
	private final int seedRows;
	private final MixProfile mixProfile;
	/** Fixed at open — never use live {@code getTotalThreads()} (ramp shifts stripes → PK collisions). */
	private final int stripeThreads;
	private final int writeBatchSize;
	private final GridSqlLoadSqlTemplates sqlTemplates;
	private final ConcurrentLinkedQueue<CompletableFuture<Void>> inflight = new ConcurrentLinkedQueue<>();

	private GridSqlJmeterSession(
			RemoteConnectionFactory factory,
			Connection connection,
			Duration opTimeout,
			int keySpace,
			int seedRows,
			MixProfile mixProfile,
			int stripeThreads,
			int writeBatchSize,
			GridSqlLoadSqlTemplates sqlTemplates
	) {
		this.factory = factory;
		this.connection.set(connection);
		this.opTimeout = opTimeout;
		this.keySpace = keySpace;
		this.seedRows = seedRows;
		this.mixProfile = mixProfile == null ? MixProfile.CAPACITY : mixProfile;
		this.stripeThreads = Math.max(1, stripeThreads);
		this.writeBatchSize = clampWriteBatchSize(writeBatchSize);
		this.sqlTemplates = sqlTemplates == null ? GridSqlLoadSqlTemplates.defaults() : sqlTemplates;
	}

	public static GridSqlJmeterSession open(
			String rawUrl,
			String paramUser,
			String paramPassword,
			int connectTimeoutMs,
			int opTimeoutMs,
			int keySpace,
			int seedRows,
			MixProfile mixProfile,
			int stripeThreads
	) {
		return open(
				rawUrl, paramUser, paramPassword, connectTimeoutMs, opTimeoutMs,
				keySpace, seedRows, mixProfile, stripeThreads, DEFAULT_WRITE_BATCH_SIZE);
	}

	public static GridSqlJmeterSession open(
			String rawUrl,
			String paramUser,
			String paramPassword,
			int connectTimeoutMs,
			int opTimeoutMs,
			int keySpace,
			int seedRows,
			MixProfile mixProfile,
			int stripeThreads,
			int writeBatchSize
	) {
		return open(
				rawUrl, paramUser, paramPassword, connectTimeoutMs, opTimeoutMs,
				keySpace, seedRows, mixProfile, stripeThreads, writeBatchSize,
				GridSqlLoadSqlTemplates.resolve(GridSqlLoadSqlTemplates.PropSource.SYSTEM));
	}

	public static GridSqlJmeterSession open(
			String rawUrl,
			String paramUser,
			String paramPassword,
			int connectTimeoutMs,
			int opTimeoutMs,
			int keySpace,
			int seedRows,
			MixProfile mixProfile,
			int stripeThreads,
			int writeBatchSize,
			GridSqlLoadSqlTemplates sqlTemplates
	) {
		final String url = rawUrl == null ? DEFAULT_GRID_URL : rawUrl.trim();
		if (url.toLowerCase(Locale.ROOT).startsWith(JDBC_SCHEME_PREFIX)) {
			throw new IllegalArgumentException("Use product grid:// URL, not jdbc:grid:// (got: " + url + ")");
		}
		final GridSqlUri uri = GridSqlUri.parse(url);
		final String user = paramUser == null || paramUser.isBlank() ? uri.user() : paramUser.trim();
		final String password = paramPassword == null ? uri.password() : paramPassword;
		final Duration opTimeout = Duration.ofMillis(Math.max(1, opTimeoutMs));
		final ConnectionOptions options = ConnectionOptions.builder()
				.maxTxContexts(uri.maxTxContexts())
				.maxConnections(uri.maxConnections())
				.warmup(uri.warmup())
				.connectTimeout(Duration.ofMillis(Math.max(1, connectTimeoutMs)))
				.execTimeout(opTimeout)
				.build();
		final RemoteConnectionFactory factory = new RemoteConnectionFactory(
				uri.endpoints(), user, password, options, uri.schema());
		final Connection conn = factory.obtain()
				.timeout(Duration.ofMillis(Math.max(1, connectTimeoutMs)))
				.toFuture()
				.join();
		if (conn == null) {
			factory.dispose();
			throw new IllegalStateException("RemoteConnectionFactory.obtain() returned null");
		}
		final int stripes = stripeThreads > 0
				? stripeThreads
				: resolveStripeThreads();
		return new GridSqlJmeterSession(
				factory, conn, opTimeout, Math.max(1, keySpace), Math.max(0, seedRows),
				mixProfile, stripes, writeBatchSize, sqlTemplates);
	}

	private static int clampWriteBatchSize(int writeBatchSize) {
		if (writeBatchSize < DEFAULT_WRITE_BATCH_SIZE) {
			return DEFAULT_WRITE_BATCH_SIZE;
		}
		if (writeBatchSize > MAX_WRITE_BATCH_SIZE) {
			return MAX_WRITE_BATCH_SIZE;
		}
		return writeBatchSize;
	}

	private static int resolveStripeThreads() {
		try {
			return Math.max(1, JMeterContextService.getContext().getThreadGroup().getNumThreads());
		} catch (Exception ignored) {
			return 1;
		}
	}

	public static void bind(GridSqlJmeterSession session) {
		final JMeterVariables vars = JMeterContextService.getContext().getVariables();
		vars.putObject(VAR_SESSION, session);
	}

	public static GridSqlJmeterSession require() {
		final JMeterVariables vars = JMeterContextService.getContext().getVariables();
		final Object raw = vars.getObject(VAR_SESSION);
		if (!(raw instanceof GridSqlJmeterSession session)) {
			throw new IllegalStateException("GridSql session missing; run Open sampler first");
		}
		return session;
	}

	public static void unbind() {
		final JMeterVariables vars = JMeterContextService.getContext().getVariables();
		vars.remove(VAR_SESSION);
	}

	public int keySpace() {
		return keySpace;
	}

	public int seedRows() {
		return seedRows;
	}

	public MixProfile mixProfile() {
		return mixProfile;
	}

	public int stripeThreads() {
		return stripeThreads;
	}

	/**
	 * WRITE_ONLY amortize: upserts packed into one TX unit (one durable barrier).
	 */
	public int writeBatchSize() {
		return writeBatchSize;
	}

	/**
	 * SQL templates resolved at {@link #open} (sampler / {@code -J} / defaults).
	 */
	public GridSqlLoadSqlTemplates sqlTemplates() {
		return sqlTemplates;
	}

	public Duration opTimeout() {
		return opTimeout;
	}

	public Connection connection() {
		ensureLive();
		return connection.get();
	}

	/**
	 * Enqueue SQL completion future; returns immediately after subscribe (Write sampler).
	 */
	public void enqueueWrite(CompletableFuture<Void> future) {
		Objects.requireNonNull(future, "future");
		inflight.offer(future);
	}

	/**
	 * Await next inflight completion (Read sampler).
	 */
	public void awaitNextRead() throws Exception {
		final CompletableFuture<Void> future = inflight.poll();
		if (future == null) {
			throw new IllegalStateException("no inflight write to read");
		}
		GridSqlJmeterAwait.awaitOp(future, opTimeout);
	}

	public CompletableFuture<Void> startQuery(String sql) {
		ensureLive();
		final Connection c = connection.get();
		final Mono<Void> pipeline = c.createStatement(sql)
				.execute()
				.concatMap(r -> r.map((Row row, RowMetadata meta) -> Boolean.TRUE))
				.then();
		return GridSqlJmeterAwait.toTimedFuture(pipeline, opTimeout);
	}

	public CompletableFuture<Void> startUpdate(String sql) {
		ensureLive();
		final Connection c = connection.get();
		final Mono<Void> pipeline = c.createStatement(sql)
				.execute()
				.concatMap(Result::getRowsUpdated)
				.reduce(0L, Long::sum)
				.then();
		return GridSqlJmeterAwait.toTimedFuture(pipeline, opTimeout);
	}

	/**
	 * WRITE_ONLY amortize: {@code batchSize} UPSERTs in one explicit TX → one OpLog/orchid force.
	 */
	public CompletableFuture<Void> startWriteBatch(int batchSize) {
		ensureLive();
		final int n = clampWriteBatchSize(batchSize);
		final Connection c = connection.get();
		final String[] sqls = new String[n];
		for (int i = 0; i < n; i++) {
			final int key = nextKey();
			final String val = "u-" + key + "-" + ThreadLocalRandom.current().nextInt(1_000_000);
			sqls[i] = sqlTemplates.renderUpsert(key, val);
		}
		final Mono<Void> pipeline = Mono.usingWhen(
				c.begin(),
				tx -> {
					Mono<Void> chain = Mono.empty();
					for (int i = 0; i < sqls.length; i++) {
						final String sql = sqls[i];
						chain = chain.then(execTx(tx, sql));
					}
					return chain.then(tx.commit());
				},
				TxContext::close,
				(tx, err) -> tx.rollback().then(tx.close()).onErrorComplete(),
				TxContext::close
		);
		return GridSqlJmeterAwait.toTimedFuture(pipeline, shortTxTimeout());
	}

	public CompletableFuture<Void> startShortTx(int key) {
		ensureLive();
		final Connection c = connection.get();
		final String selectSql = sqlTemplates.renderShortTxSelect(key);
		final String updateSql = sqlTemplates.renderShortTxUpdate(key);
		// usingWhen awaits SESSION_CLOSE — doFinally{ close.subscribe() } raced the next begin() on this TCP.
		final Mono<Void> pipeline = Mono.usingWhen(
				c.begin(),
				tx -> drainTx(tx, selectSql).then(execTx(tx, updateSql)).then(tx.commit()),
				TxContext::close,
				(tx, err) -> tx.rollback().then(tx.close()).onErrorComplete(),
				TxContext::close
		);
		// Multi-statement TX: outer budget > single EXEC (lock-wait default 8s + commit slack).
		return GridSqlJmeterAwait.toTimedFuture(pipeline, shortTxTimeout());
	}

	/**
	 * Next PK for the mix. Capacity key spaces use per-JMeter-thread stripes fixed at
	 * {@link #open} ({@code STRIPE_THREADS} / ThreadGroup size) so ramp-up does not
	 * shift bands via live {@code getTotalThreads()} and cause PK lock storms.
	 * Contention spaces ({@link #CONTENTION_KEY_SPACE}) keep a shared random key domain.
	 */
	public int nextKey() {
		if (keySpace <= CONTENTION_KEY_SPACE) {
			return 1 + ThreadLocalRandom.current().nextInt(keySpace);
		}
		final int threadNum = Math.max(0, JMeterContextService.getContext().getThreadNum());
		final int threads = stripeThreads;
		final int stripe = Math.max(1, keySpace / threads);
		final int base = (threadNum % threads) * stripe;
		final int width = Math.max(1, Math.min(stripe, keySpace - base));
		return 1 + base + ThreadLocalRandom.current().nextInt(width);
	}

	private Duration shortTxTimeout() {
		return Duration.ofMillis(opTimeout.toMillis() + LOCK_WAIT_SLACK_MS);
	}

	private static final long LOCK_WAIT_SLACK_MS = 2_000L;

	public void runSetupBlocking() throws Exception {
		// Fresh tables every capacity run — leftover KEY_SPACE=1M upserts otherwise grow forever
		// and turn short-tx / count-join into multi-second scans (Aggregate ~20 TPS vs ~400).
		await(startUpdate(sqlTemplates.renderDropA()));
		await(startUpdate(sqlTemplates.renderDropB()));
		await(startUpdate(sqlTemplates.renderDdlA()));
		await(startUpdate(sqlTemplates.renderDdlB()));
		await(startUpdate(sqlTemplates.renderIndexB()));
		final int rows = Math.min(seedRows, keySpace);
		for (int from = 1; from <= rows; from += SEED_BATCH_SIZE) {
			final int to = Math.min(from + SEED_BATCH_SIZE - 1, rows);
			await(startUpdate(sqlTemplates.renderSeedInsertA(from, to)));
			await(startUpdate(sqlTemplates.renderSeedInsertB(from, to)));
		}
	}

	public void close() {
		CompletableFuture<Void> pending;
		while ((pending = inflight.poll()) != null) {
			pending.cancel(true);
		}
		final Connection c = connection.getAndSet(null);
		if (c != null) {
			try {
				c.close().timeout(opTimeout).toFuture().join();
			} catch (Exception ignored) {
				// harness close best-effort
			}
		}
		try {
			factory.dispose();
		} catch (Exception ignored) {
			// harness dispose best-effort
		}
		unbind();
	}

	private void ensureLive() {
		final Connection c = connection.get();
		if (c == null) {
			throw new IllegalStateException("session closed");
		}
		if (c instanceof RemoteConnection rc && !rc.isOpen()) {
			throw new IllegalStateException("SQL channel closed");
		}
	}

	private static Mono<Void> drainTx(TxContext tx, String sql) {
		return tx.createStatement(sql)
				.execute()
				.concatMap(r -> r.map((Row row, RowMetadata meta) -> Boolean.TRUE))
				.then();
	}

	private static Mono<Void> execTx(TxContext tx, String sql) {
		return tx.createStatement(sql)
				.execute()
				.concatMap(Result::getRowsUpdated)
				.reduce(0L, Long::sum)
				.then();
	}

	private void await(CompletableFuture<Void> future) throws Exception {
		future.get(opTimeout.toMillis(), TimeUnit.MILLISECONDS);
	}
}
