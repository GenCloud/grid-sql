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

import java.sql.Types;

import org.genfork.grid.catalog.SqlType;
import org.genfork.grid.sql.ColumnMetas;

/**
 * Tooling helpers: map {@link SqlType} to JDBC {@link Types} / type names / precision.
 * <p>
 * Lives in {@code org.genfork.grid.jdbc} (DBeaver/IDE tooling) — not on the product SQL path.
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
public final class GridJdbcTypeSupport {
	private static final String TYPE_NAME_OTHER = "OTHER";
	private static final String TYPE_NAME_BYTEA = "BYTEA";
	private static final int SCALE_DOUBLE = 10;

	private GridJdbcTypeSupport() {
	}

	/**
	 * JDBC {@link Types} code for a catalog {@link SqlType}.
	 */
	public static int toJdbcType(SqlType type) {
		if (type == null) {
			return Types.OTHER;
		}
		return switch (type) {
			case INT -> Types.INTEGER;
			case BIGINT -> Types.BIGINT;
			case DOUBLE -> Types.DOUBLE;
			case BOOLEAN -> Types.BOOLEAN;
			case VARCHAR -> Types.VARCHAR;
			case TIMESTAMP -> Types.TIMESTAMP;
			case BYTES -> Types.VARBINARY;
			case UUID -> Types.OTHER;
			case DATE -> Types.DATE;
			case TIME -> Types.TIME;
			case TIMESTAMPTZ -> Types.TIMESTAMP_WITH_TIMEZONE;
		};
	}

	/**
	 * JDBC type name for tooling ({@code BYTEA} for bytes; otherwise enum name).
	 */
	public static String typeName(SqlType type) {
		if (type == null) {
			return TYPE_NAME_OTHER;
		}
		if (type == SqlType.BYTES) {
			return TYPE_NAME_BYTEA;
		}
		return type.name();
	}

	/**
	 * Java class name for {@link java.sql.ResultSetMetaData#getColumnClassName(int)}.
	 */
	public static String columnClassName(SqlType type) {
		return type == null ? Object.class.getName() : type.javaType().getName();
	}

	/**
	 * Default JDBC scale (only {@link SqlType#DOUBLE} is non-zero).
	 */
	public static int scaleFor(SqlType type) {
		return type == SqlType.DOUBLE ? SCALE_DOUBLE : 0;
	}

	/**
	 * Default display / JDBC precision for a SQL type.
	 */
	public static int precisionFor(SqlType type) {
		return ColumnMetas.precision(type);
	}

	/**
	 * Parse an information_schema / DDL type token; unknown → {@link SqlType#VARCHAR}.
	 */
	public static SqlType parseSqlType(String name) {
		if (name == null || name.isBlank()) {
			return SqlType.VARCHAR;
		}
		try {
			return SqlType.fromToken(name);
		} catch (IllegalArgumentException e) {
			return SqlType.VARCHAR;
		}
	}
}