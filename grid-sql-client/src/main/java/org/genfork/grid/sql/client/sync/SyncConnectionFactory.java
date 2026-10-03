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
package org.genfork.grid.sql.client.sync;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import org.genfork.grid.sql.client.Connection;
import org.genfork.grid.sql.client.ConnectionOptions;
import org.genfork.grid.sql.client.GridSqlUri;
import org.genfork.grid.sql.client.HostEndpoint;
import org.genfork.grid.sql.client.ReadEndpointRingUtil;
import org.genfork.grid.sql.client.ReadPreference;
import org.genfork.grid.sql.client.RemoteConnection;
import org.genfork.grid.sql.client.RemoteConnectionFactory;
import org.genfork.grid.sql.client.ServerMeta;
import org.genfork.grid.sql.client.SessionRole;

/**
 * Blocking factory over writer (+ optional READ_REPLICA) {@link RemoteConnectionFactory}.
 * <p>
 * Sole sync edge for obtain/park: uses {@link RemoteConnectionFactory#obtainStage()} —
 * never {@code Mono.toFuture}. Canonical entry: {@link #fromUrl(String)} (product URL parity /
 * JDBC TCP floor). Harness / CLI exclusive entry: {@link #exclusiveFromUrl(String)}.
 * {@link #shared} interns one factory per target (Driver and DataSource share).
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public final class SyncConnectionFactory implements AutoCloseable {
	/**
	 * JDBC / tooling floor on TCP channels when opening a shared or local factory.
	 */
	public static final int MIN_TCP_CHANNELS = 10;

	private static final ConcurrentHashMap<String, SharedEntry> SHARED = new ConcurrentHashMap<>();
	private static final char KEY_SEP = '\u0001';

	private final RemoteConnectionFactory writerFactory;
	/** Null when URL has no {@code readEndpoints} / PRIMARY-only. */
	private final RemoteConnectionFactory readFactory;
	private final Duration timeout;
	private final Executor syncExecutor;
	private final boolean ownsFactory;
	/** Non-null when this instance is registered in {@link #SHARED}. */
	private final String shareKey;

	public SyncConnectionFactory(RemoteConnectionFactory factory) {
		this(factory, null, resolveTimeout(factory), null, false, null);
	}

	public SyncConnectionFactory(RemoteConnectionFactory factory, Duration timeout) {
		this(factory, null, timeout, null, false, null);
	}

	public SyncConnectionFactory(RemoteConnectionFactory factory, Duration timeout, Executor syncExecutor) {
		this(factory, null, timeout, syncExecutor, false, null);
	}

	private SyncConnectionFactory(
			RemoteConnectionFactory writerFactory,
			RemoteConnectionFactory readFactory,
			Duration timeout,
			Executor syncExecutor,
			boolean ownsFactory,
			String shareKey
	) {
		this.writerFactory = Objects.requireNonNull(writerFactory, "writerFactory");
		this.readFactory = readFactory;
		this.timeout = timeout == null || timeout.isNegative() || timeout.isZero()
				? SyncAwait.DEFAULT_TIMEOUT
				: timeout;
		this.syncExecutor = syncExecutor;
		this.ownsFactory = ownsFactory;
		this.shareKey = shareKey;
	}

	/**
	 * Product-path mirror of {@code ConnectionFactory.fromUrl}: routing when
	 * {@code readEndpoints} present, else writer-only. Applies JDBC TCP floor.
	 */
	public static SyncConnectionFactory fromUrl(String gridUrl) {
		final GridSqlUri parsed = GridSqlUri.parse(gridUrl);
		return shared(
				parsed.endpoints(),
				parsed.user(),
				parsed.password(),
				parsed.options(),
				parsed.schema());
	}

	/**
	 * Exclusive (non-interned) factory from product URL — preserves URL options, does
	 * <strong>not</strong> apply JDBC {@link #MIN_TCP_CHANNELS} floor. Owns dispose on
	 * {@link #close()}. Prefer for CLI / Jepsen / tools; JDBC keeps {@link #fromUrl}.
	 */
	public static SyncConnectionFactory exclusiveFromUrl(String gridUrl) {
		return exclusiveFromUrl(gridUrl, null);
	}

	public static SyncConnectionFactory exclusiveFromUrl(String gridUrl, Duration timeout) {
		final GridSqlUri parsed = GridSqlUri.parse(gridUrl);
		final ConnectionOptions options = parsed.options();
		final RemoteConnectionFactory writer = new RemoteConnectionFactory(
				parsed.endpoints(),
				parsed.user(),
				parsed.password(),
				options,
				parsed.schema());
		final RemoteConnectionFactory read =
				createReadFactoryOrNull(parsed.endpoints(), options, parsed.user(), parsed.password(), parsed.schema());
		final Duration effective = timeout == null ? resolveTimeout(writer) : timeout;
		return new SyncConnectionFactory(writer, read, effective, null, true, null);
	}

	/**
	 * Exclusive factory that owns dispose of the given writer (and optional read) on {@link #close()}.
	 */
	public static SyncConnectionFactory exclusiveOf(RemoteConnectionFactory writer) {
		return exclusiveOf(writer, null, null);
	}

	public static SyncConnectionFactory exclusiveOf(
			RemoteConnectionFactory writer,
			RemoteConnectionFactory readFactory,
			Duration timeout
	) {
		Objects.requireNonNull(writer, "writer");
		final Duration effective = timeout == null ? resolveTimeout(writer) : timeout;
		return new SyncConnectionFactory(writer, readFactory, effective, null, true, null);
	}

	public static SyncConnectionFactory openLocal(
			String host,
			int port,
			String user,
			String password
	) {
		final ConnectionOptions options = ConnectionOptions.builder()
				.minConnections(MIN_TCP_CHANNELS)
				.maxConnections(MIN_TCP_CHANNELS)
				.warmup(true)
				.build();
		return shared(
				List.of(new HostEndpoint(host, port)),
				user,
				password,
				options,
				ConnectionOptions.DEFAULT_SCHEMA);
	}

	/**
	 * Exclusive factory (not interned). Prefer {@link #shared} for JDBC Driver/DataSource.
	 */
	public static SyncConnectionFactory openLocal(
			List<HostEndpoint> endpoints,
			String user,
			String password,
			ConnectionOptions options,
			String defaultSchema
	) {
		final ConnectionOptions floored = applyTcpFloor(options);
		final RemoteConnectionFactory writer =
				new RemoteConnectionFactory(endpoints, user, password, floored, defaultSchema);
		final RemoteConnectionFactory read =
				createReadFactoryOrNull(endpoints, floored, user, password, defaultSchema);
		return new SyncConnectionFactory(writer, read, resolveTimeout(writer), null, true, null);
	}

	/**
	 * Interned factory for one target (endpoints + credentials + floored options + schema + read ring).
	 * Driver and DataSource share the same instance; TCP multiplex stays in Sync* only.
	 */
	public static SyncConnectionFactory shared(
			List<HostEndpoint> endpoints,
			String user,
			String password,
			ConnectionOptions options,
			String defaultSchema
	) {
		Objects.requireNonNull(endpoints, "endpoints");
		final ConnectionOptions floored = applyTcpFloor(options);
		final String schema = defaultSchema == null || defaultSchema.isBlank()
				? ConnectionOptions.DEFAULT_SCHEMA
				: defaultSchema.trim();
		final String key = shareKey(endpoints, user, password, floored, schema);
		final SharedEntry entry = SHARED.computeIfAbsent(key, ignored -> {
			final RemoteConnectionFactory writer =
					new RemoteConnectionFactory(endpoints, user, password, floored, schema);
			final RemoteConnectionFactory read =
					createReadFactoryOrNull(endpoints, floored, user, password, schema);
			final SyncConnectionFactory created = new SyncConnectionFactory(
					writer, read, resolveTimeout(writer), null, true, key);
			return new SharedEntry(created);
		});
		return entry.factory;
	}

	private static RemoteConnectionFactory createReadFactoryOrNull(
			List<HostEndpoint> authority,
			ConnectionOptions floored,
			String user,
			String password,
			String schema
	) {
		if (floored.readEndpoints().isEmpty()) {
			return null;
		}
		if (floored.readPreference() != ReadPreference.REPLICA) {
			return null;
		}
		final List<HostEndpoint> readEps =
				ReadEndpointRingUtil.mergeReadRing(floored.readEndpoints(), authority);
		final ConnectionOptions readOpts = ReadEndpointRingUtil.buildReadReplicaOptions(
				floored, readEps, MIN_TCP_CHANNELS);
		return new RemoteConnectionFactory(
				readEps, user, password, readOpts, schema, SessionRole.READ_REPLICA);
	}

	/**
	 * Mark this shared factory as retained by a lifecycle owner (DataSource or Driver connect).
	 * Pairs with {@link #close()}. Shared {@link #close()} without a prior retain is a no-op
	 * (retainers clamp at zero) — use {@link #openLocal} for exclusive teardown, or retain first.
	 */
	public void retain() {
		if (shareKey == null) {
			return;
		}
		final SharedEntry entry = SHARED.get(shareKey);
		if (entry != null && entry.factory == this) {
			entry.retainers.incrementAndGet();
		}
	}

	/**
	 * Clamp {@code minConnections}/{@code maxConnections} to at least {@link #MIN_TCP_CHANNELS}.
	 */
	public static ConnectionOptions applyTcpFloor(ConnectionOptions base) {
		final ConnectionOptions src = base == null ? ConnectionOptions.defaults() : base;
		final int minTcp = Math.max(MIN_TCP_CHANNELS, src.minConnections());
		final int maxTcp = Math.max(minTcp, Math.max(MIN_TCP_CHANNELS, src.maxConnections()));
		return ConnectionOptions.builder()
				.minConnections(minTcp)
				.maxConnections(maxTcp)
				.maxTxContexts(src.maxTxContexts())
				.warmup(true)
				.execTimeout(src.execTimeout())
				.connectTimeout(src.connectTimeout())
				.readTimeout(src.readTimeout())
				.writeTimeout(src.writeTimeout())
				.maxRetries(src.maxRetries())
				.retryDelay(src.retryDelay())
				.retryMode(src.retryMode())
				.timezone(src.timezone())
				.readPreference(src.readPreference())
				.staleReadPolicy(src.staleReadPolicy())
				.maxReadConnections(Math.max(maxTcp, src.maxReadConnections()))
				.fetchWindow(src.fetchWindow())
				.readEndpoints(src.readEndpoints())
				.build();
	}

	/**
	 * Blocking obtain of a multiplexed TCP {@link SyncConnection} (writer channel + optional read pool).
	 */
	public SyncConnection open() {
		final Connection connection =
				SyncAwait.await(writerFactory.obtainStage(), timeout, null, syncExecutor);
		return wrapRemote(connection);
	}

	/**
	 * Park/drop a dead channel then obtain a live one (JDBC reconnect).
	 */
	public SyncConnection openOrReplace(SyncConnection dead) {
		if (dead != null) {
			try {
				dead.close();
			} catch (RuntimeException ignored) {
				// best-effort park
			}
		}
		return open();
	}

	/**
	 * Mid-op orchid / region fence: destroy live channels and AUTH-rediscover writer
	 * ({@link RemoteConnectionFactory#rediscoverWriterStage()}).
	 * <p>
	 * Callers that need one transparent retry should use
	 * {@link SyncSession#callWithWriterRediscover} (holds the rediscovered channel).
	 */
	public SyncConnection rediscoverWriter() {
		final Connection connection =
				SyncAwait.await(writerFactory.rediscoverWriterStage(), timeout, null, syncExecutor);
		return wrapRemote(connection);
	}

	public Duration timeout() {
		return timeout;
	}

	public Executor syncExecutor() {
		return syncExecutor == null ? SyncExecutors.executor() : syncExecutor;
	}

	public ServerMeta lastServerMeta() {
		return writerFactory.lastServerMeta();
	}

	public boolean writerEligible() {
		return writerFactory.writerEligible();
	}

	public String promoteHint() {
		return writerFactory.promoteHint();
	}

	/** Optional READ_REPLICA factory when URL routing is enabled. */
	public RemoteConnectionFactory readFactory() {
		return readFactory;
	}

	/**
	 * Multiplex diagnostics (tests). Not a product JDBC API.
	 */
	public int minConnections() {
		return writerFactory.minConnections();
	}

	/**
	 * Multiplex diagnostics (tests). Not a product JDBC API.
	 */
	public int maxConnections() {
		return writerFactory.maxConnections();
	}

	/**
	 * Multiplex diagnostics (tests). Not a product JDBC API.
	 */
	public int activeChannels() {
		return writerFactory.activeChannels();
	}

	/**
	 * Multiplex diagnostics (tests). Not a product JDBC API.
	 */
	public int maxTxContexts() {
		return writerFactory.maxTxContexts();
	}

	/**
	 * AUTH user configured for this factory (JDBC {@code DatabaseMetaData#getUserName}).
	 */
	public String user() {
		return writerFactory.user();
	}

	/**
	 * Default schema from the product URL path (JDBC {@code Connection#getSchema} seed).
	 */
	public String defaultSchema() {
		return writerFactory.defaultSchema();
	}

	RemoteConnectionFactory remoteFactory() {
		return writerFactory;
	}

	private SyncConnection wrapRemote(Connection connection) {
		if (!(connection instanceof RemoteConnection remote)) {
			throw new IllegalStateException("factory stage must return RemoteConnection");
		}
		return new SyncConnection(remote, writerFactory, readFactory, timeout, syncExecutor);
	}

	@Override
	public void close() {
		if (shareKey != null) {
			SHARED.computeIfPresent(shareKey, (ignored, entry) -> {
				if (entry.factory != this) {
					return entry;
				}
				final int left = entry.retainers.decrementAndGet();
				if (left > 0) {
					return entry;
				}
				if (left < 0) {
					entry.retainers.set(0);
					return entry;
				}
				writerFactory.dispose();
				if (readFactory != null) {
					readFactory.dispose();
				}
				return null;
			});
			return;
		}
		if (ownsFactory) {
			writerFactory.dispose();
			if (readFactory != null) {
				readFactory.dispose();
			}
		}
	}

	private static String shareKey(
			List<HostEndpoint> endpoints,
			String user,
			String password,
			ConnectionOptions options,
			String schema
	) {
		final StringBuilder sb = new StringBuilder(160);
		for (HostEndpoint ep : endpoints) {
			sb.append(ep.host().toLowerCase(Locale.ROOT)).append(':').append(ep.port()).append(',');
		}
		sb.append(KEY_SEP).append(nullToEmpty(user));
		sb.append(KEY_SEP).append(nullToEmpty(password));
		sb.append(KEY_SEP).append(schema.toLowerCase(Locale.ROOT));
		sb.append(KEY_SEP).append(options.minConnections());
		sb.append(KEY_SEP).append(options.maxConnections());
		sb.append(KEY_SEP).append(options.maxTxContexts());
		sb.append(KEY_SEP).append(options.readPreference());
		sb.append(KEY_SEP).append(options.timezone());
		sb.append(KEY_SEP).append(options.maxReadConnections());
		sb.append(KEY_SEP).append(options.fetchWindow());
		sb.append(KEY_SEP).append(options.maxRetries());
		sb.append(KEY_SEP).append(options.retryDelay());
		sb.append(KEY_SEP).append(options.retryMode());
		for (HostEndpoint ep : options.readEndpoints()) {
			sb.append(KEY_SEP).append(ep.host().toLowerCase(Locale.ROOT)).append(':').append(ep.port());
		}
		return sb.toString();
	}

	private static String nullToEmpty(String s) {
		return s == null ? "" : s;
	}

	private static Duration resolveTimeout(RemoteConnectionFactory factory) {
		if (factory == null) {
			return SyncAwait.DEFAULT_TIMEOUT;
		}
		final Duration exec = factory.execTimeout();
		if (exec == null || exec.isZero() || exec.isNegative()) {
			return SyncAwait.DEFAULT_TIMEOUT;
		}
		return exec;
	}

	private static final class SharedEntry {
		private final SyncConnectionFactory factory;
		/** Lifecycle owners: DataSource retain + each Driver-opened Connection. */
		private final AtomicInteger retainers = new AtomicInteger(0);

		private SharedEntry(SyncConnectionFactory factory) {
			this.factory = factory;
		}
	}
}
