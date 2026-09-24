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
package org.genfork.grid.serial;

import jodd.util.Bits;
import org.genfork.grid.catalog.ColumnDef;
import org.genfork.grid.catalog.SqlType;
import org.genfork.grid.catalog.SqlTypeCoercion;
import org.genfork.grid.catalog.SqlTypeWireSizes;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.codec.duplex.DuplexCodecSupport;
import org.genfork.grid.utils.SerialUtil;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * INSERT/UPSERT encoder: heap literals → logical (or duplex) row {@code byte[]}.
 * Client/SELECT projection must use {@link LogicalFieldCursor#project} on stored bytes —
 * do not round-trip full rows through {@link #decode} on the hot path.
 * <p>
 * {@link SqlType#TIMESTAMP} remains ISO-8601 {@link String} wire; {@link SqlType#UUID},
 * {@link SqlType#DATE}, {@link SqlType#TIME}, {@link SqlType#TIMESTAMPTZ} use fixed binary.
 *
 * @author: GenCloud
 * @date: 2025/06
 * @since: 1.0
 */
public final class RowEncoder {
	private static final int STARTS_INITIAL = 16;
	private static final ThreadLocal<int[]> STARTS_SCRATCH =
			ThreadLocal.withInitial(() -> new int[STARTS_INITIAL]);
	/** Per-field payload sizes from the size pass (reused so VARCHAR skips a second UTF-8 length scan). */
	private static final ThreadLocal<int[]> PAYLOAD_SCRATCH =
			ThreadLocal.withInitial(() -> new int[STARTS_INITIAL]);

	private RowEncoder() {
	}

	public static byte[] encode(TableSchema schema, Object[] values) {
		Objects.requireNonNull(schema, "schema");
		Objects.requireNonNull(values, "values");
		final FieldMetaData[] fields = schema.fieldMetas();
		if (values.length != fields.length) {
			throw new IllegalArgumentException(
					"value count " + values.length + " != columns " + fields.length);
		}
		final int fieldCount = fields.length;
		int[] starts = STARTS_SCRATCH.get();
		int[] payloads = PAYLOAD_SCRATCH.get();
		if (starts.length < fieldCount) {
			starts = new int[fieldCount];
			STARTS_SCRATCH.set(starts);
		}
		if (payloads.length < fieldCount) {
			payloads = new int[fieldCount];
			PAYLOAD_SCRATCH.set(payloads);
		}
		// Exact wire size (utf8ByteLength, not budget) → one owned alloc, no scratch copy.
		int need = fieldCount * Integer.BYTES + Integer.BYTES;
		for (int i = 0; i < fieldCount; i++) {
			final int payload = estimateBytes(fields[i].getType(), values[i]);
			payloads[i] = payload;
			need += payload;
		}
		final byte[] buf = new byte[need];
		int pos = 0;
		for (int i = 0; i < fieldCount; i++) {
			starts[i] = pos;
			pos = writeValue(buf, pos, fields[i].getType(), values[i], payloads[i]);
		}
		for (int i = 0; i < fieldCount; i++) {
			Bits.putInt(buf, pos, starts[i]);
			pos += Integer.BYTES;
		}
		Bits.putInt(buf, pos, fieldCount * Integer.BYTES);
		pos += Integer.BYTES;
		final byte[] logical = pos == need ? buf : Arrays.copyOf(buf, pos);
		if (DuplexCodecSupport.isActiveForMap()) {
			return DuplexCodecSupport.getCodec().encodeLogical(logical).toWireBytes();
		}
		return logical;
	}

	public static Object[] decode(TableSchema schema, byte[] stored) {
		final byte[] logical = SqlWireUtil.toLogicalBytes(stored);
		final LogicalFieldCursor cursor = LogicalFieldCursor.open(schema, logical);
		final Object[] out = new Object[schema.columnCount()];
		for (int i = 0; i < out.length; i++) {
			out[i] = cursor.read(i);
		}
		return out;
	}

	public static Object coerce(ColumnDef col, Object raw) {
		return coerce(col, raw, ZoneOffset.UTC);
	}

	public static Object coerce(ColumnDef col, Object raw, ZoneId zone) {
		if (raw == null) {
			if (!col.nullable() && !col.primaryKey()) {
				throw new IllegalArgumentException("NULL not allowed for " + col.name());
			}
			return null;
		}
		return coerceTo(col.type(), raw, zone);
	}

	public static Object coerceTo(SqlType type, Object raw) {
		return coerceTo(type, raw, ZoneOffset.UTC);
	}

	public static Object coerceTo(SqlType type, Object raw, ZoneId zone) {
		if (raw == null) {
			return null;
		}
		return switch (type) {
			case INT -> {
				if (raw instanceof Number n) {
					yield n.intValue();
				}
				yield Integer.parseInt(String.valueOf(raw));
			}
			case BIGINT -> {
				if (raw instanceof Number n) {
					yield n.longValue();
				}
				yield Long.parseLong(String.valueOf(raw));
			}
			case DOUBLE -> {
				if (raw instanceof Number n) {
					yield n.doubleValue();
				}
				yield Double.parseDouble(String.valueOf(raw));
			}
			case BOOLEAN -> {
				if (raw instanceof Boolean b) {
					yield b;
				}
				yield Boolean.parseBoolean(String.valueOf(raw));
			}
			case VARCHAR -> String.valueOf(raw);
			case TIMESTAMP -> SqlTypeCoercion.toTimestampString(raw);
			case BYTES -> {
				if (raw instanceof byte[] b) {
					yield b;
				}
				yield String.valueOf(raw).getBytes(java.nio.charset.StandardCharsets.UTF_8);
			}
			case UUID -> SqlTypeCoercion.toUuid(raw);
			case DATE -> SqlTypeCoercion.toDate(raw);
			case TIME -> SqlTypeCoercion.toTime(raw);
			case TIMESTAMPTZ -> SqlTypeCoercion.toTimestamptz(raw, zone);
		};
	}

	private static int estimateBytes(Class<?> type, Object value) {
		// SQL-common order first (INT / BIGINT / DOUBLE / VARCHAR / BOOLEAN / BYTES).
		if (type == int.class || type == Integer.class) {
			return Integer.BYTES;
		}
		if (type == long.class || type == Long.class) {
			return Long.BYTES;
		}
		if (type == double.class || type == Double.class) {
			return Double.BYTES;
		}
		if (type == String.class) {
			if (value == null) {
				return Integer.BYTES;
			}
			final String s = value instanceof String str ? str : String.valueOf(value);
			return Integer.BYTES + SerialUtil.utf8ByteLength(s);
		}
		if (type == boolean.class || type == Boolean.class || type == byte.class) {
			return Byte.BYTES;
		}
		if (type == byte[].class) {
			final byte[] b = (byte[]) value;
			return Integer.BYTES + (b == null ? 0 : b.length);
		}
		if (type == float.class) {
			return Float.BYTES;
		}
		if (type == short.class || type == char.class) {
			return Short.BYTES;
		}
		if (type == UUID.class) {
			return value == null ? 0 : SqlTypeWireSizes.UUID_BYTES;
		}
		if (type == LocalDate.class) {
			return value == null ? 0 : SqlTypeWireSizes.DATE_BYTES;
		}
		if (type == LocalTime.class) {
			return value == null ? 0 : SqlTypeWireSizes.TIME_BYTES;
		}
		if (type == Instant.class) {
			return value == null ? 0 : SqlTypeWireSizes.TIMESTAMPTZ_BYTES;
		}
		throw new IllegalArgumentException("Unsupported wire type " + type.getName());
	}

	private static int writeValue(byte[] buf, int pos, Class<?> type, Object value, int payloadBytes) {
		if (type == int.class || type == Integer.class) {
			final int v = value == null ? -1 : ((Number) value).intValue();
			Bits.putInt(buf, pos, v);
			return pos + Integer.BYTES;
		}
		if (type == long.class || type == Long.class) {
			final long v = value == null ? -1L : ((Number) value).longValue();
			Bits.putLong(buf, pos, v);
			return pos + Long.BYTES;
		}
		if (type == double.class || type == Double.class) {
			final double v = value == null ? -1d : ((Number) value).doubleValue();
			Bits.putDouble(buf, pos, v);
			return pos + Double.BYTES;
		}
		if (type == String.class) {
			if (value == null) {
				Bits.putInt(buf, pos, SerialUtil.STRING_NULL);
				return pos + Integer.BYTES;
			}
			final String s = value instanceof String str ? str : String.valueOf(value);
			return SerialUtil.writeUtf8KnownLen(buf, pos, s, payloadBytes - Integer.BYTES);
		}
		if (type == boolean.class || type == Boolean.class) {
			Bits.putBoolean(buf, pos, value != null && (Boolean) value);
			return pos + Byte.BYTES;
		}
		if (type == byte.class) {
			buf[pos] = value == null ? 0 : ((Number) value).byteValue();
			return pos + Byte.BYTES;
		}
		if (type == byte[].class) {
			final byte[] b = (byte[]) value;
			if (b == null) {
				Bits.putInt(buf, pos, -1);
				return pos + Integer.BYTES;
			}
			Bits.putInt(buf, pos, b.length);
			pos += Integer.BYTES;
			System.arraycopy(b, 0, buf, pos, b.length);
			return pos + b.length;
		}
		if (type == float.class) {
			Bits.putFloat(buf, pos, value == null ? -1f : ((Number) value).floatValue());
			return pos + Float.BYTES;
		}
		if (type == short.class) {
			Bits.putShort(buf, pos, value == null ? 0 : ((Number) value).shortValue());
			return pos + Short.BYTES;
		}
		if (type == char.class) {
			Bits.putChar(buf, pos, value == null ? 0 : (Character) value);
			return pos + Character.BYTES;
		}
		if (type == UUID.class) {
			if (value == null) {
				return pos;
			}
			return SqlTypeCoercion.writeUuidBytes(buf, pos, (UUID) value);
		}
		if (type == LocalDate.class) {
			if (value == null) {
				return pos;
			}
			Bits.putInt(buf, pos, (int) ((LocalDate) value).toEpochDay());
			return pos + SqlTypeWireSizes.DATE_BYTES;
		}
		if (type == LocalTime.class) {
			if (value == null) {
				return pos;
			}
			Bits.putLong(buf, pos, ((LocalTime) value).toNanoOfDay());
			return pos + SqlTypeWireSizes.TIME_BYTES;
		}
		if (type == Instant.class) {
			if (value == null) {
				return pos;
			}
			final Instant i = (Instant) value;
			Bits.putLong(buf, pos, i.getEpochSecond());
			Bits.putInt(buf, pos + Long.BYTES, i.getNano());
			return pos + SqlTypeWireSizes.TIMESTAMPTZ_BYTES;
		}
		throw new IllegalArgumentException("Unsupported wire type " + type.getName());
	}
}
