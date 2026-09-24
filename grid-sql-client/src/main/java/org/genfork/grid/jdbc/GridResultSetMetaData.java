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

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;

import org.genfork.grid.catalog.SqlType;
import org.genfork.grid.jdbc.GridJdbcCatalogSupport.GridColumnHints;
import org.genfork.grid.jdbc.GridJdbcCatalogSupport.Hint;
import org.genfork.grid.sql.client.RowMetadata;

/**
 * {@link ResultSetMetaData} from SPI {@link RowMetadata}, optionally enriched from catalog hints.
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
public final class GridResultSetMetaData implements ResultSetMetaData {
	private final RowMetadata metadata;
	private final GridColumnHints hints;
	private final String schema;
	private final String table;

	GridResultSetMetaData(RowMetadata metadata, GridColumnHints hints, String schema, String table) {
		this.metadata = metadata;
		this.hints = hints;
		this.schema = schema == null ? "" : schema;
		this.table = table == null ? "" : table;
	}

	@Override
	public int getColumnCount() {
		return metadata.getColumnCount();
	}

	@Override
	public boolean isAutoIncrement(int column) {
		return false;
	}

	@Override
	public boolean isCaseSensitive(int column) {
		return true;
	}

	@Override
	public boolean isSearchable(int column) {
		return true;
	}

	@Override
	public boolean isCurrency(int column) {
		return false;
	}

	@Override
	public int isNullable(int column) {
		final Boolean spi = metadata.getNullable(column - 1);
		if (spi != null) {
			return spi ? columnNullable : columnNoNulls;
		}

		final Hint h = hint(column);
		if (h == null) {
			return columnNullableUnknown;
		}
		return h.nullable() ? columnNullable : columnNoNulls;
	}

	@Override
	public boolean isSigned(int column) throws SQLException {
		final int t = getColumnType(column);
		return t == Types.INTEGER || t == Types.BIGINT || t == Types.DOUBLE;
	}

	@Override
	public int getColumnDisplaySize(int column) {
		final int p = getPrecision(column);
		return p > 0 ? p : 64;
	}

	@Override
	public String getColumnLabel(int column) {
		return getColumnName(column);
	}

	@Override
	public String getColumnName(int column) {
		return metadata.getColumnNames().get(column - 1);
	}

	@Override
	public String getSchemaName(int column) {
		final String spi = metadata.getSchemaName(column - 1);
		if (spi != null && !spi.isEmpty()) {
			return spi;
		}
		final Hint h = hint(column);
		if (h != null) {
			return h.schema();
		}
		return schema;
	}

	@Override
	public int getPrecision(int column) {
		return metadata.getPrecision(column - 1);
	}

	@Override
	public int getScale(int column) {
		return GridJdbcTypeSupport.scaleFor(metadata.getColumnType(column - 1));
	}

	@Override
	public String getTableName(int column) {
		final String spi = metadata.getTableName(column - 1);
		if (spi != null && !spi.isEmpty()) {
			return spi;
		}
		final Hint h = hint(column);
		if (h != null) {
			return h.table();
		}
		return table;
	}

	@Override
	public String getCatalogName(int column) {
		return GridDatabaseMetaData.CATALOG_NAME;
	}

	@Override
	public int getColumnType(int column) {
		final Hint h = hint(column);
		final SqlType t = h != null ? h.type() : metadata.getColumnType(column - 1);
		return GridJdbcTypeSupport.toJdbcType(t);
	}

	@Override
	public String getColumnTypeName(int column) {
		final Hint h = hint(column);
		final SqlType t = h != null ? h.type() : metadata.getColumnType(column - 1);
		return GridJdbcTypeSupport.typeName(t);
	}

	@Override
	public boolean isReadOnly(int column) {
		return false;
	}

	@Override
	public boolean isWritable(int column) {
		return true;
	}

	@Override
	public boolean isDefinitelyWritable(int column) {
		return hints != null;
	}

	@Override
	public String getColumnClassName(int column) {
		final Hint h = hint(column);
		final SqlType t = h != null ? h.type() : metadata.getColumnType(column - 1);
		return GridJdbcTypeSupport.columnClassName(t);
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

	private Hint hint(int column) {
		if (hints == null) {
			return null;
		}
		return hints.hint(getColumnName(column));
	}
}
