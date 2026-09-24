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
package org.genfork.grid.jooq;

import java.sql.Date;
import java.sql.Time;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

import org.genfork.grid.catalog.SqlType;
import org.jooq.DataType;
import org.jooq.impl.SQLDataType;

/**
 * Maps Grid catalog {@link SqlType} ↔ jOOQ {@link DataType} at the SPI / DDL edge.
 * <p>
 * Object / boxed values belong only at SPI / Result boundaries — not mid-pipeline wire paths.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
public final class GridSqlTypes {
	private static final String ERR_NULL_SQL_TYPE = "sqlType";
	private static final String ERR_NULL_DATA_TYPE = "dataType";
	private static final String ERR_UNSUPPORTED_DATA_TYPE = "Unsupported jOOQ DataType for Grid SqlType: ";

	private GridSqlTypes() {
	}

	/**
	 * jOOQ {@link DataType} for a catalog {@link SqlType}.
	 * <p>
	 * {@link SqlType#TIMESTAMP} maps to VARCHAR (ISO-8601 string on the Grid wire).
	 */
	public static DataType<?> toDataType(SqlType sqlType) {
		Objects.requireNonNull(sqlType, ERR_NULL_SQL_TYPE);
		return switch (sqlType) {
			case INT -> SQLDataType.INTEGER;
			case BIGINT -> SQLDataType.BIGINT;
			case DOUBLE -> SQLDataType.DOUBLE;
			case BOOLEAN -> SQLDataType.BOOLEAN;
			case VARCHAR -> SQLDataType.VARCHAR;
			case TIMESTAMP -> SQLDataType.VARCHAR;
			case BYTES -> SQLDataType.VARBINARY;
			case UUID -> SQLDataType.UUID;
			case DATE -> SQLDataType.LOCALDATE;
			case TIME -> SQLDataType.LOCALTIME;
			case TIMESTAMPTZ -> SQLDataType.INSTANT;
		};
	}

	/**
	 * Best-effort reverse map from jOOQ {@link DataType} to {@link SqlType}.
	 * <p>
	 * Unrecognised types throw {@link IllegalArgumentException}.
	 */
	public static SqlType toSqlType(DataType<?> dataType) {
		Objects.requireNonNull(dataType, ERR_NULL_DATA_TYPE);
		final DataType<?> root = dataType.getSQLDataType() == null ? dataType : dataType.getSQLDataType();
		final Class<?> javaType = root.getType();
		if (javaType == null) {
			throw new IllegalArgumentException(ERR_UNSUPPORTED_DATA_TYPE + dataType);
		}
		if (Integer.class.equals(javaType) || int.class.equals(javaType)) {
			return SqlType.INT;
		}
		if (Long.class.equals(javaType) || long.class.equals(javaType)) {
			return SqlType.BIGINT;
		}
		if (Double.class.equals(javaType) || double.class.equals(javaType)
				|| Float.class.equals(javaType) || float.class.equals(javaType)) {
			return SqlType.DOUBLE;
		}
		if (Boolean.class.equals(javaType) || boolean.class.equals(javaType)) {
			return SqlType.BOOLEAN;
		}
		if (byte[].class.equals(javaType)) {
			return SqlType.BYTES;
		}
		if (UUID.class.equals(javaType)) {
			return SqlType.UUID;
		}
		if (LocalDate.class.equals(javaType) || Date.class.equals(javaType)) {
			return SqlType.DATE;
		}
		if (LocalTime.class.equals(javaType) || Time.class.equals(javaType)) {
			return SqlType.TIME;
		}
		if (Instant.class.equals(javaType) || OffsetDateTime.class.equals(javaType)) {
			return SqlType.TIMESTAMPTZ;
		}
		if (String.class.equals(javaType)) {
			return SqlType.VARCHAR;
		}
		throw new IllegalArgumentException(ERR_UNSUPPORTED_DATA_TYPE + dataType + " / " + javaType.getName());
	}
}
