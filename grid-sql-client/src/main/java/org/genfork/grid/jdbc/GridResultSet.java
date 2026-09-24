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
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.genfork.grid.catalog.SqlTypeCoercion;
import org.genfork.grid.jdbc.GridJdbcCatalogSupport.GridColumnHints;
import org.genfork.grid.sql.client.RowMetadata;

/**
 * Scrollable JDBC {@link ResultSet}; optionally updatable when a PK edit context is present.
 * <p>
 * Cells are decoded on {@code getXxx}; row DML is issued via
 * {@link GridConnection#executeUpdateInternal(String)}.
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
public final class GridResultSet implements ResultSet {
	private static final String MSG_CLOSED = "ResultSet is closed";
	private static final String MSG_BEFORE = "Before first row";
	private static final String MSG_AFTER = "After last row";
	private static final String MSG_NOT_ON_ROW = "Not on a valid row";
	private static final String MSG_READ_ONLY = "ResultSet is CONCUR_READ_ONLY";
	private static final String MSG_NO_PK = "Primary key column not in result set";
	private static final String MSG_ON_INSERT = "Cursor is on insert row";
	private static final String MSG_NOT_ON_INSERT = "Cursor is not on insert row";
	private static final String MSG_INVALID_COLUMN = "Invalid column index: ";
	private static final String MSG_UNKNOWN_COLUMN = "Unknown column: ";
	private static final String SQLSTATE_GENERAL = "HY000";

	private static final String SQL_NULL = "NULL";
	private static final String SQL_UPDATE = "UPDATE ";
	private static final String SQL_SET = " SET ";
	private static final String SQL_WHERE = " WHERE ";
	private static final String SQL_DELETE = "DELETE FROM ";
	private static final String SQL_INSERT = "INSERT INTO ";
	private static final String SQL_VALUES = " VALUES (";
	private static final String SQL_SELECT = "SELECT ";
	private static final String SQL_FROM = " FROM ";
	private static final String SQL_EQ = " = ";
	private static final String SQL_COMMA = ", ";
	private static final String SQL_CLOSE_PAREN = ")";
	private static final char SQL_QUOTE = '\'';
	private static final char HEX_PREFIX_A = 'X';
	private static final char HEX_PREFIX_B = '\'';

	private final GridStatement statement;
	private final RowMetadata metadata;
	private final List<Object[]> rows;
	private final GridResultSetMetaData rsMeta;
	private final GridTableEditContext edit;
	private final int concurrency;

	private int cursor = -1;
	private boolean closed;
	private boolean wasNull;
	private boolean onInsertRow;
	private int savedCursor = -1;
	private final Object[] updateBuffer;
	private final boolean[] updateFlags;
	private final Object[] insertBuffer;
	private final boolean[] insertFlags;

	GridResultSet(GridStatement statement, RowMetadata metadata, List<Object[]> rows) {
		this(statement, metadata, rows, null, null);
	}

	GridResultSet(
			GridStatement statement,
			RowMetadata metadata,
			List<Object[]> rows,
			GridTableEditContext edit,
			GridColumnHints hints) {
		this.statement = statement;
		this.metadata = metadata;
		this.rows = rows instanceof ArrayList ? rows : new ArrayList<>(rows);
		final String schema = edit != null ? edit.schema() : (hints != null ? hints.schema() : "");
		final String table = edit != null ? edit.table() : (hints != null ? hints.table() : "");
		this.edit = edit;
		this.rsMeta = new GridResultSetMetaData(metadata, hints, schema, table);
		this.concurrency = edit != null && edit.pkIndex() >= 0 ? CONCUR_UPDATABLE : CONCUR_READ_ONLY;
		final int cols = metadata.getColumnCount();
		this.updateBuffer = new Object[cols];
		this.updateFlags = new boolean[cols];
		this.insertBuffer = new Object[cols];
		this.insertFlags = new boolean[cols];
	}

	@Override
	public boolean next() throws SQLException {
		ensureOpen();
		ensureNotInsertRow();
		if (cursor + 1 >= rows.size()) {
			cursor = rows.size();
			return false;
		}
		cursor++;
		clearUpdates();
		return true;
	}

	@Override
	public boolean previous() throws SQLException {
		ensureOpen();
		ensureNotInsertRow();
		if (cursor <= 0) {
			cursor = -1;
			return false;
		}
		cursor--;
		clearUpdates();
		return true;
	}

	@Override
	public boolean first() throws SQLException {
		ensureOpen();
		ensureNotInsertRow();
		if (rows.isEmpty()) {
			cursor = -1;
			return false;
		}
		cursor = 0;
		clearUpdates();
		return true;
	}

	@Override
	public boolean last() throws SQLException {
		ensureOpen();
		ensureNotInsertRow();
		if (rows.isEmpty()) {
			cursor = -1;
			return false;
		}
		cursor = rows.size() - 1;
		clearUpdates();
		return true;
	}

	@Override
	public void beforeFirst() throws SQLException {
		ensureOpen();
		ensureNotInsertRow();
		cursor = -1;
		clearUpdates();
	}

	@Override
	public void afterLast() throws SQLException {
		ensureOpen();
		ensureNotInsertRow();
		cursor = rows.size();
		clearUpdates();
	}

	@Override
	public boolean absolute(int row) throws SQLException {
		ensureOpen();
		ensureNotInsertRow();
		if (row == 0) {
			cursor = -1;
			clearUpdates();
			return false;
		}
		final int target;
		if (row > 0) {
			target = row - 1;
		} else {
			target = rows.size() + row;
		}
		if (target < 0) {
			cursor = -1;
			clearUpdates();
			return false;
		}
		if (target >= rows.size()) {
			cursor = rows.size();
			clearUpdates();
			return false;
		}
		cursor = target;
		clearUpdates();
		return true;
	}

	@Override
	public boolean relative(int rowsDelta) throws SQLException {
		ensureOpen();
		ensureNotInsertRow();
		return absolute(cursor + rowsDelta + 1);
	}

	@Override
	public boolean isBeforeFirst() {
		return !rows.isEmpty() && cursor < 0;
	}

	@Override
	public boolean isAfterLast() {
		return !rows.isEmpty() && cursor >= rows.size();
	}

	@Override
	public boolean isFirst() {
		return !rows.isEmpty() && cursor == 0;
	}

	@Override
	public boolean isLast() {
		return !rows.isEmpty() && cursor == rows.size() - 1;
	}

	@Override
	public int getRow() {
		if (onInsertRow || cursor < 0 || cursor >= rows.size()) {
			return 0;
		}
		return cursor + 1;
	}

	@Override
	public void close() {
		closed = true;
	}

	@Override
	public boolean wasNull() {
		return wasNull;
	}

	private Object cell(int columnIndex) throws SQLException {
		ensureOpen();
		ensureOnRow();
		final int idx = columnIndex - 1;
		ensureColumnIndex(idx, columnIndex);
		final Object v = rows.get(cursor)[idx];
		wasNull = v == null;
		return v;
	}

	private Object cell(String label) throws SQLException {
		return cell(findIndex(label) + 1);
	}

	@Override
	public String getString(int columnIndex) throws SQLException {
		final Object v = cell(columnIndex);
		return v == null ? null : String.valueOf(v);
	}

	@Override
	public boolean getBoolean(int columnIndex) throws SQLException {
		final Object v = cell(columnIndex);
        return switch (v) {
            case null -> false;
            case Boolean b -> b;
            case Number n -> n.intValue() != 0;
            default -> Boolean.parseBoolean(String.valueOf(v));
        };
    }

	@Override
	public byte getByte(int columnIndex) throws SQLException {
		return (byte) getLong(columnIndex);
	}

	@Override
	public short getShort(int columnIndex) throws SQLException {
		return (short) getLong(columnIndex);
	}

	@Override
	public int getInt(int columnIndex) throws SQLException {
		return (int) getLong(columnIndex);
	}

	@Override
	public long getLong(int columnIndex) throws SQLException {
		final Object v = cell(columnIndex);
		if (v == null) {
			return 0L;
		}
		if (v instanceof Number n) {
			return n.longValue();
		}
		return Long.parseLong(String.valueOf(v));
	}

	@Override
	public float getFloat(int columnIndex) throws SQLException {
		return (float) getDouble(columnIndex);
	}

	@Override
	public double getDouble(int columnIndex) throws SQLException {
		final Object v = cell(columnIndex);
		if (v == null) {
			return 0.0d;
		}
		if (v instanceof Number n) {
			return n.doubleValue();
		}
		return Double.parseDouble(String.valueOf(v));
	}

	@Override
	public BigDecimal getBigDecimal(int columnIndex, int scale) throws SQLException {
		return getBigDecimal(columnIndex);
	}

	@Override
	public byte[] getBytes(int columnIndex) throws SQLException {
		final Object v = cell(columnIndex);
		if (v == null) {
			return null;
		}
		if (v instanceof byte[] bytes) {
			return bytes;
		}
		return String.valueOf(v).getBytes();
	}

	@Override
	public Date getDate(int columnIndex) throws SQLException {
		final Object v = cell(columnIndex);
        return switch (v) {
            case null -> null;
            case Date d -> d;
            case LocalDate ld -> Date.valueOf(ld);
            case Instant i -> Date.valueOf(LocalDate.ofInstant(i, ZoneOffset.UTC));
            default -> Date.valueOf(Objects.requireNonNull(SqlTypeCoercion.toDate(v)));
        };
    }

	@Override
	public Time getTime(int columnIndex) throws SQLException {
		final Object v = cell(columnIndex);
        return switch (v) {
            case null -> null;
            case Time t -> t;
            case LocalTime lt -> Time.valueOf(lt);
            default -> Time.valueOf(Objects.requireNonNull(SqlTypeCoercion.toTime(v)));
        };
    }

	@Override
	public Timestamp getTimestamp(int columnIndex) throws SQLException {
		final Object v = cell(columnIndex);
        return switch (v) {
            case null -> null;
            case Timestamp ts -> ts;
            case Instant i -> Timestamp.from(i);
            case LocalDateTime ldt -> Timestamp.valueOf(ldt);
            case LocalDate ld -> Timestamp.valueOf(ld.atStartOfDay());
            default -> Timestamp.valueOf(Objects.requireNonNull(SqlTypeCoercion.toTimestampString(v)).replace('T', ' '));
        };
    }

	@Override
	public InputStream getAsciiStream(int columnIndex) throws SQLException {
		throw unsupported("getAsciiStream");
	}

	@Override
	public InputStream getUnicodeStream(int columnIndex) throws SQLException {
		throw unsupported("getUnicodeStream");
	}

	@Override
	public InputStream getBinaryStream(int columnIndex) throws SQLException {
		throw unsupported("getBinaryStream");
	}

	@Override
	public String getString(String columnLabel) throws SQLException {
		final Object v = cell(columnLabel);
		return v == null ? null : String.valueOf(v);
	}

	@Override
	public boolean getBoolean(String columnLabel) throws SQLException {
		final Object v = cell(columnLabel);
		if (v == null) {
			return false;
		}
		if (v instanceof Boolean b) {
			return b;
		}
		return Boolean.parseBoolean(String.valueOf(v));
	}

	@Override
	public byte getByte(String columnLabel) throws SQLException {
		return (byte) getLong(columnLabel);
	}

	@Override
	public short getShort(String columnLabel) throws SQLException {
		return (short) getLong(columnLabel);
	}

	@Override
	public int getInt(String columnLabel) throws SQLException {
		return (int) getLong(columnLabel);
	}

	@Override
	public long getLong(String columnLabel) throws SQLException {
		final Object v = cell(columnLabel);
		if (v == null) {
			return 0L;
		}
		if (v instanceof Number n) {
			return n.longValue();
		}
		return Long.parseLong(String.valueOf(v));
	}

	@Override
	public float getFloat(String columnLabel) throws SQLException {
		return (float) getDouble(columnLabel);
	}

	@Override
	public double getDouble(String columnLabel) throws SQLException {
		final Object v = cell(columnLabel);
		if (v == null) {
			return 0.0d;
		}
		if (v instanceof Number n) {
			return n.doubleValue();
		}
		return Double.parseDouble(String.valueOf(v));
	}

	@Override
	public BigDecimal getBigDecimal(String columnLabel, int scale) throws SQLException {
		return getBigDecimal(columnLabel);
	}

	@Override
	public byte[] getBytes(String columnLabel) throws SQLException {
		final Object v = cell(columnLabel);
		if (v == null) {
			return null;
		}
		if (v instanceof byte[] bytes) {
			return bytes;
		}
		return String.valueOf(v).getBytes();
	}

	@Override
	public Date getDate(String columnLabel) throws SQLException {
		return getDate(findIndex(columnLabel) + 1);
	}

	@Override
	public Time getTime(String columnLabel) throws SQLException {
		return getTime(findIndex(columnLabel) + 1);
	}

	@Override
	public Timestamp getTimestamp(String columnLabel) throws SQLException {
		return getTimestamp(findIndex(columnLabel) + 1);
	}

	@Override
	public InputStream getAsciiStream(String columnLabel) throws SQLException {
		throw unsupported("getAsciiStream");
	}

	@Override
	public InputStream getUnicodeStream(String columnLabel) throws SQLException {
		throw unsupported("getUnicodeStream");
	}

	@Override
	public InputStream getBinaryStream(String columnLabel) throws SQLException {
		throw unsupported("getBinaryStream");
	}

	@Override
	public SQLWarning getWarnings() {
		return null;
	}

	@Override
	public void clearWarnings() {
	}

	@Override
	public String getCursorName() throws SQLException {
		throw unsupported("getCursorName");
	}

	@Override
	public ResultSetMetaData getMetaData() {
		return rsMeta;
	}

	@Override
	public Object getObject(int columnIndex) throws SQLException {
		return cell(columnIndex);
	}

	@Override
	public Object getObject(String columnLabel) throws SQLException {
		return cell(columnLabel);
	}

	@Override
	public int findColumn(String columnLabel) throws SQLException {
		return findIndex(columnLabel) + 1;
	}

	private int findIndex(String columnLabel) throws SQLException {
		for (int i = 0; i < metadata.getColumnCount(); i++) {
			if (metadata.getColumnNames().get(i).equalsIgnoreCase(columnLabel)) {
				return i;
			}
		}
		throw new SQLException(MSG_UNKNOWN_COLUMN + columnLabel, SQLSTATE_GENERAL);
	}

	@Override
	public Reader getCharacterStream(int columnIndex) throws SQLException {
		throw unsupported("getCharacterStream");
	}

	@Override
	public Reader getCharacterStream(String columnLabel) throws SQLException {
		throw unsupported("getCharacterStream");
	}

	@Override
	public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
		final Object v = cell(columnIndex);
        return switch (v) {
            case null -> null;
            case BigDecimal bd -> bd;
            case Number n -> BigDecimal.valueOf(n.doubleValue());
            default -> new BigDecimal(String.valueOf(v));
        };
    }

	@Override
	public BigDecimal getBigDecimal(String columnLabel) throws SQLException {
		return getBigDecimal(findIndex(columnLabel) + 1);
	}

	@Override
	public void setFetchDirection(int direction) {
	}

	@Override
	public int getFetchDirection() {
		return FETCH_FORWARD;
	}

	@Override
	public void setFetchSize(int rows) {
	}

	@Override
	public int getFetchSize() {
		return 0;
	}

	@Override
	public int getType() {
		return TYPE_SCROLL_INSENSITIVE;
	}

	@Override
	public int getConcurrency() {
		return concurrency;
	}

	@Override
	public boolean rowUpdated() {
		return false;
	}

	@Override
	public boolean rowInserted() {
		return false;
	}

	@Override
	public boolean rowDeleted() {
		return false;
	}

	@Override
	public void updateNull(int columnIndex) throws SQLException {
		bufferPut(columnIndex, null);
	}

	@Override
	public void updateBoolean(int columnIndex, boolean x) throws SQLException {
		bufferPut(columnIndex, x);
	}

	@Override
	public void updateByte(int columnIndex, byte x) throws SQLException {
		bufferPut(columnIndex, (int) x);
	}

	@Override
	public void updateShort(int columnIndex, short x) throws SQLException {
		bufferPut(columnIndex, (int) x);
	}

	@Override
	public void updateInt(int columnIndex, int x) throws SQLException {
		bufferPut(columnIndex, x);
	}

	@Override
	public void updateLong(int columnIndex, long x) throws SQLException {
		bufferPut(columnIndex, x);
	}

	@Override
	public void updateFloat(int columnIndex, float x) throws SQLException {
		bufferPut(columnIndex, (double) x);
	}

	@Override
	public void updateDouble(int columnIndex, double x) throws SQLException {
		bufferPut(columnIndex, x);
	}

	@Override
	public void updateBigDecimal(int columnIndex, BigDecimal x) throws SQLException {
		bufferPut(columnIndex, x);
	}

	@Override
	public void updateString(int columnIndex, String x) throws SQLException {
		bufferPut(columnIndex, x);
	}

	@Override
	public void updateBytes(int columnIndex, byte[] x) throws SQLException {
		bufferPut(columnIndex, x);
	}

	@Override
	public void updateDate(int columnIndex, Date x) throws SQLException {
		bufferPut(columnIndex, x);
	}

	@Override
	public void updateTime(int columnIndex, Time x) throws SQLException {
		throw unsupported("updateTime");
	}

	@Override
	public void updateTimestamp(int columnIndex, Timestamp x) throws SQLException {
		bufferPut(columnIndex, x);
	}

	@Override
	public void updateAsciiStream(int columnIndex, InputStream x, int length) throws SQLException {
		throw unsupported("update");
	}

	@Override
	public void updateBinaryStream(int columnIndex, InputStream x, int length) throws SQLException {
		throw unsupported("update");
	}

	@Override
	public void updateCharacterStream(int columnIndex, Reader x, int length) throws SQLException {
		throw unsupported("update");
	}

	@Override
	public void updateObject(int columnIndex, Object x, int scaleOrLength) throws SQLException {
		bufferPut(columnIndex, x);
	}

	@Override
	public void updateObject(int columnIndex, Object x) throws SQLException {
		bufferPut(columnIndex, x);
	}

	@Override
	public void updateNull(String columnLabel) throws SQLException {
		updateNull(findIndex(columnLabel) + 1);
	}

	@Override
	public void updateBoolean(String columnLabel, boolean x) throws SQLException {
		updateBoolean(findIndex(columnLabel) + 1, x);
	}

	@Override
	public void updateByte(String columnLabel, byte x) throws SQLException {
		updateByte(findIndex(columnLabel) + 1, x);
	}

	@Override
	public void updateShort(String columnLabel, short x) throws SQLException {
		updateShort(findIndex(columnLabel) + 1, x);
	}

	@Override
	public void updateInt(String columnLabel, int x) throws SQLException {
		updateInt(findIndex(columnLabel) + 1, x);
	}

	@Override
	public void updateLong(String columnLabel, long x) throws SQLException {
		updateLong(findIndex(columnLabel) + 1, x);
	}

	@Override
	public void updateFloat(String columnLabel, float x) throws SQLException {
		updateFloat(findIndex(columnLabel) + 1, x);
	}

	@Override
	public void updateDouble(String columnLabel, double x) throws SQLException {
		updateDouble(findIndex(columnLabel) + 1, x);
	}

	@Override
	public void updateBigDecimal(String columnLabel, BigDecimal x) throws SQLException {
		updateBigDecimal(findIndex(columnLabel) + 1, x);
	}

	@Override
	public void updateString(String columnLabel, String x) throws SQLException {
		updateString(findIndex(columnLabel) + 1, x);
	}

	@Override
	public void updateBytes(String columnLabel, byte[] x) throws SQLException {
		updateBytes(findIndex(columnLabel) + 1, x);
	}

	@Override
	public void updateDate(String columnLabel, Date x) throws SQLException {
		updateDate(findIndex(columnLabel) + 1, x);
	}

	@Override
	public void updateTime(String columnLabel, Time x) throws SQLException {
		throw unsupported("updateTime");
	}

	@Override
	public void updateTimestamp(String columnLabel, Timestamp x) throws SQLException {
		updateTimestamp(findIndex(columnLabel) + 1, x);
	}

	@Override
	public void updateAsciiStream(String columnLabel, InputStream x, int length) throws SQLException {
		throw unsupported("update");
	}

	@Override
	public void updateBinaryStream(String columnLabel, InputStream x, int length) throws SQLException {
		throw unsupported("update");
	}

	@Override
	public void updateCharacterStream(String columnLabel, Reader reader, int length) throws SQLException {
		throw unsupported("update");
	}

	@Override
	public void updateObject(String columnLabel, Object x, int scaleOrLength) throws SQLException {
		updateObject(findIndex(columnLabel) + 1, x);
	}

	@Override
	public void updateObject(String columnLabel, Object x) throws SQLException {
		updateObject(findIndex(columnLabel) + 1, x);
	}

	@Override
	public void insertRow() throws SQLException {
		ensureOpen();
		ensureUpdatable();
		if (!onInsertRow) {
			throw new SQLException(MSG_NOT_ON_INSERT, SQLSTATE_GENERAL);
		}
		final StringBuilder cols = new StringBuilder();
		final StringBuilder vals = new StringBuilder();
		boolean first = true;
		for (int i = 0; i < insertFlags.length; i++) {
			if (!insertFlags[i]) {
				continue;
			}
			if (!first) {
				cols.append(SQL_COMMA);
				vals.append(SQL_COMMA);
			}
			first = false;
			cols.append(metadata.getColumnNames().get(i));
			vals.append(sqlLiteral(insertBuffer[i]));
		}
		if (first) {
			throw new SQLException(MSG_NOT_ON_INSERT, SQLSTATE_GENERAL);
		}
		final String sql = SQL_INSERT + edit.qualifiedTable() + " (" + cols + ")" + SQL_VALUES + vals + SQL_CLOSE_PAREN;
		statement.connection.executeUpdateInternal(sql);
		final Object[] newRow = new Object[metadata.getColumnCount()];
		for (int i = 0; i < newRow.length; i++) {
			newRow[i] = insertFlags[i] ? insertBuffer[i] : null;
		}
		rows.add(newRow);
		clearInsert();
	}

	@Override
	public void updateRow() throws SQLException {
		ensureOpen();
		ensureUpdatable();
		ensureOnDataRow();
		final StringBuilder set = new StringBuilder();
		boolean first = true;
		for (int i = 0; i < updateFlags.length; i++) {
			if (!updateFlags[i]) {
				continue;
			}
			if (!first) {
				set.append(SQL_COMMA);
			}
			first = false;
			set.append(metadata.getColumnNames().get(i)).append(SQL_EQ).append(sqlLiteral(updateBuffer[i]));
		}
		if (first) {
			return;
		}
		final Object pkVal = rows.get(cursor)[edit.pkIndex()];
		final String sql = SQL_UPDATE + edit.qualifiedTable() + SQL_SET + set
				+ SQL_WHERE + edit.pkColumn() + SQL_EQ + sqlLiteral(pkVal);
		statement.connection.executeUpdateInternal(sql);
		final Object[] row = rows.get(cursor);
		for (int i = 0; i < updateFlags.length; i++) {
			if (updateFlags[i]) {
				row[i] = updateBuffer[i];
			}
		}
		clearUpdates();
	}

	@Override
	public void deleteRow() throws SQLException {
		ensureOpen();
		ensureUpdatable();
		ensureOnDataRow();
		final Object pkVal = rows.get(cursor)[edit.pkIndex()];
		final String sql = SQL_DELETE + edit.qualifiedTable()
				+ SQL_WHERE + edit.pkColumn() + SQL_EQ + sqlLiteral(pkVal);
		statement.connection.executeUpdateInternal(sql);
		rows.remove(cursor);
		if (cursor >= rows.size()) {
			cursor = rows.size();
		}
		clearUpdates();
	}

	@Override
	public void refreshRow() throws SQLException {
		ensureOpen();
		ensureUpdatable();
		ensureOnDataRow();
		final Object pkVal = rows.get(cursor)[edit.pkIndex()];
		final StringBuilder cols = new StringBuilder();
		for (int i = 0; i < metadata.getColumnCount(); i++) {
			if (i > 0) {
				cols.append(SQL_COMMA);
			}
			cols.append(metadata.getColumnNames().get(i));
		}
		final String sql = SQL_SELECT + cols + SQL_FROM + edit.qualifiedTable()
				+ SQL_WHERE + edit.pkColumn() + SQL_EQ + sqlLiteral(pkVal);
		final ResultSet rs = statement.connection.executeQueryInternal(sql);
		try {
			if (!rs.next()) {
				rows.remove(cursor);
				if (cursor >= rows.size()) {
					cursor = rows.size();
				}
				clearUpdates();
				return;
			}
			final Object[] row = rows.get(cursor);
			for (int i = 0; i < row.length; i++) {
				row[i] = rs.getObject(i + 1);
			}
		} finally {
			final Statement st = rs.getStatement();
			rs.close();
			if (st != null) {
				st.close();
			}
		}
		clearUpdates();
	}

	@Override
	public void cancelRowUpdates() throws SQLException {
		ensureOpen();
		clearUpdates();
		clearInsert();
	}

	@Override
	public void moveToInsertRow() throws SQLException {
		ensureOpen();
		ensureUpdatable();
		if (!onInsertRow) {
			savedCursor = cursor;
			onInsertRow = true;
		}
		clearInsert();
	}

	@Override
	public void moveToCurrentRow() throws SQLException {
		ensureOpen();
		if (onInsertRow) {
			onInsertRow = false;
			cursor = savedCursor;
			clearInsert();
		}
	}

	@Override
	public Statement getStatement() {
		return statement;
	}

	@Override
	public Object getObject(int columnIndex, Map<String, Class<?>> map) throws SQLException {
		return getObject(columnIndex);
	}

	@Override
	public Ref getRef(int columnIndex) throws SQLException {
		throw unsupported("getRef");
	}

	@Override
	public Blob getBlob(int columnIndex) throws SQLException {
		throw unsupported("getBlob");
	}

	@Override
	public Clob getClob(int columnIndex) throws SQLException {
		throw unsupported("getClob");
	}

	@Override
	public Array getArray(int columnIndex) throws SQLException {
		throw unsupported("getArray");
	}

	@Override
	public Object getObject(String columnLabel, Map<String, Class<?>> map) throws SQLException {
		return getObject(columnLabel);
	}

	@Override
	public Ref getRef(String columnLabel) throws SQLException {
		throw unsupported("getRef");
	}

	@Override
	public Blob getBlob(String columnLabel) throws SQLException {
		throw unsupported("getBlob");
	}

	@Override
	public Clob getClob(String columnLabel) throws SQLException {
		throw unsupported("getClob");
	}

	@Override
	public Array getArray(String columnLabel) throws SQLException {
		throw unsupported("getArray");
	}

	@Override
	public Date getDate(int columnIndex, Calendar cal) throws SQLException {
		final Object v = cell(columnIndex);
		if (v == null) {
			return null;
		}
		if (v instanceof Instant i && cal != null) {
			final ZoneId z = cal.getTimeZone().toZoneId();
			return Date.valueOf(LocalDate.ofInstant(i, z));
		}
		return getDate(columnIndex);
	}

	@Override
	public Date getDate(String columnLabel, Calendar cal) throws SQLException {
		return getDate(findIndex(columnLabel) + 1, cal);
	}

	@Override
	public Time getTime(int columnIndex, Calendar cal) throws SQLException {
		return getTime(columnIndex);
	}

	@Override
	public Time getTime(String columnLabel, Calendar cal) throws SQLException {
		return getTime(columnLabel);
	}

	@Override
	public Timestamp getTimestamp(int columnIndex, Calendar cal) throws SQLException {
		final Object v = cell(columnIndex);
        switch (v) {
            case null -> {
                return null;
            }
            case Instant i -> {
                return Timestamp.from(i);
            }
            case String s when cal != null -> {
                final Instant i = SqlTypeCoercion.toTimestamptz(s, cal.getTimeZone().toZoneId());
                return Timestamp.from(Objects.requireNonNull(i));
            }
            default -> {
            }
        }
        return getTimestamp(columnIndex);
	}

	@Override
	public Timestamp getTimestamp(String columnLabel, Calendar cal) throws SQLException {
		return getTimestamp(findIndex(columnLabel) + 1, cal);
	}

	@Override
	public URL getURL(int columnIndex) throws SQLException {
		throw unsupported("getURL");
	}

	@Override
	public URL getURL(String columnLabel) throws SQLException {
		throw unsupported("getURL");
	}

	@Override
	public void updateRef(int columnIndex, Ref x) throws SQLException {
		throw unsupported("update");
	}

	@Override
	public void updateRef(String columnLabel, Ref x) throws SQLException {
		throw unsupported("update");
	}

	@Override
	public void updateBlob(int columnIndex, Blob x) throws SQLException {
		throw unsupported("update");
	}

	@Override
	public void updateBlob(String columnLabel, Blob x) throws SQLException {
		throw unsupported("update");
	}

	@Override
	public void updateClob(int columnIndex, Clob x) throws SQLException {
		throw unsupported("update");
	}

	@Override
	public void updateClob(String columnLabel, Clob x) throws SQLException {
		throw unsupported("update");
	}

	@Override
	public void updateArray(int columnIndex, Array x) throws SQLException {
		throw unsupported("update");
	}

	@Override
	public void updateArray(String columnLabel, Array x) throws SQLException {
		throw unsupported("update");
	}

	@Override
	public RowId getRowId(int columnIndex) throws SQLException {
		throw unsupported("getRowId");
	}

	@Override
	public RowId getRowId(String columnLabel) throws SQLException {
		throw unsupported("getRowId");
	}

	@Override
	public void updateRowId(int columnIndex, RowId x) throws SQLException {
		throw unsupported("update");
	}

	@Override
	public void updateRowId(String columnLabel, RowId x) throws SQLException {
		throw unsupported("update");
	}

	@Override
	public int getHoldability() {
		return HOLD_CURSORS_OVER_COMMIT;
	}

	@Override
	public boolean isClosed() {
		return closed;
	}

	@Override
	public void updateNString(int columnIndex, String nString) throws SQLException {
		updateString(columnIndex, nString);
	}

	@Override
	public void updateNString(String columnLabel, String nString) throws SQLException {
		updateString(columnLabel, nString);
	}

	@Override
	public void updateNClob(int columnIndex, NClob nClob) throws SQLException {
		throw unsupported("update");
	}

	@Override
	public void updateNClob(String columnLabel, NClob nClob) throws SQLException {
		throw unsupported("update");
	}

	@Override
	public NClob getNClob(int columnIndex) throws SQLException {
		throw unsupported("getNClob");
	}

	@Override
	public NClob getNClob(String columnLabel) throws SQLException {
		throw unsupported("getNClob");
	}

	@Override
	public SQLXML getSQLXML(int columnIndex) throws SQLException {
		throw unsupported("getSQLXML");
	}

	@Override
	public SQLXML getSQLXML(String columnLabel) throws SQLException {
		throw unsupported("getSQLXML");
	}

	@Override
	public void updateSQLXML(int columnIndex, SQLXML xmlObject) throws SQLException {
		throw unsupported("update");
	}

	@Override
	public void updateSQLXML(String columnLabel, SQLXML xmlObject) throws SQLException {
		throw unsupported("update");
	}

	@Override
	public String getNString(int columnIndex) throws SQLException {
		return getString(columnIndex);
	}

	@Override
	public String getNString(String columnLabel) throws SQLException {
		return getString(columnLabel);
	}

	@Override
	public Reader getNCharacterStream(int columnIndex) throws SQLException {
		throw unsupported("getNCharacterStream");
	}

	@Override
	public Reader getNCharacterStream(String columnLabel) throws SQLException {
		throw unsupported("getNCharacterStream");
	}

	@Override
	public void updateNCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
		throw unsupported("update");
	}

	@Override
	public void updateNCharacterStream(String columnLabel, Reader reader, long length) throws SQLException {
		throw unsupported("update");
	}

	@Override
	public void updateAsciiStream(int columnIndex, InputStream x, long length) throws SQLException {
		throw unsupported("update");
	}

	@Override
	public void updateBinaryStream(int columnIndex, InputStream x, long length) throws SQLException {
		throw unsupported("update");
	}

	@Override
	public void updateCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
		throw unsupported("update");
	}

	@Override
	public void updateAsciiStream(String columnLabel, InputStream x, long length) throws SQLException {
		throw unsupported("update");
	}

	@Override
	public void updateBinaryStream(String columnLabel, InputStream x, long length) throws SQLException {
		throw unsupported("update");
	}

	@Override
	public void updateCharacterStream(String columnLabel, Reader reader, long length) throws SQLException {
		throw unsupported("update");
	}

	@Override
	public void updateBlob(int columnIndex, InputStream inputStream, long length) throws SQLException {
		throw unsupported("update");
	}

	@Override
	public void updateBlob(String columnLabel, InputStream inputStream, long length) throws SQLException {
		throw unsupported("update");
	}

	@Override
	public void updateClob(int columnIndex, Reader reader, long length) throws SQLException {
		throw unsupported("update");
	}

	@Override
	public void updateClob(String columnLabel, Reader reader, long length) throws SQLException {
		throw unsupported("update");
	}

	@Override
	public void updateNClob(int columnIndex, Reader reader, long length) throws SQLException {
		throw unsupported("update");
	}

	@Override
	public void updateNClob(String columnLabel, Reader reader, long length) throws SQLException {
		throw unsupported("update");
	}

	@Override
	public void updateNCharacterStream(int columnIndex, Reader x) throws SQLException {
		throw unsupported("update");
	}

	@Override
	public void updateNCharacterStream(String columnLabel, Reader reader) throws SQLException {
		throw unsupported("update");
	}

	@Override
	public void updateAsciiStream(int columnIndex, InputStream x) throws SQLException {
		throw unsupported("update");
	}

	@Override
	public void updateBinaryStream(int columnIndex, InputStream x) throws SQLException {
		throw unsupported("update");
	}

	@Override
	public void updateCharacterStream(int columnIndex, Reader x) throws SQLException {
		throw unsupported("update");
	}

	@Override
	public void updateAsciiStream(String columnLabel, InputStream x) throws SQLException {
		throw unsupported("update");
	}

	@Override
	public void updateBinaryStream(String columnLabel, InputStream x) throws SQLException {
		throw unsupported("update");
	}

	@Override
	public void updateCharacterStream(String columnLabel, Reader reader) throws SQLException {
		throw unsupported("update");
	}

	@Override
	public void updateBlob(int columnIndex, InputStream inputStream) throws SQLException {
		throw unsupported("update");
	}

	@Override
	public void updateBlob(String columnLabel, InputStream inputStream) throws SQLException {
		throw unsupported("update");
	}

	@Override
	public void updateClob(int columnIndex, Reader reader) throws SQLException {
		throw unsupported("update");
	}

	@Override
	public void updateClob(String columnLabel, Reader reader) throws SQLException {
		throw unsupported("update");
	}

	@Override
	public void updateNClob(int columnIndex, Reader reader) throws SQLException {
		throw unsupported("update");
	}

	@Override
	public void updateNClob(String columnLabel, Reader reader) throws SQLException {
		throw unsupported("update");
	}

	@Override
	public <T> T getObject(int columnIndex, Class<T> type) throws SQLException {
		final Object v = getObject(columnIndex);
		if (v == null) {
			return null;
		}
		if (type == UUID.class) {
			return type.cast(SqlTypeCoercion.toUuid(v));
		}
		if (type == LocalDate.class) {
			return type.cast(SqlTypeCoercion.toDate(v));
		}
		if (type == LocalTime.class) {
			return type.cast(SqlTypeCoercion.toTime(v));
		}
		if (type == Instant.class) {
			return type.cast(SqlTypeCoercion.toTimestamptz(v, ZoneOffset.UTC));
		}
		if (type.isInstance(v)) {
			return type.cast(v);
		}
		return type.cast(v);
	}

	@Override
	public <T> T getObject(String columnLabel, Class<T> type) throws SQLException {
		final Object v = getObject(columnLabel);
		if (v == null) {
			return null;
		}
		return type.cast(v);
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

	private void bufferPut(int columnIndex, Object value) throws SQLException {
		ensureOpen();
		ensureUpdatable();
		final int idx = columnIndex - 1;
		ensureColumnIndex(idx, columnIndex);
		if (onInsertRow) {
			insertBuffer[idx] = value;
			insertFlags[idx] = true;
		} else {
			ensureOnDataRow();
			updateBuffer[idx] = value;
			updateFlags[idx] = true;
		}
	}

	private void clearUpdates() {
		Arrays.fill(updateBuffer, null);
		Arrays.fill(updateFlags, false);
	}

	private void clearInsert() {
		Arrays.fill(insertBuffer, null);
		Arrays.fill(insertFlags, false);
	}

	private void ensureOpen() throws SQLException {
		if (closed) {
			throw new SQLException(MSG_CLOSED, SQLSTATE_GENERAL);
		}
	}

	private void ensureOnRow() throws SQLException {
		if (onInsertRow) {
			throw new SQLException(MSG_ON_INSERT, SQLSTATE_GENERAL);
		}
		if (cursor < 0) {
			throw new SQLException(MSG_BEFORE, SQLSTATE_GENERAL);
		}
		if (cursor >= rows.size()) {
			throw new SQLException(MSG_AFTER, SQLSTATE_GENERAL);
		}
	}

	private void ensureOnDataRow() throws SQLException {
		if (onInsertRow) {
			throw new SQLException(MSG_ON_INSERT, SQLSTATE_GENERAL);
		}
		if (cursor < 0 || cursor >= rows.size()) {
			throw new SQLException(MSG_NOT_ON_ROW, SQLSTATE_GENERAL);
		}
	}

	private void ensureNotInsertRow() throws SQLException {
		if (onInsertRow) {
			throw new SQLException(MSG_ON_INSERT, SQLSTATE_GENERAL);
		}
	}

	private void ensureUpdatable() throws SQLException {
		if (concurrency != CONCUR_UPDATABLE || edit == null || edit.pkIndex() < 0) {
			throw new SQLException(edit == null || edit.pkIndex() < 0 ? MSG_NO_PK : MSG_READ_ONLY, SQLSTATE_GENERAL);
		}
	}

	private void ensureColumnIndex(int idx, int columnIndex) throws SQLException {
		if (idx < 0 || idx >= metadata.getColumnCount()) {
			throw new SQLException(MSG_INVALID_COLUMN + columnIndex, SQLSTATE_GENERAL);
		}
	}

	static String sqlLiteral(Object value) {
        switch (value) {
            case null -> {
                return SQL_NULL;
            }
            case Number _, Boolean _ -> {
                return String.valueOf(value);
            }
            case byte[] bytes -> {
                final StringBuilder sb = new StringBuilder(bytes.length * 2 + 3);
                sb.append(HEX_PREFIX_A).append(HEX_PREFIX_B);
                for (byte b : bytes) {
                    sb.append(String.format("%02X", b));
                }
                sb.append(SQL_QUOTE);
                return sb.toString();
            }
            default -> {
            }
        }
        final String s = value instanceof Date || value instanceof Timestamp
				? value.toString()
				: String.valueOf(value);
		return SQL_QUOTE + s.replace("'", "''") + SQL_QUOTE;
	}

	private static SQLFeatureNotSupportedException unsupported(String method) {
		return new SQLFeatureNotSupportedException(method + " not supported");
	}
}
