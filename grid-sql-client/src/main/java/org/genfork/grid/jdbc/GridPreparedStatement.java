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

import org.genfork.grid.sql.client.sync.SyncStatement;

/**
 * JDBC {@link PreparedStatement} using SPI positional binds.
 * <p>
 * {@link #executeBatch()} runs per-bind {@code EXEC} via Sync* because wire
 * {@code BATCH_EXEC} accepts only {@code List<String>} (no bind payloads). Statement-level
 * string batch still uses {@code syncBatch} → {@code BATCH_EXEC}.
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
		for (Map.Entry<Integer, Object> e : bindMap.entrySet()) {
			final int jdbcIndex = e.getKey();
			if (jdbcIndex < 1) {
				throw new SQLException("Parameter index must be >= 1", "HY000");
			}
			statement.bind(jdbcIndex - 1, e.getValue());
		}
		return statement;
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
		final ExecOutcome outcome = run(boundSpi(), sql);
		if (outcome.resultSet() != null) {
			throw new SQLException("SQL produced a result set", "HY000");
		}
		return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, outcome.updateCount()));
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
		binds.put(parameterIndex, x == null ? null : x.toInstant());
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
		setObject(parameterIndex, x);
	}

	@Override
	public void setObject(int parameterIndex, Object x) {
		binds.put(parameterIndex, x);
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
		final int[] counts = new int[snapshot.size()];
		for (int i = 0; i < snapshot.size(); i++) {
			final ExecOutcome outcome = run(boundSpi(snapshot.get(i)), sql);
			if (outcome.resultSet() != null) {
				counts[i] = Statement.SUCCESS_NO_INFO;
			} else {
				counts[i] = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, outcome.updateCount()));
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
		setTimestamp(parameterIndex, x);
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
		setObject(parameterIndex, x);
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
