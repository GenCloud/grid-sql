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
import org.genfork.grid.catalog.SqlTypeCoercion;
import org.genfork.grid.catalog.SqlTypeWireSizes;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.codec.duplex.DuplexCodecSupport;
import org.genfork.grid.replication.apply.FieldModifyApplicator;
import org.genfork.grid.replication.codec.ModifyPayload.Decoded;
import org.genfork.grid.utils.SerialUtil;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads/rewrites selected fields on logical (or duplex-unwrapped) row bytes without full domain convert.
 * <p>
 * Supports abs-start trailers and duplex hash+offset trailers. Multi-assign rewrite is one pass.
 *
 * @author: GenCloud
 * @date: 2025/06
 * @since: 1.0
 */
public final class LogicalFieldCursor {
	private enum TrailerKind { ABS_STARTS, HASH_OFFSET }

	private final FieldMetaData[] fields;
	private final byte[] logical;
	private final int[] starts;
	private final int[] hashes;
	private final int payloadEnd;
	private final TrailerKind trailerKind;

	private LogicalFieldCursor(FieldMetaData[] fields, byte[] logical,
	                           int[] starts, int[] hashes, int payloadEnd, TrailerKind trailerKind) {
		this.fields = fields;
		this.logical = logical;
		this.starts = starts;
		this.hashes = hashes;
		this.payloadEnd = payloadEnd;
		this.trailerKind = trailerKind;
	}

	public static LogicalFieldCursor open(TableSchema schema, byte[] stored) {
		final byte[] logical = SqlWireUtil.toLogicalBytes(stored);
		return openFields(schema.fieldMetas(), logical);
	}

	private static LogicalFieldCursor openFields(FieldMetaData[] fields, byte[] logical) {
		final int n = fields.length;
		final int trailerLenPos = logical.length - Integer.BYTES;
		final int trailerBytes = SerialUtil.readI32(logical, trailerLenPos);

		final int[] starts = new int[n];
		final int[] hashes;
		final int payloadEnd = trailerLenPos - trailerBytes;

		final TrailerKind kind;
		if (trailerBytes == n * 8) {
			kind = TrailerKind.HASH_OFFSET;
			hashes = new int[n];

			final int[] rawByOrd = new int[n];
			Arrays.fill(rawByOrd, Integer.MIN_VALUE);
			int pos = trailerLenPos - trailerBytes;
			for (int i = 0; i < n; i++) {
				hashes[i] = SerialUtil.readI32(logical, pos);
				pos += 4;
				final int raw = SerialUtil.readI32(logical, pos);
				pos += 4;
				final int ord = ordinalByHash(fields, hashes[i]);
				rawByOrd[ord] = raw;
			}

			for (int i = 0; i < n; i++) {
				if (rawByOrd[i] == Integer.MIN_VALUE) {
					throw new IllegalStateException("duplex trailer missing hash for field " + fields[i].getName());
				}
			}

			// WireByteSink records abs start positions for the trailer.
			int sum = 0;
			for (int i = 0; i < n; i++) {
				sum += rawByOrd[i];
			}

			if (sum == payloadEnd) {
				int cursor = 0;
				for (int i = 0; i < n; i++) {
					starts[i] = cursor;
					cursor += rawByOrd[i];
				}
			} else {
				System.arraycopy(rawByOrd, 0, starts, 0, n);
			}
		} else if (trailerBytes == n * 4) {
			kind = TrailerKind.ABS_STARTS;
			hashes = null;

			int pos = trailerLenPos - trailerBytes;
			for (int i = 0; i < n; i++) {
				starts[i] = SerialUtil.readI32(logical, pos);
				pos += 4;
			}
		} else if (trailerBytes % 4 == 0 && trailerBytes / 4 < n && trailerBytes / 4 > 0) {
			// ALTER ADD COLUMN: blob written with fewer trailing fields — pad missing as empty/null.
			kind = TrailerKind.ABS_STARTS;
			hashes = null;

			final int wire = trailerBytes / 4;
			int pos = trailerLenPos - trailerBytes;
			for (int i = 0; i < wire; i++) {
				starts[i] = SerialUtil.readI32(logical, pos);
				pos += 4;
			}

			for (int i = wire; i < n; i++) {
				starts[i] = payloadEnd;
			}
		} else {
			throw new IllegalStateException("schema fields " + n + " incompatible with trailer bytes " + trailerBytes);
		}
		return new LogicalFieldCursor(fields, logical, starts, hashes, payloadEnd, kind);
	}

