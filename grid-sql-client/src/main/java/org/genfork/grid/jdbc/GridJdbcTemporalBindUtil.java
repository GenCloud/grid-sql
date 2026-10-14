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

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Calendar;
import java.util.Objects;

import org.genfork.grid.catalog.SqlTypeCoercion;

/**
 * Normalize JDBC temporal binds for Grid wire ({@code TIMESTAMP} local vs {@code TIMESTAMPTZ} Instant).
 * <p>
 * Coercion string/zone rules reuse {@link SqlTypeCoercion} (no duplicate temporal parsers).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
public final class GridJdbcTemporalBindUtil {
	private static final String ERR_TIMESTAMP = "cannot coerce ";
	private static final String ERR_TO_TIMESTAMP = " to TIMESTAMP";
	private static final String ERR_TO_TIMESTAMPTZ = " to TIMESTAMPTZ";
	private static final String ERR_TO_DATE = " to DATE";
	private static final String ERR_TO_TIME = " to TIME";

	private GridJdbcTemporalBindUtil() {
	}

	/**
	 * JDBC {@code setTimestamp} without Calendar — zone-less local date-time for {@code TIMESTAMP}.
	 */
	public static LocalDateTime fromTimestamp(Timestamp timestamp) {
		if (timestamp == null) {
			return null;
		}
		return timestamp.toLocalDateTime();
	}

	/**
	 * JDBC {@code setTimestamp} with Calendar — wall-clock {@link LocalDateTime} in the calendar zone.
	 */
	public static LocalDateTime fromTimestamp(Timestamp timestamp, Calendar calendar) {
		if (timestamp == null) {
			return null;
		}
		if (calendar == null) {
			return timestamp.toLocalDateTime();
		}
		return LocalDateTime.ofInstant(timestamp.toInstant(), calendar.getTimeZone().toZoneId());
	}

	/**
	 * Normalize {@code setObject} value, optionally honoring JDBC {@code targetSqlType}.
	 */
	public static Object normalizeObject(Object value, int targetSqlType) {
		if (value == null) {
			return null;
		}
		return switch (targetSqlType) {
			case Types.TIMESTAMP -> toLocalDateTime(value);
			case Types.TIMESTAMP_WITH_TIMEZONE -> toInstant(value);
			case Types.DATE -> toLocalDate(value);
			case Types.TIME -> toLocalTime(value);
			default -> normalizeUntyped(value);
		};
	}

	/**
	 * Normalize {@code setObject} without target type — preserve TIMESTAMP vs TIMESTAMPTZ intent by runtime type.
	 */
	public static Object normalizeObject(Object value) {
		if (value == null) {
			return null;
		}
		return normalizeUntyped(value);
	}

	private static Object normalizeUntyped(Object value) {
		if (value instanceof Timestamp ts) {
			return ts.toLocalDateTime();
		}
		if (value instanceof Date d) {
			return d.toLocalDate();
		}
		if (value instanceof Time t) {
			return t.toLocalTime();
		}
		if (value instanceof LocalDateTime
				|| value instanceof Instant
				|| value instanceof LocalDate
				|| value instanceof LocalTime) {
			return value;
		}
		if (value instanceof OffsetDateTime odt) {
			return odt.toInstant();
		}
		if (value instanceof ZonedDateTime zdt) {
			return zdt.toInstant();
		}
		return value;
	}

	private static LocalDateTime toLocalDateTime(Object value) {
		Objects.requireNonNull(value, "value");
		try {
			return Objects.requireNonNull(SqlTypeCoercion.toLocalDateTime(value));
		} catch (RuntimeException ex) {
			throw new IllegalArgumentException(
					ERR_TIMESTAMP + value.getClass().getSimpleName() + ERR_TO_TIMESTAMP, ex);
		}
	}

	private static Instant toInstant(Object value) {
		Objects.requireNonNull(value, "value");
		try {
			return SqlTypeCoercion.toTimestamptz(value, ZoneOffset.UTC);
		} catch (RuntimeException ex) {
			throw new IllegalArgumentException(
					ERR_TIMESTAMP + value.getClass().getSimpleName() + ERR_TO_TIMESTAMPTZ, ex);
		}
	}

	private static LocalDate toLocalDate(Object value) {
		Objects.requireNonNull(value, "value");
		try {
			return SqlTypeCoercion.toDate(value);
		} catch (RuntimeException ex) {
			throw new IllegalArgumentException(
					ERR_TIMESTAMP + value.getClass().getSimpleName() + ERR_TO_DATE, ex);
		}
	}

	private static LocalTime toLocalTime(Object value) {
		Objects.requireNonNull(value, "value");
		try {
			return SqlTypeCoercion.toTime(value);
		} catch (RuntimeException ex) {
			throw new IllegalArgumentException(
					ERR_TIMESTAMP + value.getClass().getSimpleName() + ERR_TO_TIME, ex);
		}
	}
}
