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
package org.genfork.grid.sql;

import org.genfork.grid.catalog.SqlType;
import org.genfork.grid.catalog.SqlTypeWireSizes;

/**
 * Named precision defaults and helpers for {@link SqlResult.ColumnMeta} / JDBC metadata.
 *
 * Lives in {@code grid-commons} (shared client/server; JDK-only).
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class ColumnMetas {
	public static final int PRECISION_INT = 10;
	public static final int PRECISION_BIGINT = 19;
	public static final int PRECISION_DOUBLE = 17;
	public static final int PRECISION_BOOLEAN = 1;
	public static final int PRECISION_VARCHAR = 255;
	public static final int PRECISION_BYTES = 0;
	public static final int PRECISION_TIMESTAMP = 255;
	public static final int PRECISION_UUID = SqlTypeWireSizes.UUID_BYTES;
	public static final int PRECISION_DATE = 10;
	public static final int PRECISION_TIME = 15;
	public static final int PRECISION_TIMESTAMPTZ = 35;

	private ColumnMetas() {
	}

	/**
	 * Default display / JDBC precision for a SQL type.
	 */
	public static int precision(SqlType type) {
		if (type == null) {
			return PRECISION_BYTES;
		}
		return switch (type) {
			case INT -> PRECISION_INT;
			case BIGINT -> PRECISION_BIGINT;
			case DOUBLE -> PRECISION_DOUBLE;
			case BOOLEAN -> PRECISION_BOOLEAN;
			case VARCHAR -> PRECISION_VARCHAR;
			case TIMESTAMP -> PRECISION_TIMESTAMP;
			case BYTES -> PRECISION_BYTES;
			case UUID -> PRECISION_UUID;
			case DATE -> PRECISION_DATE;
			case TIME -> PRECISION_TIME;
			case TIMESTAMPTZ -> PRECISION_TIMESTAMPTZ;
		};
	}
}