	private static int ordinalByHash(FieldMetaData[] fields, int hash) {
		for (int i = 0; i < fields.length; i++) {
			if (fields[i].hashCode() == hash) {
				return i;
			}
		}

		throw new IllegalStateException("duplex trailer hash " + hash + " not in schema");
	}

	public Object read(int ordinal) {
		final FieldMetaData fm = fields[ordinal];
		final Class<?> type = fm.getType();
		final int start = starts[ordinal];
		final int end = fieldEnd(ordinal);
		if (isWireNull(type, start, end)) {
			return null;
		}
		if (type == UUID.class) {
			final byte[] raw = new byte[SqlTypeWireSizes.UUID_BYTES];
			System.arraycopy(logical, start, raw, 0, SqlTypeWireSizes.UUID_BYTES);
			return SqlTypeCoercion.uuidFromBytes(raw);
		}
		if (type == LocalDate.class) {
			return LocalDate.ofEpochDay(SerialUtil.readI32(logical, start));
		}
		if (type == LocalTime.class) {
			return LocalTime.ofNanoOfDay(SerialUtil.readI64(logical, start));
		}
		if (type == Instant.class) {
			final long epochSec = SerialUtil.readI64(logical, start);
			final int nano = SerialUtil.readI32(logical, start + Long.BYTES);
			return Instant.ofEpochSecond(epochSec, nano);
		}
		if (type == byte[].class) {
			final int length = SerialUtil.readI32(logical, start);
			final byte[] value = new byte[length];
			System.arraycopy(logical, start + Integer.BYTES, value, 0, length);
			return value;
		}
		final Object raw = SerialUtil.readPrimitives(logical, start, type);
		return decodeWireNull(type, raw);
	}

	/** Absolute start of field payload in {@link #logical}. */
	public int fieldStart(int ordinal) {
		return starts[ordinal];
	}

	/** Exclusive end of field payload in {@link #logical}. */
	public int fieldEnd(int ordinal) {
		return ordinal + 1 < fields.length ? starts[ordinal + 1] : payloadEnd;
	}

	/**
	 * Zero-copy wire span for JOIN / GROUP BY / DISTINCT mid-pipeline keys.
	 * <p>
	 * Lifetime is bound to this cursor's logical blob. For owned keys (BPTree / store)
	 * use {@link #indexKeyBytes(int)} or {@link WireSpan#toOwnedBytes()}.
	 */
	public WireSpan indexKeySpan(int ordinal) {
		final FieldMetaData fm = fields[ordinal];
		final Class<?> type = fm.getType();
		final int start = starts[ordinal];
		final int end = fieldEnd(ordinal);
		if (isWireNull(type, start, end)) {
			return WireSpan.nullSpan();
		}
		return WireSpan.of(logical, start, end - start);
	}

	/**
	 * Index key bytes for a field: owned copy of the wire span when non-null (same layout as
	 * {@link SqlWireUtil#toGenericArray} for primitives/String), else {@code NULL_PTR}.
	 * Prefer {@link #indexKeySpan(int)} on hot JOIN/GROUP paths.
	 */
	public byte[] indexKeyBytes(int ordinal) {
		return indexKeySpan(ordinal).toOwnedBytes();
	}

