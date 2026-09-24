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

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.UUID;

/**
 * Edge converters for UUID / temporal SQL types (bind + result).
 * <p>
 * Internal storage for {@link SqlType#TIMESTAMPTZ} is UTC {@link Instant} bytes;
 * zone-less strings are interpreted with the session / connection {@link ZoneId}.
 *
 * Lives in {@code grid-commons} (shared client/server; JDK-only).
 *
 * @author: GenCloud
 * @date: 2025/07
 * @since: 1.0
 */
public final class SqlTypeCoercion {
	private static final DateTimeFormatter TS = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
	private static final char SPACE = ' ';
	private static final char ISO_T = 'T';

	private SqlTypeCoercion() {
	}

	public static ZoneId zoneOrUtc(ZoneId zone) {
		return zone == null ? ZoneOffset.UTC : zone;
	}

	public static UUID toUuid(Object raw) {
        return switch (raw) {
            case null -> null;
            case UUID u -> u;
            case byte[] b -> uuidFromBytes(b);
            default -> UUID.fromString(String.valueOf(raw).trim());
        };
    }

	public static LocalDate toDate(Object raw) {
        switch (raw) {
            case null -> {
                return null;
            }
            case LocalDate d -> {
                return d;
            }
            case java.sql.Date d -> {
                return d.toLocalDate();
            }
            case LocalDateTime ldt -> {
                return ldt.toLocalDate();
            }
            case Instant i -> {
                return LocalDate.ofInstant(i, ZoneOffset.UTC);
            }
            default -> {
            }
        }
        final String s = normalizeTemporalString(String.valueOf(raw));
		if (s.length() >= 10 && s.charAt(4) == '-') {
			return LocalDate.parse(s.substring(0, 10));
		}
		return LocalDate.parse(s);
	}

	public static LocalTime toTime(Object raw) {
        switch (raw) {
            case null -> {
                return null;
            }
            case LocalTime t -> {
                return t;
            }
            case java.sql.Time t -> {
                return t.toLocalTime();
            }
            case LocalDateTime ldt -> {
                return ldt.toLocalTime();
            }
            default -> {
            }
        }
        final String s = normalizeTemporalString(String.valueOf(raw));
		return LocalTime.parse(s);
	}

	/**
	 * Coerce to UTC {@link Instant} for {@link SqlType#TIMESTAMPTZ}.
	 * Zone-less local date-times use {@code zone}.
	 */
	public static Instant toTimestamptz(Object raw, ZoneId zone) {
        switch (raw) {
            case null -> {
                return null;
            }
            case Instant i -> {
                return i;
            }
            case OffsetDateTime odt -> {
                return odt.toInstant();
            }
            case ZonedDateTime zdt -> {
                return zdt.toInstant();
            }
            case Timestamp ts -> {
                return ts.toInstant();
            }
            case LocalDateTime ldt -> {
                return ldt.atZone(zoneOrUtc(zone)).toInstant();
            }
            case LocalDate d -> {
                return d.atStartOfDay(zoneOrUtc(zone)).toInstant();
            }
            default -> {
            }
        }
        final String s = normalizeTemporalString(String.valueOf(raw));
		try {
			return Instant.parse(s);
		} catch (DateTimeParseException ignored) {
			// fall through
		}
		try {
			return OffsetDateTime.parse(s).toInstant();
		} catch (DateTimeParseException ignored) {
			// fall through
		}
		try {
			return ZonedDateTime.parse(s).toInstant();
		} catch (DateTimeParseException ignored) {
			// fall through
		}
		final LocalDateTime ldt = LocalDateTime.parse(s, TS);
		return ldt.atZone(zoneOrUtc(zone)).toInstant();
	}

	/** Legacy {@link SqlType#TIMESTAMP} string (ISO local date-time). */
	public static String toTimestampString(Object raw) {
        return switch (raw) {
            case null -> null;
            case LocalDateTime ldt -> ldt.format(TS);
            case Instant i -> LocalDateTime.ofInstant(i, ZoneOffset.UTC).format(TS);
            case Timestamp ts -> ts.toLocalDateTime().format(TS);
            default -> normalizeTemporalString(String.valueOf(raw));
        };
    }

	public static byte[] uuidToBytes(UUID uuid) {
		Objects.requireNonNull(uuid, "uuid");
		final byte[] out = new byte[SqlTypeWireSizes.UUID_BYTES];
		writeUuidBytes(out, 0, uuid);
		return out;
	}

	/**
	 * Write UUID as 16 big-endian bytes into {@code dest} at {@code off} (no intermediate array).
	 *
	 * @return {@code off + }{@link SqlTypeWireSizes#UUID_BYTES}
	 */
	public static int writeUuidBytes(byte[] dest, int off, UUID uuid) {
		Objects.requireNonNull(dest, "dest");
		Objects.requireNonNull(uuid, "uuid");
		putLongBe(dest, off, uuid.getMostSignificantBits());
		putLongBe(dest, off + Long.BYTES, uuid.getLeastSignificantBits());
		return off + SqlTypeWireSizes.UUID_BYTES;
	}

	public static UUID uuidFromBytes(byte[] bytes) {
		Objects.requireNonNull(bytes, "bytes");
		if (bytes.length != SqlTypeWireSizes.UUID_BYTES) {
			throw new IllegalArgumentException(
					"UUID wire length " + bytes.length + " != " + SqlTypeWireSizes.UUID_BYTES);
		}
		final long msb = getLongBe(bytes, 0);
		final long lsb = getLongBe(bytes, Long.BYTES);
		return new UUID(msb, lsb);
	}

	private static String normalizeTemporalString(String raw) {
		final String trimmed = raw.trim();
		if (trimmed.indexOf(SPACE) > 0 && trimmed.indexOf(ISO_T) < 0) {
			return trimmed.replace(SPACE, ISO_T);
		}
		return trimmed;
	}

	private static void putLongBe(byte[] buf, int off, long v) {
		buf[off] = (byte) (v >>> 56);
		buf[off + 1] = (byte) (v >>> 48);
		buf[off + 2] = (byte) (v >>> 40);
		buf[off + 3] = (byte) (v >>> 32);
		buf[off + 4] = (byte) (v >>> 24);
		buf[off + 5] = (byte) (v >>> 16);
		buf[off + 6] = (byte) (v >>> 8);
		buf[off + 7] = (byte) v;
	}

	private static long getLongBe(byte[] buf, int off) {
		return ((long) buf[off] & 0xffL) << 56
				| ((long) buf[off + 1] & 0xffL) << 48
				| ((long) buf[off + 2] & 0xffL) << 40
				| ((long) buf[off + 3] & 0xffL) << 32
				| ((long) buf[off + 4] & 0xffL) << 24
				| ((long) buf[off + 5] & 0xffL) << 16
				| ((long) buf[off + 6] & 0xffL) << 8
				| ((long) buf[off + 7] & 0xffL);
	}
}
