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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.TreeMap;

import org.genfork.grid.sql.SqlBinds;
import org.genfork.grid.sql.SqlRouteClassifier;
import org.genfork.grid.sql.client.ArrayRow;
import org.genfork.grid.sql.client.ConnectionFactory;
import org.genfork.grid.sql.client.DefaultRowMetadata;
import org.genfork.grid.sql.client.RemoteConnection;
import org.genfork.grid.sql.client.RemoteConnectionFactory;
import org.genfork.grid.sql.client.Row;
import org.genfork.grid.sql.client.ServerMeta;
import org.genfork.grid.sql.client.SqlClientRouteUtil;
import org.genfork.grid.sql.client.SqlClientSql;
import org.genfork.grid.sql.client.transport.TransportConnection;
import org.genfork.grid.sql.client.transport.TransportOutcome;

/**
 * Blocking facade over {@link TransportConnection} (CompletionStage / RowPortal only).
 * <p>
 * Optional READ_REPLICA pool: autocommit SELECT/EXPLAIN route via {@link SqlClientRouteUtil}
 * (product parity with reactive {@code RoutingConnection}).
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public final class SyncConnection implements AutoCloseable {
	private final RemoteConnection remote;
	private final TransportConnection transport;
	private final ConnectionFactory factory;
	private final RemoteConnectionFactory readFactory;
	private final ConcurrentMap<String, SqlRouteClassifier.Route> prepareRouteCache;
	private final Duration timeout;
	private final Executor syncExecutor;

	public SyncConnection(RemoteConnection remote, ConnectionFactory factory, Duration timeout, Executor syncExecutor) {
		this(remote, factory, null, timeout, syncExecutor);
	}

	public SyncConnection(
			RemoteConnection remote,
			ConnectionFactory factory,
			RemoteConnectionFactory readFactory,
			Duration timeout,
			Executor syncExecutor
	) {
		this.remote = Objects.requireNonNull(remote, "remote");
		this.transport = remote.transport();
		this.factory = factory;
		this.readFactory = readFactory;
		this.prepareRouteCache = readFactory == null ? null : SqlClientRouteUtil.newPrepareRouteCache();
		this.timeout = timeout == null ? SyncAwait.DEFAULT_TIMEOUT : timeout;
		this.syncExecutor = syncExecutor;
	}

	/**
	 * Accepts a reactive {@link org.genfork.grid.sql.client.Connection} that must be
	 * {@link RemoteConnection}.
	 */
	public SyncConnection(
			org.genfork.grid.sql.client.Connection connection,
			ConnectionFactory factory,
			Duration timeout,
			Executor syncExecutor
	) {
		this(requireRemote(connection), factory, null, timeout, syncExecutor);
	}

	private static RemoteConnection requireRemote(org.genfork.grid.sql.client.Connection connection) {
		if (!(connection instanceof RemoteConnection remoteConnection)) {
			throw new IllegalArgumentException("SyncConnection requires RemoteConnection");
		}
		return remoteConnection;
	}

	public org.genfork.grid.sql.client.Connection reactive() {
		return remote;
	}

	RemoteConnection remote() {
		return remote;
	}

	public Duration timeout() {
		return timeout;
	}

	Executor syncExecutor() {
		return syncExecutor;
	}

	public ServerMeta serverMeta() {
		return transport.serverMeta();
	}

	public boolean isOpen() {
		return transport.isOpen();
	}

	public SyncTxContext begin() {
		if (!transport.beginAllowed()) {
			throw transport.beginNotAllowed();
		}
		final Integer sessionId = SyncAwait.await(transport.openSession(), timeout, null, syncExecutor);
		final TransportOutcome beginOutcome =
				SyncAwait.await(transport.exec(sessionId, "BEGIN", null, 0), timeout, null, syncExecutor);
		final long handle = beginOutcome instanceof TransportOutcome.Dml dml ? dml.affected() : 0L;
		return new SyncTxContext(transport, sessionId, handle, timeout, syncExecutor);
	}

	public SyncStatement statement(String sql) {
		return statement(sql, timeout);
	}

	/**
	 * Autocommit statement with an explicit await timeout (JDBC {@code queryTimeout}).
	 */
	public SyncStatement statement(String sql, Duration execTimeout) {
		final Duration effective = execTimeout == null || execTimeout.isZero() || execTimeout.isNegative()
				? timeout
				: execTimeout;
		if (prepareRouteCache != null) {
			SqlClientRouteUtil.onSql(prepareRouteCache, sql);
		}
		if (readFactory != null
				&& SqlClientRouteUtil.routeFor(prepareRouteCache, sql) == SqlRouteClassifier.Route.READ) {
			return SyncStatement.autocommitRead(readFactory, sql, effective, syncExecutor);
		}
		return new SyncStatement(transport, sql, effective, syncExecutor);
	}

	public List<SyncResult> executeBatch(List<String> sqls) {
		return executeBatch(sqls, timeout);
	}

	public List<SyncResult> executeBatch(List<String> sqls, Duration execTimeout) {
		final Duration effective = execTimeout == null || execTimeout.isZero() || execTimeout.isNegative()
				? timeout
				: execTimeout;
		if (readFactory != null && SqlClientRouteUtil.allRead(prepareRouteCache, sqls)) {
			final org.genfork.grid.sql.client.Connection borrowed =
					SyncAwait.await(readFactory.obtainStage(), effective, null, syncExecutor);
			if (!(borrowed instanceof RemoteConnection readRemote)) {
				throw new IllegalStateException("read obtainStage must return RemoteConnection");
			}
			try {
				final List<TransportOutcome> outcomes = SyncAwait.await(
						readRemote.transport().batchAutocommit(sqls), effective, null, syncExecutor);
				return wrapOutcomes(outcomes, effective, syncExecutor);
			} finally {
				SyncAwait.awaitVoid(readRemote.transport().parkStage(), effective, syncExecutor);
			}
		}
		final List<TransportOutcome> outcomes =
				SyncAwait.await(transport.batchAutocommit(sqls), effective, null, syncExecutor);
		return wrapOutcomes(outcomes, effective, syncExecutor);
	}

	public long executeUpdate(String sql) {
		final TransportOutcome outcome =
				SyncAwait.await(transport.execAutocommit(sql, null, 0), timeout, null, syncExecutor);
		if (outcome instanceof TransportOutcome.Dml dml) {
			return dml.affected();
		}
		return 0L;
	}

	public SyncPreparedHandle prepare(String name, String bodySql) {
		if (prepareRouteCache != null) {
			SqlClientRouteUtil.cachePrepareRoute(prepareRouteCache, name, bodySql);
		}
		SyncAwait.await(
				transport.execAutocommit(SqlClientSql.prepareSql(name, bodySql), null, 0),
				timeout, null, syncExecutor);
		return new SyncPreparedHandle(transport, name, timeout, syncExecutor);
	}

	public void deallocate(String name) {
		if (prepareRouteCache != null && name != null && !name.isBlank()) {
			prepareRouteCache.remove(name.trim());
		}
		SyncAwait.await(
				transport.execAutocommit(SqlClientSql.deallocateSql(name), null, 0),
				timeout, null, syncExecutor);
	}

	public void setSchema(String schema) {
		SyncAwait.await(
				transport.execAutocommit(SqlClientSql.setSchemaSql(schema), null, 0),
				timeout, null, syncExecutor);
	}

	public void setTimezone(String zoneId) {
		SyncAwait.awaitVoid(transport.setTimezone(zoneId), timeout, syncExecutor);
	}

	public void setRemoteDirty(boolean enabled) {
		SyncAwait.await(
				transport.execAutocommit(SqlClientSql.setRemoteDirtySql(enabled), null, 0),
				timeout, null, syncExecutor);
	}

	public void pin(String table, Object key) {
		SyncAwait.await(
				transport.execAutocommit(SqlClientSql.pinSql(table, key, null, null), null, 0),
				timeout, null, syncExecutor);
	}

	public void pin(String table, Object key, long ttlMs) {
		SyncAwait.await(
				transport.execAutocommit(SqlClientSql.pinSql(table, key, ttlMs, null), null, 0),
				timeout, null, syncExecutor);
	}

	public void pin(String table, Object key, long ttlMs, String qos) {
		SyncAwait.await(
				transport.execAutocommit(SqlClientSql.pinSql(table, key, ttlMs, qos), null, 0),
				timeout, null, syncExecutor);
	}

	public void unpin(String table, Object key) {
		SyncAwait.await(
				transport.execAutocommit(SqlClientSql.unpinSql(table, key), null, 0),
				timeout, null, syncExecutor);
	}

	private static final long PARK_DRAIN_BUDGET_MS = 2_000L;
	private static final long PARK_DRAIN_SPIN_MS = 10L;

	/**
	 * Park TCP back to the factory idle pool. Never disposes the factory / EventLoopGroup.
	 */
	@Override
	public void close() {
		if (prepareRouteCache != null) {
			prepareRouteCache.clear();
		}
		drainActiveRequestsBeforePark();
		SyncAwait.awaitVoid(transport.parkStage(), timeout, syncExecutor);
	}

	/**
	 * Brief wait so in-flight requests finish before idle park (avoids silent skip in factory park).
	 */
	private void drainActiveRequestsBeforePark() {
		final long deadlineNanos = System.nanoTime()
				+ java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(PARK_DRAIN_BUDGET_MS);
		while (transport.activeRequests() > 0 && System.nanoTime() < deadlineNanos) {
			try {
				Thread.sleep(PARK_DRAIN_SPIN_MS);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

	static List<SyncResult> wrapOutcomes(
			List<TransportOutcome> outcomes,
			Duration timeout,
			Executor syncExecutor
	) {
		if (outcomes == null || outcomes.isEmpty()) {
			return List.of();
		}
		final List<SyncResult> out = new ArrayList<>(outcomes.size());
		for (TransportOutcome outcome : outcomes) {
			out.add(new SyncResult(outcome, timeout, syncExecutor));
		}
		return out;
	}

	static List<Row> drainPortal(TransportOutcome.ResultSet resultSet, Duration timeout) {
		final DefaultRowMetadata meta = new DefaultRowMetadata(resultSet.columns());
		final List<Row> rows = new ArrayList<>();
		resultSet.portal().request(Long.MAX_VALUE);
		try {
			while (true) {
				final Object[] cells = resultSet.portal().take(timeout);
				if (cells == null) {
					break;
				}
				rows.add(new ArrayRow(cells, meta));
			}
		} catch (TimeoutException ex) {
			throw new SyncAwait.SyncTimeoutException("row portal take timed out", ex);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new SyncAwait.SyncInterruptedException("row portal take interrupted", ex);
		}
		return rows;
	}

	static Object[] denseBinds(TreeMap<Integer, Object> binds) {
		return SqlBinds.dense(binds);
	}
}
