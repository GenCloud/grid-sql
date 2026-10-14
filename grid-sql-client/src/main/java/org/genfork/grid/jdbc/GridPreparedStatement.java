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

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.genfork.grid.sql.SqlBinds;
import org.genfork.grid.sql.client.sync.SyncResult;
import org.genfork.grid.sql.client.sync.SyncStatement;
import org.genfork.grid.sql.netty.SqlWire;

/**
 * JDBC {@link PreparedStatement} using SPI positional binds.
 * <p>
 * {@link #executeBatch()} sends one bound {@code BATCH_EXEC} (same SQL, N bind rows).
 * Statement-level string batch uses {@code syncBatch} → {@code BATCH_EXEC} with bindCount=0.
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
public final class GridPreparedStatement extends GridStatement implements PreparedStatement {
	private final String sql;
	private final TreeMap<Integer, Object> binds = new TreeMap<>();
	private final List<TreeMap<Integer, Object>> batchBinds = new ArrayList<>();

	GridPreparedStatement(GridConnection connection, String sql) {
		super(connection);
		this.sql = sql;
	}

	private SyncStatement boundSpi() throws SQLException {
		return boundSpi(binds);
	}

	private SyncStatement boundSpi(TreeMap<Integer, Object> bindMap) throws SQLException {
		ensureOpen();
		final SyncStatement statement = connection.syncStatement(sql);
		final Object[] dense = denseJdbcBinds(bindMap);
		for (int i = 0; i < dense.length; i++) {
			statement.bind(i, dense[i]);
		}
		return statement;
	}

	/** JDBC 1-based parameter map → dense 0-based wire binds. */
	private static Object[] denseJdbcBinds(TreeMap<Integer, Object> bindMap) throws SQLException {
		if (bindMap == null || bindMap.isEmpty()) {
			return new Object[0];
		}
		final TreeMap<Integer, Object> zeroBased = new TreeMap<>();
		for (Map.Entry<Integer, Object> e : bindMap.entrySet()) {
			final int jdbcIndex = e.getKey();
			if (jdbcIndex < 1) {
				throw new SQLException("Parameter index must be >= 1", "HY000");
			}
			zeroBased.put(jdbcIndex - 1, e.getValue());
		}
		return SqlBinds.dense(zeroBased);
	}

	@Override
	public ResultSet executeQuery() throws SQLException {
		final ExecOutcome outcome = run(boundSpi(), sql);
		if (outcome.resultSet() == null) {
			throw new SQLException("SQL did not produce a result set", "HY000");
		}
		return outcome.resultSet();
	}

	@Override
	public int executeUpdate() throws SQLException {
		return acceptUpdateOrReturning(run(boundSpi(), sql));
	}

	@Override
	public boolean execute() throws SQLException {
		return run(boundSpi(), sql).resultSet() != null;
	}

	@Override
	public void setNull(int parameterIndex, int sqlType) {
		binds.put(parameterIndex, null);
	}

	@Override
	public void setBoolean(int parameterIndex, boolean x) {
		binds.put(parameterIndex, x);
	}

	@Override
	public void setByte(int parameterIndex, byte x) {
		binds.put(parameterIndex, (int) x);
	}

	@Override
	public void setShort(int parameterIndex, short x) {
		binds.put(parameterIndex, (int) x);
	}

	@Override
	public void setInt(int parameterIndex, int x) {
		binds.put(parameterIndex, x);
	}

	@Override
	public void setLong(int parameterIndex, long x) {
		binds.put(parameterIndex, x);
	}

	@Override
	public void setFloat(int parameterIndex, float x) {
		binds.put(parameterIndex, (double) x);
	}

	@Override
	public void setDouble(int parameterIndex, double x) {
		binds.put(parameterIndex, x);
	}

	@Override
	public void setBigDecimal(int parameterIndex, BigDecimal x) {
		binds.put(parameterIndex, x == null ? null : x.doubleValue());
	}

	@Override
	public void setString(int parameterIndex, String x) {
		binds.put(parameterIndex, x);
	}

	@Override
	public void setBytes(int parameterIndex, byte[] x) {
		binds.put(parameterIndex, x);
	}

	@Override
	public void setDate(int parameterIndex, Date x) {
		binds.put(parameterIndex, x == null ? null : x.toLocalDate());
	}

	@Override
	public void setTime(int parameterIndex, Time x) {
		binds.put(parameterIndex, x == null ? null : x.toLocalTime());
	}

	@Override
	public void setTimestamp(int parameterIndex, Timestamp x) {
		binds.put(parameterIndex, GridJdbcTemporalBindUtil.fromTimestamp(x));
	}

	@Override
	public void setAsciiStream(int parameterIndex, InputStream x, int length) throws SQLException {
		throw unsupported("setAsciiStream");
	}

	@Override
	public void setUnicodeStream(int parameterIndex, InputStream x, int length) throws SQLException {
		throw unsupported("setUnicodeStream");
	}

	@Override
	public void setBinaryStream(int parameterIndex, InputStream x, int length) throws SQLException {
		throw unsupported("setBinaryStream");
	}

	@Override
	public void clearParameters() {
		binds.clear();
	}

	@Override
	public void setObject(int parameterIndex, Object x, int targetSqlType) {
		binds.put(parameterIndex, GridJdbcTemporalBindUtil.normalizeObject(x, targetSqlType));
	}

	@Override
	public void setObject(int parameterIndex, Object x) {
		binds.put(parameterIndex, GridJdbcTemporalBindUtil.normalizeObject(x));
	}

	@Override
	public void addBatch() throws SQLException {
		ensureOpen();
		batchBinds.add(new TreeMap<>(binds));
	}

	@Override
	public void clearBatch() {
		super.clearBatch();
		batchBinds.clear();
	}

	@Override
	public int[] executeBatch() throws SQLException {
		ensureOpen();
		if (batchBinds.isEmpty()) {
			return new int[0];
		}
		final List<TreeMap<Integer, Object>> snapshot = List.copyOf(batchBinds);
		batchBinds.clear();
		final List<SqlWire.BatchStatement> statements = new ArrayList<>(snapshot.size());
		for (TreeMap<Integer, Object> bindMap : snapshot) {
			statements.add(new SqlWire.BatchStatement(sql, denseJdbcBinds(bindMap)));
		}
		final List<SyncResult> results = connection.syncBatchBound(statements, queryTimeoutDuration());
		final int[] counts = new int[results.size()];
		for (int i = 0; i < results.size(); i++) {
			final SyncResult result = results.get(i);
			if (result.isResultSet()) {
				counts[i] = Statement.SUCCESS_NO_INFO;
			} else {
				counts[i] = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, result.rowsUpdated()));
			}
		}
		return counts;
	}

	@Override
	public void setCharacterStream(int parameterIndex, Reader reader, int length) throws SQLException {
		throw unsupported("setCharacterStream");
	}

	@Override
	public void setRef(int parameterIndex, Ref x) throws SQLException {
		throw unsupported("setRef");
	}

	@Override
	public void setBlob(int parameterIndex, Blob x) throws SQLException {
		throw unsupported("setBlob");
	}

	@Override
	public void setClob(int parameterIndex, Clob x) throws SQLException {
		throw unsupported("setClob");
	}

	@Override
	public void setArray(int parameterIndex, Array x) throws SQLException {
		throw unsupported("setArray");
	}

	@Override
	public ResultSetMetaData getMetaData() throws SQLException {
		throw unsupported("getMetaData");
	}

	@Override
	public void setDate(int parameterIndex, Date x, Calendar cal) {
		setDate(parameterIndex, x);
	}

	@Override
	public void setTime(int parameterIndex, Time x, Calendar cal) {
		setTime(parameterIndex, x);
	}

	@Override
	public void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) {
		binds.put(parameterIndex, GridJdbcTemporalBindUtil.fromTimestamp(x, cal));
	}

	@Override
	public void setNull(int parameterIndex, int sqlType, String typeName) {
		setNull(parameterIndex, sqlType);
	}

	@Override
	public void setURL(int parameterIndex, URL x) throws SQLException {
		throw unsupported("setURL");
	}

	@Override
	public ParameterMetaData getParameterMetaData() throws SQLException {
		throw unsupported("getParameterMetaData");
	}

	@Override
	public void setRowId(int parameterIndex, RowId x) throws SQLException {
		throw unsupported("setRowId");
	}

	@Override
	public void setNString(int parameterIndex, String value) {
		setString(parameterIndex, value);
	}

	@Override
	public void setNCharacterStream(int parameterIndex, Reader value, long length) throws SQLException {
		throw unsupported("setNCharacterStream");
	}

	@Override
	public void setNClob(int parameterIndex, NClob value) throws SQLException {
		throw unsupported("setNClob");
	}

	@Override
	public void setClob(int parameterIndex, Reader reader, long length) throws SQLException {
		throw unsupported("setClob");
	}

	@Override
	public void setBlob(int parameterIndex, InputStream inputStream, long length) throws SQLException {
		throw unsupported("setBlob");
	}

	@Override
	public void setNClob(int parameterIndex, Reader reader, long length) throws SQLException {
		throw unsupported("setNClob");
	}

	@Override
	public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException {
		throw unsupported("setSQLXML");
	}

	@Override
	public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) {
		setObject(parameterIndex, x, targetSqlType);
	}

	@Override
	public void setAsciiStream(int parameterIndex, InputStream x, long length) throws SQLException {
		throw unsupported("setAsciiStream");
	}

	@Override
	public void setBinaryStream(int parameterIndex, InputStream x, long length) throws SQLException {
		throw unsupported("setBinaryStream");
	}

	@Override
	public void setCharacterStream(int parameterIndex, Reader reader, long length) throws SQLException {
		throw unsupported("setCharacterStream");
	}

	@Override
	public void setAsciiStream(int parameterIndex, InputStream x) throws SQLException {
		throw unsupported("setAsciiStream");
	}

	@Override
	public void setBinaryStream(int parameterIndex, InputStream x) throws SQLException {
		throw unsupported("setBinaryStream");
	}

	@Override
	public void setCharacterStream(int parameterIndex, Reader reader) throws SQLException {
		throw unsupported("setCharacterStream");
	}

	@Override
	public void setNCharacterStream(int parameterIndex, Reader value) throws SQLException {
		throw unsupported("setNCharacterStream");
	}

	@Override
	public void setClob(int parameterIndex, Reader reader) throws SQLException {
		throw unsupported("setClob");
	}

	@Override
	public void setBlob(int parameterIndex, InputStream inputStream) throws SQLException {
		throw unsupported("setBlob");
	}

	@Override
	public void setNClob(int parameterIndex, Reader reader) throws SQLException {
		throw unsupported("setNClob");
	}

	private static SQLFeatureNotSupportedException unsupported(String method) {
		return new SQLFeatureNotSupportedException(method + " not supported");
	}
}