	/** Project selected ordinals into heap values (only those fields — not full-row decode). */
	public Object[] project(int[] ordinals) {
		if (ordinals == null) {
			final Object[] all = new Object[fields.length];
			for (int i = 0; i < fields.length; i++) {
				all[i] = read(i);
			}
			return all;
		}

		final Object[] out = new Object[ordinals.length];
		for (int i = 0; i < ordinals.length; i++) {
			out[i] = read(ordinals[i]);
		}
		return out;
	}

	private boolean isWireNull(Class<?> type, int start, int end) {
		if (end <= start) {
			return true;
		}

		if (type == Integer.class && end - start >= 4) {
			return SerialUtil.readI32(logical, start) == -1;
		}

		if (type == Long.class && end - start >= 8) {
			return SerialUtil.readI64(logical, start) == -1L;
		}

		if (type == Double.class && end - start >= 8) {
			return Double.doubleToLongBits(SerialUtil.readF64(logical, start)) == Double.doubleToLongBits(-1d);
		}

		if (type == String.class && end - start >= 4) {
			return SerialUtil.readI32(logical, start) < 0;
		}

		if (type == byte[].class && end - start >= 4) {
			return SerialUtil.readI32(logical, start) < 0;
		}
		if (type == UUID.class || type == LocalDate.class || type == LocalTime.class || type == Instant.class) {
			return end <= start;
		}
		return false;
	}

	/** Boxed numeric wire uses -1 as null sentinel (same as SerialUtil.writeField). */
	private static Object decodeWireNull(Class<?> type, Object raw) {
        return switch (raw) {
            case null -> null;
            case Integer i when type == Integer.class && i == -1 -> null;
            case Long l when type == Long.class && l == -1L -> null;
            case Double d when type == Double.class && d == -1d -> null;
            default -> raw;
        };
    }

	public int ordinalOf(String fieldName) {
		for (int i = 0; i < fields.length; i++) {
			if (fields[i].getName().equals(fieldName)) {
				return i;
			}
		}
		throw new IllegalArgumentException("Unknown field: " + fieldName);
	}

	/**
	 * Apply assigns in one rebuild; return map storage bytes (duplex wire if map duplex active).
	 */
	public byte[] rewrite(List<Decoded> assigns) {
		if (assigns == null || assigns.isEmpty()) {
			return wrapForStorage(logical);
		}

		final Map<Integer, byte[]> replacements = new HashMap<>(assigns.size() * 2);
		for (Decoded decoded : assigns) {
			final int ord = ordinalOf(decoded.fieldName());
			final FieldMetaData fm = fields[ord];
			final Object prev = read(ord);
			final Object next = FieldModifyApplicator.applyValue(
					fm.getType(), decoded.kind(), decoded.arg(), prev);
			replacements.put(ord, encodeFieldBytes(fm.getType(), next));
		}

		final int n = fields.length;
		int newPayload = 0;
		final byte[][] segments = new byte[n][];
		for (int i = 0; i < n; i++) {
			if (replacements.containsKey(i)) {
				segments[i] = replacements.get(i);
			} else {
				final int end = i + 1 < n ? starts[i + 1] : payloadEnd;
				if (end < starts[i] || end > payloadEnd) {
					throw new IllegalStateException("bad field span ord=" + i
							+ " start=" + starts[i] + " end=" + end + " payloadEnd=" + payloadEnd);
				}
				segments[i] = Arrays.copyOfRange(logical, starts[i], end);
			}
			newPayload += segments[i].length;
		}
		// Always emit abs-starts trailer so LogicalFieldCursor stays aligned.
		final byte[] out = new byte[newPayload + n * 4 + 4];
		final int[] newStarts = new int[n];
		int pos = 0;
		for (int i = 0; i < n; i++) {
			newStarts[i] = pos;
			System.arraycopy(segments[i], 0, out, pos, segments[i].length);
			pos += segments[i].length;
		}
		SerialUtil.writeAbsTrailer(out, pos, newStarts, n);
		return wrapForStorage(out);
	}

