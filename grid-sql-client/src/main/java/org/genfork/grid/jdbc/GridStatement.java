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

import org.genfork.grid.sql.SqlScriptStatements;
import org.genfork.grid.sql.client.Row;
import org.genfork.grid.sql.client.RowMetadata;
import org.genfork.grid.sql.client.sync.SyncResult;
import org.genfork.grid.sql.client.sync.SyncStatement;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * JDBC {@link Statement} over Sync* / transport CompletionStage.
 * <p>
 * When {@link #setFetchSize(int)} is {@code > 0}, applies fetchWindow before execute so wire
 * FETCH is demand-driven via {@code RowPortal}; rows are still materialized into
 * {@link GridResultSet} for scrollable DBeaver navigation.
 * <p>
 * Multi-statement scripts (semicolon-separated) are ANTLR-split and sent as BATCH_EXEC
 * when more than one executable is present.
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
public class GridStatement implements Statement {
	private static final String MSG_CLOSED = "Statement is closed";
	private static final String SQLSTATE_GENERAL = "HY000";
	static final int NO_UPDATE_COUNT = -1;
	private static final int BATCH_SUCCESS_NO_INFO = Statement.SUCCESS_NO_INFO;

	protected final GridConnection connection;
	private volatile boolean closed;
	private volatile GridResultSet currentResult;
	private volatile long updateCount = NO_UPDATE_COUNT;
	private volatile int maxRows;
	private volatile int queryTimeoutSeconds;
	private volatile int fetchSize;
	private volatile Runnable cancelHook;
	private volatile SyncResult activeSyncResult;
	private final boolean skipEditResolve;
	private final List<String> batchSql = new ArrayList<>();
	/** Remaining sync results after the current one (BATCH / multi-statement). */
	private final List<SyncResult> pendingSyncResults = new ArrayList<>();
	private String lastSqlOrNull;

	GridStatement(GridConnection connection) {
		this(connection, false);
	}

	GridStatement(GridConnection connection, boolean skipEditResolve) {
		this.connection = connection;
		this.skipEditResolve = skipEditResolve;
	}

	@Override
	public ResultSet executeQuery(String sql) throws SQLException {
		ensureOpen();
		final ExecOutcome outcome = runSql(sql);
		if (outcome.resultSet() == null) {
			throw new SQLException("SQL did not produce a result set", SQLSTATE_GENERAL);
		}
		return outcome.resultSet();
	}

	@Override
	public int executeUpdate(String sql) throws SQLException {
		ensureOpen();
		final ExecOutcome outcome = runSql(sql);
		if (outcome.resultSet() != null) {
			throw new SQLException(
					"SQL produced a result set (use executeQuery for RETURNING / SELECT)",
					SQLSTATE_GENERAL);
		}
		return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, outcome.updateCount()));
	}

	@Override
	public boolean execute(String sql) throws SQLException {
		ensureOpen();
		final ExecOutcome outcome = runSql(sql);
		return outcome.resultSet() != null;
	}

	/**
	 * ANTLR script split → single EXEC or BATCH_EXEC; applies queryTimeout + cancel hook.
	 */
	private ExecOutcome runSql(String sql) throws SQLException {
		final List<String> parts;
		try {
			parts = SqlScriptStatements.splitExecutables(sql);
		} catch (IllegalArgumentException ex) {
			throw new SQLException(ex.getMessage(), SQLSTATE_GENERAL, ex);
		}
		if (parts.isEmpty()) {
			throw new SQLException("SQL must not be blank", SQLSTATE_GENERAL);
		}
		if (parts.size() == 1) {
			return run(connection.syncStatement(parts.getFirst(), queryTimeoutDuration()), parts.getFirst());
		}
		return runBatchParts(parts);
	}

	private ExecOutcome runBatchParts(List<String> parts) throws SQLException {
		clearCurrent();
		cancelHook = null;
		activeSyncResult = null;
		pendingSyncResults.clear();
		lastSqlOrNull = parts.getFirst();
		final List<SyncResult> results = connection.syncBatch(parts, queryTimeoutDuration());
		if (results.isEmpty()) {
			updateCount = 0L;
			return new ExecOutcome(null, 0L);
		}
		for (int i = 1; i < results.size(); i++) {
			pendingSyncResults.add(results.get(i));
		}
		return applySyncResult(results.getFirst(), parts.getFirst());
	}

	private Duration queryTimeoutDuration() {
		if (queryTimeoutSeconds <= 0) {
			return null;
		}
		return Duration.ofSeconds(queryTimeoutSeconds);
	}

	ExecOutcome run(SyncStatement statement, String sqlOrNull) throws SQLException {
		clearCurrent();
		cancelHook = null;
		activeSyncResult = null;
		pendingSyncResults.clear();
		lastSqlOrNull = sqlOrNull;
		applyFetchWindow(statement);
		statement.cancelSlot(hook -> cancelHook = hook);
		final List<SyncResult> results;
		try {
			results = statement.execute();
		} catch (RuntimeException e) {
			throw JdbcSync.toSqlException(e);
		} finally {
			cancelHook = null;
		}
		if (results.isEmpty()) {
			updateCount = 0L;
			return new ExecOutcome(null, 0L);
		}
		for (int i = 1; i < results.size(); i++) {
			pendingSyncResults.add(results.get(i));
		}
		return applySyncResult(results.getFirst(), sqlOrNull);
	}

	private ExecOutcome applySyncResult(SyncResult first, String sqlOrNull) throws SQLException {
		activeSyncResult = first;
		if (first.isResultSet()) {
			final RowMetadata meta = first.rowMetadata();
			final List<Row> rows;
			try {
				rows = first.rows();
			} catch (RuntimeException e) {
				throw JdbcSync.toSqlException(e);
			}

			final List<Object[]> cells = new ArrayList<>(rows.size());
			for (Row row : rows) {
                assert meta != null;
                final int n = meta.getColumnCount();
				final Object[] values = new Object[n];
				for (int i = 0; i < n; i++) {
					values[i] = row.get(i);
				}
				cells.add(values);
			}

			List<Object[]> limited = cells;
			if (maxRows > 0 && cells.size() > maxRows) {
				limited = new ArrayList<>(cells.subList(0, maxRows));
			}

			GridTableEditContext edit = null;
			GridJdbcCatalogSupport.GridColumnHints hints = null;
			if (sqlOrNull != null && !skipEditResolve) {
				edit = GridJdbcCatalogSupport.resolveEditContext(connection, sqlOrNull);
				if (edit != null) {
					int pkIdx = -1;
					for (int i = 0; i < Objects.requireNonNull(meta).getColumnCount(); i++) {
						if (meta.getColumnNames().get(i).equalsIgnoreCase(edit.pkColumn())) {
							pkIdx = i;
							break;
						}
					}
					edit = new GridTableEditContext(edit.schema(), edit.table(), edit.pkColumn(), pkIdx);
					hints = GridJdbcCatalogSupport.loadColumnHints(connection, edit.schema(), edit.table());
				}
			}
			final GridResultSet rs = edit != null
					? new GridResultSet(this, meta, limited, edit, hints)
					: new GridResultSet(this, meta, limited);
			currentResult = rs;
			updateCount = NO_UPDATE_COUNT;
			return new ExecOutcome(rs, NO_UPDATE_COUNT);
		}

		final long count = first.rowsUpdated();
		updateCount = count;
		return new ExecOutcome(null, count);
	}

	private void clearCurrent() {
		if (currentResult != null) {
			currentResult.close();
			currentResult = null;
		}
		cancelHook = null;
		activeSyncResult = null;
		pendingSyncResults.clear();
		updateCount = NO_UPDATE_COUNT;
	}

	@Override
	public void close() throws SQLException {
		if (closed) {
			return;
		}
		closed = true;
		clearCurrent();
	}

	@Override
	public int getMaxFieldSize() {
		return 0;
	}

	@Override
	public void setMaxFieldSize(int max) {
	}

	@Override
	public int getMaxRows() {
		return maxRows;
	}

	@Override
	public void setMaxRows(int max) {
		this.maxRows = Math.max(0, max);
	}

	@Override
	public void setEscapeProcessing(boolean enable) {
	}

	@Override
	public int getQueryTimeout() {
		return queryTimeoutSeconds;
	}

	@Override
	public void setQueryTimeout(int seconds) {
		this.queryTimeoutSeconds = Math.max(0, seconds);
	}

	@Override
	public void cancel() throws SQLException {
		final Runnable hook = cancelHook;
		if (hook != null) {
			hook.run();
		}
		final SyncResult syncResult = activeSyncResult;
		if (syncResult != null) {
			syncResult.cancel();
		}
		if (currentResult != null) {
			currentResult.close();
			currentResult = null;
		}
	}

	@Override
	public SQLWarning getWarnings() {
		return null;
	}

	@Override
	public void clearWarnings() {
	}

	@Override
	public void setCursorName(String name) throws SQLException {
		throw new SQLFeatureNotSupportedException("setCursorName");
	}

	@Override
	public ResultSet getResultSet() {
		return currentResult;
	}

	@Override
	public int getUpdateCount() {
		return (int) Math.min(Integer.MAX_VALUE, updateCount);
	}

	@Override
	public boolean getMoreResults() throws SQLException {
		ensureOpen();
		if (currentResult != null) {
			currentResult.close();
			currentResult = null;
		}
		activeSyncResult = null;
		updateCount = NO_UPDATE_COUNT;
		if (pendingSyncResults.isEmpty()) {
			return false;
		}
		final SyncResult next = pendingSyncResults.removeFirst();
		final ExecOutcome outcome = applySyncResult(next, lastSqlOrNull);
		return outcome.resultSet() != null;
	}

	@Override
	public void setFetchDirection(int direction) {
	}

	@Override
	public int getFetchDirection() {
		return ResultSet.FETCH_FORWARD;
	}

	@Override
	public void setFetchSize(int rows) {
		this.fetchSize = Math.max(0, rows);
	}

	@Override
	public int getFetchSize() {
		return fetchSize;
	}

	private void applyFetchWindow(SyncStatement statement) {
		if (fetchSize > 0) {
			statement.fetchWindow(fetchSize);
		}
	}

	@Override
	public int getResultSetConcurrency() {
		return ResultSet.CONCUR_READ_ONLY;
	}

	@Override
	public int getResultSetType() {
		return ResultSet.TYPE_SCROLL_INSENSITIVE;
	}

	@Override
	public void addBatch(String sql) throws SQLException {
		ensureOpen();
		if (sql == null || sql.isBlank()) {
			throw new SQLException("Batch SQL must not be blank", SQLSTATE_GENERAL);
		}
		batchSql.add(sql);
	}

	@Override
	public void clearBatch() {
		batchSql.clear();
	}

	@Override
	public int[] executeBatch() throws SQLException {
		ensureOpen();
		if (batchSql.isEmpty()) {
			return new int[0];
		}
		final List<String> sqls = List.copyOf(batchSql);
		batchSql.clear();
		clearCurrent();
		lastSqlOrNull = null;
		final List<SyncResult> results = connection.syncBatch(sqls, queryTimeoutDuration());
		final int[] counts = new int[results.size()];
		for (int i = 0; i < results.size(); i++) {
			final SyncResult result = results.get(i);
			if (result.isResultSet()) {
				counts[i] = BATCH_SUCCESS_NO_INFO;
			} else {
				final long count = result.rowsUpdated();
				counts[i] = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, count));
			}
		}
		if (!results.isEmpty()) {
			for (int i = 1; i < results.size(); i++) {
				pendingSyncResults.add(results.get(i));
			}
			applySyncResult(results.getFirst(), sqls.getFirst());
		}
		return counts;
	}

	@Override
	public Connection getConnection() {
		return connection;
	}

	@Override
	public boolean getMoreResults(int current) throws SQLException {
		return getMoreResults();
	}

	@Override
	public ResultSet getGeneratedKeys() throws SQLException {
		throw new SQLFeatureNotSupportedException("getGeneratedKeys");
	}

	@Override
	public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
		return executeUpdate(sql);
	}

	@Override
	public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
		return executeUpdate(sql);
	}

	@Override
	public int executeUpdate(String sql, String[] columnNames) throws SQLException {
		return executeUpdate(sql);
	}

	@Override
	public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
		return execute(sql);
	}

	@Override
	public boolean execute(String sql, int[] columnIndexes) throws SQLException {
		return execute(sql);
	}

	@Override
	public boolean execute(String sql, String[] columnNames) throws SQLException {
		return execute(sql);
	}

	@Override
	public int getResultSetHoldability() {
		return ResultSet.HOLD_CURSORS_OVER_COMMIT;
	}

	@Override
	public boolean isClosed() {
		return closed;
	}

	@Override
	public void setPoolable(boolean poolable) {
	}

	@Override
	public boolean isPoolable() {
		return false;
	}

	@Override
	public void closeOnCompletion() {
	}

	@Override
	public boolean isCloseOnCompletion() {
		return false;
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

	void ensureOpen() throws SQLException {
		connection.ensureOpen();
		if (closed) {
			throw new SQLException(MSG_CLOSED, SQLSTATE_GENERAL);
		}
	}

	record ExecOutcome(GridResultSet resultSet, long updateCount) {
	}
}
