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
package org.genfork.grid.catalog;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Wire / SQL scalar types for catalog columns (no Java domain class).
 * <p>
 * {@link #TIMESTAMP} stays ISO-8601 {@link String} on the wire for sealed-row
 * compatibility. Prefer {@link #TIMESTAMPTZ} ({@link Instant} UTC bytes),
 * {@link #DATE}, and {@link #TIME} for new binary temporal columns.
 * {@link #UUID} is a fixed 16-byte payload (not a string mid-pipeline).
 *
 * Lives in {@code grid-commons} (shared client/server; JDK-only).
 *
 * @author: GenCloud
 * @date: 2025/07
 * @since: 1.0
 */
public enum SqlType {
	INT(Integer.class),
	BIGINT(Long.class),
	DOUBLE(Double.class),
	BOOLEAN(Boolean.class),
	VARCHAR(String.class),
	TIMESTAMP(String.class),
	BYTES(byte[].class),
	UUID(UUID.class),
	DATE(LocalDate.class),
	TIME(LocalTime.class),
	TIMESTAMPTZ(Instant.class);

	private final Class<?> javaType;

	SqlType(Class<?> javaType) {
		this.javaType = javaType;
	}

	public Class<?> javaType() {
		return javaType;
	}

	public static SqlType fromToken(String token) {
		if (token == null || token.isBlank()) {
			throw new IllegalArgumentException("SQL type token empty");
		}
		final String t = token.trim().toUpperCase();
		if ("JSONB".equals(t)) {
			throw new IllegalArgumentException("Unsupported SQL type JSONB; use JSON for opaque UTF-8 text");
		}
		return switch (t) {
			case "INT", "INTEGER" -> INT;
			case "BIGINT", "LONG" -> BIGINT;
			case "DOUBLE", "FLOAT", "REAL" -> DOUBLE;
			case "BOOLEAN", "BOOL" -> BOOLEAN;
			case "VARCHAR", "STRING", "TEXT", "JSON" -> VARCHAR;
			case "TIMESTAMP", "DATETIME" -> TIMESTAMP;
			case "BYTES", "BYTEA", "BINARY", "BLOB" -> BYTES;
			case "UUID" -> UUID;
			case "DATE" -> DATE;
			case "TIME" -> TIME;
			case "TIMESTAMPTZ", "TIMESTAMP WITH TIME ZONE" -> TIMESTAMPTZ;
			default -> throw new IllegalArgumentException("Unsupported SQL type: " + token);
		};
	}
}
