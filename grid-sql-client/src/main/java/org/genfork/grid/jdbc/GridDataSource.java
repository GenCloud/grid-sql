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
package org.genfork.grid.jdbc;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.genfork.grid.sql.client.GridSqlUri;
import org.genfork.grid.sql.client.sync.SyncConnection;
import org.genfork.grid.sql.client.sync.SyncConnectionFactory;

/**
 * Multiplex JDBC {@link DataSource} over shared {@link SyncConnectionFactory}.
 * <p>
 * Canon for jOOQ / Spring {@code DataSourceConnectionProvider}: at least
 * {@link #MIN_TCP_CHANNELS} TCP channels, each multiplexing up to {@code maxTxContexts}
 * logical sessions. Obtains channels via {@link SyncConnectionFactory#open()} (sync
 * CompletionStage path). Not a Hikari-style N-socket-per-TX pool.
 * <p>
 * TCP caps live only in Sync and Remote factories; this class does not keep a JDBC connection registry.
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
public final class GridDataSource implements DataSource, AutoCloseable {
	/**
	 * Floor on TCP channels opened by this DataSource (named product floor for jOOQ wiring).
	 */
	public static final int MIN_TCP_CHANNELS = SyncConnectionFactory.MIN_TCP_CHANNELS;

	private static final String ERR_NULL_URL = "jdbcUrl";
	private static final String ERR_CLOSED = "GridDataSource is closed";
	private static final String ERR_BAD_URL = "Expected jdbc:grid:// URL, got: ";
	private static final String PROP_USER = "user";
	private static final String PROP_PASSWORD = "password";

	private final SyncConnectionFactory syncFactory;
	private final AtomicBoolean closed = new AtomicBoolean();
	private PrintWriter logWriter;
	private int loginTimeoutSeconds;

	/**
	 * Build from a {@code jdbc:grid://} URL. Ensures min/maxConnections are at least
	 * {@link #MIN_TCP_CHANNELS}; warms the TCP pool on construction.
	 */
	public GridDataSource(String jdbcUrl) throws SQLException {
		this(jdbcUrl, null);
	}

	/**
	 * Build from URL + optional user/password properties ({@code user}/{@code password}).
	 */
	public GridDataSource(String jdbcUrl, Properties info) throws SQLException {
		Objects.requireNonNull(jdbcUrl, ERR_NULL_URL);
		GridHikariBridgeGuard.rejectIfHikariOnStack();
		if (!GridJdbcUrls.accepts(jdbcUrl)) {
			throw new SQLException(ERR_BAD_URL + jdbcUrl);
		}
		final String gridUrl = GridJdbcUrls.toGridUrl(jdbcUrl);
		final GridSqlUri parsed = GridSqlUri.parse(gridUrl);
		String user = parsed.user();
		String password = parsed.password();
		if (info != null) {
			if (info.getProperty(PROP_USER) != null) {
				user = info.getProperty(PROP_USER);
			}
			if (info.getProperty(PROP_PASSWORD) != null) {
				password = info.getProperty(PROP_PASSWORD);
			}
		}
		this.syncFactory = SyncConnectionFactory.shared(
				parsed.endpoints(),
				user,
				password,
				parsed.options(),
				parsed.schema());
		this.syncFactory.retain();
		try {
			final SyncConnection warmed = syncFactory.open();
			warmed.close();
		} catch (RuntimeException e) {
			syncFactory.close();
			throw JdbcSync.toSqlException(e);
		}
	}

	/**
	 * Canonical product-URL construction ({@code jdbc:grid://} → Sync fromUrl semantics).
	 */
	public static GridDataSource fromUrl(String jdbcUrl) throws SQLException {
		return new GridDataSource(jdbcUrl);
	}

	/**
	 * Shared sync factory (tests / diagnostics).
	 */
	public SyncConnectionFactory syncFactory() {
		return syncFactory;
	}

	/**
	 * Configured TCP channel floor (at least {@link #MIN_TCP_CHANNELS}).
	 */
	public int tcpChannelFloor() {
		return syncFactory.minConnections();
	}

	@Override
	public Connection getConnection() throws SQLException {
		ensureOpen();
		GridHikariBridgeGuard.rejectIfHikari(this);
		try {
			final SyncConnection sync = syncFactory.open();
			return new GridConnection(syncFactory, sync);
		} catch (RuntimeException e) {
			throw JdbcSync.toSqlException(e);
		}
	}

	@Override
	public Connection getConnection(String username, String password) throws SQLException {
		return getConnection();
	}

	@Override
	public PrintWriter getLogWriter() {
		return logWriter;
	}

	@Override
	public void setLogWriter(PrintWriter out) {
		this.logWriter = out;
	}

	@Override
	public void setLoginTimeout(int seconds) {
		this.loginTimeoutSeconds = Math.max(0, seconds);
	}

	@Override
	public int getLoginTimeout() {
		return loginTimeoutSeconds;
	}

	@Override
	public Logger getParentLogger() throws SQLFeatureNotSupportedException {
		throw new SQLFeatureNotSupportedException("getParentLogger");
	}

	@Override
	public <T> T unwrap(Class<T> iface) throws SQLException {
		if (iface.isInstance(this)) {
			return iface.cast(this);
		}
		throw new SQLException("Not a wrapper for " + iface);
	}

	@Override
	public boolean isWrapperFor(Class<?> iface) {
		return iface.isInstance(this);
	}

	@Override
	public void close() {
		if (closed.compareAndSet(false, true)) {
			syncFactory.close();
		}
	}

	private void ensureOpen() throws SQLException {
		if (closed.get()) {
			throw new SQLException(ERR_CLOSED);
		}
	}
}