	private static byte[] wrapForStorage(byte[] logicalBytes) {
		if (DuplexCodecSupport.isActiveForMap()) {
			return DuplexCodecSupport.getCodec().encodeLogical(logicalBytes).toWireBytes();
		}
		return logicalBytes;
	}

	private static byte[] encodeFieldBytes(Class<?> type, Object value) {
		if (type == String.class) {
			final String s = value == null ? "" : String.valueOf(value);
			final int need = 4 + SerialUtil.utf8ByteLength(s);
			final byte[] buf = new byte[need];
			SerialUtil.writeUtf8(buf, 0, s);
			return buf;
		}

		if (type == int.class || type == Integer.class) {
			final byte[] buf = new byte[4];
			final int v = value == null ? 0 : ((Number) value).intValue();
			Bits.putInt(buf, 0, type == Integer.class && value == null ? -1 : v);
			return buf;
		}

		if (type == long.class || type == Long.class) {
			final byte[] buf = new byte[8];
			final long v = value == null ? 0L : ((Number) value).longValue();
			Bits.putLong(buf, 0, type == Long.class && value == null ? -1L : v);
			return buf;
		}

		if (type == short.class || type == Short.class) {
			final byte[] buf = new byte[2];
			Bits.putShort(buf, 0, value == null ? (short) 0 : ((Number) value).shortValue());
			return buf;
		}

		if (type == byte.class || type == Byte.class || type == boolean.class || type == Boolean.class) {
			final byte[] buf = new byte[1];
			if (type == boolean.class || type == Boolean.class) {
				buf[0] = (byte) (value != null && (Boolean) value ? 1 : 0);
			} else {
				buf[0] = value == null ? 0 : ((Number) value).byteValue();
			}
			return buf;
		}

		if (type == float.class || type == Float.class) {
			final byte[] buf = new byte[4];
			Bits.putFloat(buf, 0, value == null ? 0f : ((Number) value).floatValue());
			return buf;
		}

		if (type == double.class || type == Double.class) {
			final byte[] buf = new byte[8];
			Bits.putDouble(buf, 0, value == null ? 0d : ((Number) value).doubleValue());
			return buf;
		}

		if (type == UUID.class) {
			if (value == null) {
				return new byte[0];
			}
			return SqlTypeCoercion.uuidToBytes((UUID) value);
		}

		if (type == LocalDate.class) {
			if (value == null) {
				return new byte[0];
			}
			final byte[] buf = new byte[SqlTypeWireSizes.DATE_BYTES];
			Bits.putInt(buf, 0, (int) ((LocalDate) value).toEpochDay());
			return buf;
		}

		if (type == LocalTime.class) {
			if (value == null) {
				return new byte[0];
			}
			final byte[] buf = new byte[SqlTypeWireSizes.TIME_BYTES];
			Bits.putLong(buf, 0, ((LocalTime) value).toNanoOfDay());
			return buf;
		}

		if (type == Instant.class) {
			if (value == null) {
				return new byte[0];
			}
			final Instant i = (Instant) value;
			final byte[] buf = new byte[SqlTypeWireSizes.TIMESTAMPTZ_BYTES];
			Bits.putLong(buf, 0, i.getEpochSecond());
			Bits.putInt(buf, Long.BYTES, i.getNano());
			return buf;
		}

		if (type == byte[].class) {
			if (value == null) {
				final byte[] buf = new byte[4];
				Bits.putInt(buf, 0, -1);
				return buf;
			}
			final byte[] b = (byte[]) value;
			final byte[] buf = new byte[4 + b.length];
			Bits.putInt(buf, 0, b.length);
			System.arraycopy(b, 0, buf, 4, b.length);
			return buf;
		}

		throw new IllegalArgumentException("LogicalFieldCursor encode unsupported type: " + type.getName());
	}

	public static boolean canOpen(TableSchema schema, byte[] stored) {
		if (stored == null || stored.length < 8 || schema == null) {
			return false;
		}

		try {
			open(schema, stored);
			return true;
		} catch (RuntimeException ex) {
			return false;
		}
	}
}