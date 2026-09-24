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

import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import org.genfork.grid.sql.client.RemoteConnection;
import org.genfork.grid.sql.client.ServerMeta;
import org.genfork.grid.sql.client.sync.SyncConnection;
import org.genfork.grid.sql.client.sync.SyncConnectionFactory;
import org.genfork.grid.sql.client.sync.SyncStatement;
import org.genfork.grid.sql.client.sync.SyncTxContext;

/**
 * JDBC {@link Connection} over {@link SyncConnectionFactory} / {@link SyncConnection}.
 * <p>
 * Obtain and reconnect go through Sync* only — never hand-rolled {@code obtainStage}
 * on the JDBC edge. {@link #close()} parks the channel; factory lifecycle stays in Sync*
 * ({@link SyncConnectionFactory#close()} / DataSource retain). Dead-channel reconnect uses
 * {@link SyncConnectionFactory#openOrReplace(SyncConnection)}.
 * <p>
 * Product Sync surface: unwrap {@link SyncConnection} / {@link ServerMeta}; {@link #pin}/
 * {@link #unpin}; timezone via {@link #setClientInfo}; query timeout via Statement.
 * One open TX maps JDBC autoCommit — parallel TX = N JDBC Connections or unwrap Sync.
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
public final class GridConnection implements Connection {
	static final String PRODUCT_NAME = "Jamoa Grid";
	static final String PRODUCT_VERSION = "1.0";
	static final int JDBC_MAJOR = 4;
	static final int JDBC_MINOR = 3;

	private static final String MSG_CLOSED = "Connection is closed";
	private static final String SQLSTATE_CONNECTION_CLOSED = "08003";
	private static final String SQLSTATE_CONNECT_FAILURE = "08001";
	private static final String MSG_RECONNECT_FAILED = "SQL channel reconnect failed";
	/** Client-info key for session timezone (Sync {@code setTimezone}). */
	public static final String CLIENT_INFO_TIMEZONE = "timezone";

	private final SyncConnectionFactory syncFactory;
	/**
	 * When true (Driver connect), {@link #close()} releases one factory retain after park.
	 * DataSource-owned connections leave retain to {@link GridDataSource#close()}.
	 */
	private final boolean releaseFactoryOnClose;
	/** Live SPI transport; replaced on reconnect when the previous channel is dead. */
	private volatile RemoteConnection spi;
	private volatile SyncConnection sync;
	private volatile boolean closed;
	private volatile boolean autoCommit = true;
	private volatile SyncTxContext openTx;
	private volatile String schema;
	private volatile boolean readOnly;
	private final AtomicInteger savepointSeq = new AtomicInteger();

	GridConnection(SyncConnectionFactory syncFactory, SyncConnection sync) {
		this(syncFactory, sync, false);
	}

	GridConnection(SyncConnectionFactory syncFactory, SyncConnection sync, boolean releaseFactoryOnClose) {
		this.syncFactory = Objects.requireNonNull(syncFactory, "syncFactory");
		this.releaseFactoryOnClose = releaseFactoryOnClose;
		Objects.requireNonNull(sync, "sync");
		final org.genfork.grid.sql.client.Connection reactive = sync.reactive();
		if (!(reactive instanceof RemoteConnection remote)) {
			throw new IllegalArgumentException("GridConnection requires RemoteConnection");
		}
		this.spi = remote;
		this.sync = sync;
	}

	RemoteConnection spi() throws SQLException {
		ensureSpiLive();
		return spi;
	}

	Duration timeout() {
		return syncFactory.timeout();
	}

	/**
	 * Sticky HA server meta from the writer channel (promote observability).
	 */
	public ServerMeta serverMeta() throws SQLException {
		ensureOpen();
		ensureSpiLive();
		return sync.serverMeta();
	}

	/**
	 * Product PIN helper (same SQL as reactive / Sync).
	 */
	public void pin(String table, Object key) throws SQLException {
		ensureOpen();
		ensureSpiLive();
		try {
			sync.pin(table, key);
		} catch (RuntimeException e) {
			throw mapSql(e);
		}
	}

	/**
	 * Product PIN with TTL.
	 */
	public void pin(String table, Object key, long ttlMs) throws SQLException {
		ensureOpen();
		ensureSpiLive();
		try {
			sync.pin(table, key, ttlMs);
		} catch (RuntimeException e) {
			throw mapSql(e);
		}
	}

	/**
	 * Product UNPIN helper.
	 */
	public void unpin(String table, Object key) throws SQLException {
		ensureOpen();
		ensureSpiLive();
		try {
			sync.unpin(table, key);
		} catch (RuntimeException e) {
			throw mapSql(e);
		}
	}

	/**
	 * Session timezone via Sync (also {@link #setClientInfo} with {@link #CLIENT_INFO_TIMEZONE}).
	 */
	public void setTimezone(String zoneId) throws SQLException {
		ensureOpen();
		ensureSpiLive();
		try {
			sync.setTimezone(zoneId);
		} catch (RuntimeException e) {
			throw mapSql(e);
		}
	}

	@Override
	public Statement createStatement() throws SQLException {
		ensureOpen();
		return new GridStatement(this);
	}

	@Override
	public PreparedStatement prepareStatement(String sql) throws SQLException {
		ensureOpen();
		return new GridPreparedStatement(this, sql);
	}

	@Override
	public void setAutoCommit(boolean autoCommit) throws SQLException {
		ensureOpen();
		ensureSpiLive();
		if (this.autoCommit == autoCommit) {
			return;
		}
		if (!this.autoCommit && autoCommit) {
			final SyncTxContext tx = openTx;
			openTx = null;
			if (tx != null) {
				try {
					tx.commit();
				} catch (RuntimeException e) {
					throw mapSql(e);
				}
			}
		}
		this.autoCommit = autoCommit;
		if (!autoCommit && openTx == null) {
			try {
				openTx = sync.begin();
			} catch (RuntimeException e) {
				throw mapSql(e);
			}
		}
	}

	@Override
	public boolean getAutoCommit() throws SQLException {
		ensureOpen();
		return autoCommit;
	}

	@Override
	public void commit() throws SQLException {
		ensureOpen();
		ensureSpiLive();
		if (autoCommit) {
			return;
		}
		final SyncTxContext tx = openTx;
		if (tx == null) {
			return;
		}
		try {
			tx.commit();
			openTx = sync.begin();
		} catch (RuntimeException e) {
			throw mapSql(e);
		}
	}

	@Override
	public void rollback() throws SQLException {
		ensureOpen();
		ensureSpiLive();
		if (autoCommit) {
			return;
		}
		final SyncTxContext tx = openTx;
		if (tx == null) {
			return;
		}
		try {
			tx.rollback();
			openTx = sync.begin();
		} catch (RuntimeException e) {
			throw mapSql(e);
		}
	}

	@Override
	public void close() throws SQLException {
		if (closed) {
			return;
		}
		closed = true;
		final SyncTxContext tx = openTx;
		openTx = null;
		if (tx != null) {
			try {
				tx.close();
			} catch (RuntimeException ignored) {
				// best-effort
			}
		}
		final SyncConnection live = sync;
		if (live != null) {
			try {
				live.close();
			} catch (RuntimeException ignored) {
				// best-effort park only
			}
		}
		if (releaseFactoryOnClose) {
			try {
				syncFactory.close();
			} catch (RuntimeException ignored) {
				// best-effort retain release
			}
		}
	}

	SyncConnectionFactory syncFactory() {
		return syncFactory;
	}

	@Override
	public boolean isClosed() {
		return closed;
	}

	@Override
	public DatabaseMetaData getMetaData() throws SQLException {
		ensureOpen();
		return new GridDatabaseMetaData(this);
	}

	@Override
	public void setReadOnly(boolean readOnly) throws SQLException {
		ensureOpen();
		this.readOnly = readOnly;
	}

	@Override
	public boolean isReadOnly() throws SQLException {
		ensureOpen();
		return readOnly;
	}

	@Override
	public void setCatalog(String catalog) {
		/* single catalog */
	}

	@Override
	public String getCatalog() {
		return GridDatabaseMetaData.CATALOG_NAME;
	}

	@Override
	public void setTransactionIsolation(int level) throws SQLException {
		ensureOpen();
		/* best-effort: isolation fixed by engine */
	}

	@Override
	public int getTransactionIsolation() throws SQLException {
		ensureOpen();
		return TRANSACTION_READ_COMMITTED;
	}

	@Override
	public SQLWarning getWarnings() {
		return null;
	}

	@Override
	public void clearWarnings() {
	}

	@Override
	public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
		return createStatement();
	}

	@Override
	public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency)
			throws SQLException {
		return prepareStatement(sql);
	}

	@Override
	public CallableStatement prepareCall(String sql) throws SQLException {
		throw unsupported("prepareCall");
	}

	@Override
	public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency)
			throws SQLException {
		throw unsupported("prepareCall");
	}

	@Override
	public String nativeSQL(String sql) throws SQLException {
		ensureOpen();
		return sql;
	}

	@Override
	public void setHoldability(int holdability) {
	}

	@Override
	public int getHoldability() {
		return ResultSetHoldability.HOLD_CURSORS_OVER_COMMIT;
	}

	@Override
	public Savepoint setSavepoint() throws SQLException {
		return setSavepoint("jdbc_sp_" + savepointSeq.incrementAndGet());
	}

	@Override
	public Savepoint setSavepoint(String name) throws SQLException {
		ensureOpen();
		ensureSpiLive();
		if (autoCommit) {
			throw new SQLException("setSavepoint requires autoCommit=false", "25001");
		}
		final SyncTxContext tx = openTx;
		if (tx == null) {
			throw new SQLException("no transaction in progress", "25P01");
		}
		try {
			final org.genfork.grid.sql.client.Savepoint sp = tx.savepoint(name);
			return new GridSavepoint(sp.name(), savepointSeq.get());
		} catch (RuntimeException e) {
			throw mapSql(e);
		}
	}

	@Override
	public void rollback(Savepoint savepoint) throws SQLException {
		ensureOpen();
		ensureSpiLive();
		if (savepoint == null) {
			throw new SQLException("savepoint required");
		}
		final SyncTxContext tx = openTx;
		if (tx == null) {
			throw new SQLException("no transaction in progress", "25P01");
		}
		final String name = savepoint.getSavepointName();
		try {
			tx.rollbackTo(org.genfork.grid.sql.client.Savepoint.of(name));
		} catch (RuntimeException e) {
			throw mapSql(e);
		}
	}

	@Override
	public void releaseSavepoint(Savepoint savepoint) throws SQLException {
		ensureOpen();
		ensureSpiLive();
		if (savepoint == null) {
			throw new SQLException("savepoint required");
		}
		final SyncTxContext tx = openTx;
		if (tx == null) {
			throw new SQLException("no transaction in progress", "25P01");
		}
		final String name = savepoint.getSavepointName();
		try {
			tx.release(org.genfork.grid.sql.client.Savepoint.of(name));
		} catch (RuntimeException e) {
			throw mapSql(e);
		}
	}

	@Override
	public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability)
			throws SQLException {
		return createStatement();
	}

	@Override
	public PreparedStatement prepareStatement(
			String sql,
			int resultSetType,
			int resultSetConcurrency,
			int resultSetHoldability
	) throws SQLException {
		return prepareStatement(sql);
	}

	@Override
	public CallableStatement prepareCall(
			String sql,
			int resultSetType,
			int resultSetConcurrency,
			int resultSetHoldability
	) throws SQLException {
		throw unsupported("prepareCall");
	}

	@Override
	public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
		return prepareStatement(sql);
	}

	@Override
	public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
		return prepareStatement(sql);
	}

	@Override
	public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
		return prepareStatement(sql);
	}

	@Override
	public Clob createClob() throws SQLException {
		throw unsupported("createClob");
	}

	@Override
	public Blob createBlob() throws SQLException {
		throw unsupported("createBlob");
	}

	@Override
	public NClob createNClob() throws SQLException {
		throw unsupported("createNClob");
	}

	@Override
	public SQLXML createSQLXML() throws SQLException {
		throw unsupported("createSQLXML");
	}

	/**
	 * Channel-lifecycle validity only — no SQL round-trip ({@code SELECT 1} intentionally omitted).
	 */
	@Override
	public boolean isValid(int timeoutSeconds) {
		if (closed) {
			return false;
		}
		try {
			ensureSpiLive();
			return isSpiLive(spi);
		} catch (SQLException e) {
			return false;
		}
	}

	@Override
	public void setClientInfo(String name, String value) throws SQLClientInfoException {
		if (CLIENT_INFO_TIMEZONE.equalsIgnoreCase(name) && value != null && !value.isBlank()) {
			try {
				setTimezone(value);
			} catch (SQLException e) {
				final SQLClientInfoException fail = new SQLClientInfoException();
				fail.initCause(e);
				throw fail;
			}
		}
	}

	@Override
	public void setClientInfo(Properties properties) throws SQLClientInfoException {
		if (properties == null) {
			return;
		}
		final String zone = properties.getProperty(CLIENT_INFO_TIMEZONE);
		if (zone != null) {
			setClientInfo(CLIENT_INFO_TIMEZONE, zone);
		}
	}

	@Override
	public String getClientInfo(String name) {
		return null;
	}

	@Override
	public Properties getClientInfo() {
		return new Properties();
	}

	@Override
	public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
		throw unsupported("createArrayOf");
	}

	@Override
	public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
		throw unsupported("createStruct");
	}

	@Override
	public void setSchema(String schema) throws SQLException {
		ensureOpen();
		ensureSpiLive();
		this.schema = schema;
		if (schema != null && !schema.isBlank()) {
			executeUpdateInternal("SET SCHEMA " + schema);
		}
	}

	@Override
	public String getSchema() throws SQLException {
		ensureOpen();
		return schema;
	}

	@Override
	public void abort(Executor executor) throws SQLException {
		close();
	}

	@Override
	public void setNetworkTimeout(Executor executor, int milliseconds) {
	}

	@Override
	public int getNetworkTimeout() {
		return 0;
	}

	@Override
	public Map<String, Class<?>> getTypeMap() throws SQLException {
		throw unsupported("getTypeMap");
	}

	@Override
	public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
		throw unsupported("setTypeMap");
	}

	@Override
	public <T> T unwrap(Class<T> iface) throws SQLException {
		if (iface.isInstance(this)) {
			return iface.cast(this);
		}
		if (iface.isInstance(sync)) {
			return iface.cast(sync);
		}
		if (iface == ServerMeta.class) {
			return iface.cast(serverMeta());
		}
		if (iface.isInstance(syncFactory)) {
			return iface.cast(syncFactory);
		}
		throw new SQLException("Not a wrapper for " + iface);
	}

	@Override
	public boolean isWrapperFor(Class<?> iface) {
		return iface.isInstance(this)
				|| iface.isInstance(sync)
				|| iface == ServerMeta.class
				|| iface.isInstance(syncFactory);
	}

	SyncStatement syncStatement(String sql) throws SQLException {
		return syncStatement(sql, null);
	}

	SyncStatement syncStatement(String sql, Duration queryTimeout) throws SQLException {
		ensureOpen();
		ensureSpiLive();
		if (autoCommit || openTx == null) {
			return sync.statement(sql, effectiveTimeout(queryTimeout));
		}
		return openTx.statement(sql, effectiveTimeout(queryTimeout));
	}

	List<org.genfork.grid.sql.client.sync.SyncResult> syncBatch(List<String> sqls) throws SQLException {
		return syncBatch(sqls, null);
	}

	List<org.genfork.grid.sql.client.sync.SyncResult> syncBatch(List<String> sqls, Duration queryTimeout)
			throws SQLException {
		ensureOpen();
		ensureSpiLive();
		try {
			if (autoCommit || openTx == null) {
				return sync.executeBatch(sqls, effectiveTimeout(queryTimeout));
			}
			return openTx.executeBatch(sqls, effectiveTimeout(queryTimeout));
		} catch (RuntimeException e) {
			throw mapSql(e);
		}
	}

	private Duration effectiveTimeout(Duration queryTimeout) {
		if (queryTimeout == null || queryTimeout.isZero() || queryTimeout.isNegative()) {
			return syncFactory.timeout();
		}
		return queryTimeout;
	}

	void ensureOpen() throws SQLException {
		if (closed) {
			throw new SQLException(MSG_CLOSED, SQLSTATE_CONNECTION_CLOSED);
		}
	}

	/**
	 * Reconnect SPI transport when the TCP channel is dead but JDBC {@link #close()} was not called.
	 * Uses {@link SyncConnectionFactory#openOrReplace(SyncConnection)} only.
	 */
	void ensureSpiLive() throws SQLException {
		ensureOpen();
		if (isSpiLive(spi)) {
			return;
		}
		synchronized (this) {
			ensureOpen();
			if (isSpiLive(spi)) {
				return;
			}
			openTx = null;
			try {
				final SyncConnection dead = sync;
				final SyncConnection next = syncFactory.openOrReplace(dead);
				final org.genfork.grid.sql.client.Connection reactive = next.reactive();
				if (!(reactive instanceof RemoteConnection remote)) {
					throw new SQLException(MSG_RECONNECT_FAILED, SQLSTATE_CONNECT_FAILURE);
				}
				spi = remote;
				sync = next;
				if (!autoCommit) {
					openTx = sync.begin();
				}
			} catch (RuntimeException e) {
				throw new SQLException(MSG_RECONNECT_FAILED + ": " + e.getMessage(),
						SQLSTATE_CONNECT_FAILURE, e);
			}
		}
	}

	private static boolean isSpiLive(RemoteConnection connection) {
		return connection != null && connection.isOpen();
	}

	int executeUpdateInternal(String sql) throws SQLException {
        try (GridStatement st = new GridStatement(this, true)) {
            return st.executeUpdate(sql);
        }
	}

	ResultSet executeQueryInternal(String sql) throws SQLException {
		final GridStatement st = new GridStatement(this, true);
		return st.executeQuery(sql);
	}

	private static SQLException mapSql(RuntimeException e) {
		return JdbcSync.toSqlException(e);
	}

	private static SQLFeatureNotSupportedException unsupported(String method) {
		return new SQLFeatureNotSupportedException(method + " not supported");
	}

	/** Holdability constants without importing ResultSet into every call site. */
	private static final class ResultSetHoldability {
		static final int HOLD_CURSORS_OVER_COMMIT = ResultSet.HOLD_CURSORS_OVER_COMMIT;
	}
}
