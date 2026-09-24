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
package org.genfork.grid.utils;

import jodd.util.Bits;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.genfork.grid.serial.stream.WireByteSink;
import sun.misc.Unsafe;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;

/**
 * LogicalFieldCursor-compatible wire: primitives, UTF-8 length-prefixed strings, abs ordinal trailer.
 * Sole map-wire implementation (no parallel codec).
 *
 * @author: GenCloud
 * @date: 2025/02
 * @since: 1.0
 */
public final class SerialUtil {
	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SerialUtil.class);
	/**
	 * Null string marker (u32).
	 */
	public static final int STRING_NULL = -1;
	private static final byte LOCAL_DATE_TYPE = 1;
	private static final byte LOCAL_TIME_TYPE = 2;
	private static final byte OFFSET_TIME_TYPE = 3;
	private static final byte LOCAL_DATE_TIME_TYPE = 4;
	private static final byte INSTANT_TYPE = 5;
	private static final long STRING_VALUE_OFFSET;
	private static final long STRING_CODER_OFFSET;

	static {
		long v = -1;
		long c = -1;
		try {
			final Unsafe u = UnsafeMemory.getUnsafe();
			v = u.objectFieldOffset(String.class.getDeclaredField("value"));
			c = u.objectFieldOffset(String.class.getDeclaredField("coder"));
		} catch (Throwable ignored) {
		}
		// fallback to char encode
		STRING_VALUE_OFFSET = v;
		STRING_CODER_OFFSET = c;
	}

	public static int getFieldOffset(Class<?> type, Object value) {
		int offset = 0;
		if (byte.class == type || Byte.class == type || boolean.class == type || Boolean.class == type) {
			offset = Byte.BYTES;
		} else if (short.class == type || Short.class == type) {
			offset = Short.BYTES;
		} else if (int.class == type || Integer.class == type || float.class == type || Float.class == type) {
			offset = Integer.BYTES;
		} else if (long.class == type || Long.class == type || double.class == type || Double.class == type) {
			offset = Long.BYTES;
		} else if (BigDecimal.class == type || String.class == type) {
			if (value == null) {
				offset = Integer.BYTES;
			} else {
				offset = Integer.BYTES + utf8ByteLength(value.toString());
			}
		} else if (type.isArray()) {
			final Object[] array = readArray(type, value);
			if (array == null || ArrayUtils.isEmpty(array)) {
				offset = Integer.BYTES;
			} else {
				offset += Integer.BYTES;
				for (Object arrayValue : array) {
					final Class<?> arrayValueType = arrayValue.getClass();
					offset += getFieldOffset(arrayValueType, arrayValue);
				}
			}
		} else if (ClassUtil.isTemporal(type)) {
			if (type == LocalDate.class || type == LocalDateTime.class) {
				offset += (value == null ? Byte.BYTES : 14);
			} else if (type == LocalTime.class) {
				offset += (value == null ? Byte.BYTES : 18);
			} else if (type == OffsetTime.class) {
				offset += (value == null ? Byte.BYTES : 22);
			} else if (type == Instant.class) {
				offset += (value == null ? Byte.BYTES : 10);
			}
		}
		return offset;
	}

	public static void writePrimitiveData(ObjectOutput out, Class<?> type, Object value) throws IOException {
		if (byte.class == type || Byte.class == type) {
			writeU8(out, value == null ? -1 : (byte) value);
		} else if (short.class == type || Short.class == type) {
			writeI16(out, value == null ? -1 : (short) value);
		} else if (int.class == type || Integer.class == type) {
			writeI32(out, value == null ? -1 : (int) value);
		} else if (long.class == type || Long.class == type) {
			writeI64(out, value == null ? -1 : (long) value);
		} else if (double.class == type || Double.class == type) {
			writeF64(out, value == null ? -1 : (double) value);
		} else if (float.class == type || Float.class == type) {
			writeF32(out, value == null ? -1 : (float) value);
		} else if (BigDecimal.class == type) {
			writeUtf8(out, value == null ? StringUtils.EMPTY : value.toString());
		} else if (boolean.class == type || Boolean.class == type) {
			writeU8(out, value == null ? 0 : ((boolean) value) ? 1 : 0);
		} else if (String.class == type) {
			writeUtf8(out, (String) value);
		} else if (type.isArray()) {
			final Object[] array = readArray(type, value);
			if (array == null || ArrayUtils.isEmpty(array)) {
				writeI32(out, 0);
			} else {
				final int size = array.length;
				writeI32(out, size);
				for (Object arrayValue : array) {
					final Class<?> arrayType = arrayValue.getClass();
					writePrimitiveData(out, arrayType, arrayValue);
				}
			}
		} else {
			log.error("Can\'t write data correctly. Unknown filed type: {} for value - {}", type, value);
		}
	}

	private static Object[] readArray(Class<?> type, Object value) {
		if (isPrimitiveArray(type)) {
			if (value == null) {
				return new Object[0];
			}
			return boxPrimitiveArray(value);
		}
		return (Object[]) value;
	}

	private static Object[] boxPrimitiveArray(Object value) {
		if (value instanceof byte[] a) {
			final Object[] out = new Object[a.length];
			for (int i = 0; i < a.length; i++) {
				out[i] = a[i];
			}
			return out;
		}
		if (value instanceof short[] a) {
			final Object[] out = new Object[a.length];
			for (int i = 0; i < a.length; i++) {
				out[i] = a[i];
			}
			return out;
		}
		if (value instanceof int[] a) {
			final Object[] out = new Object[a.length];
			for (int i = 0; i < a.length; i++) {
				out[i] = a[i];
			}
			return out;
		}
		if (value instanceof long[] a) {
			final Object[] out = new Object[a.length];
			for (int i = 0; i < a.length; i++) {
				out[i] = a[i];
			}
			return out;
		}
		if (value instanceof float[] a) {
			final Object[] out = new Object[a.length];
			for (int i = 0; i < a.length; i++) {
				out[i] = a[i];
			}
			return out;
		}
		if (value instanceof double[] a) {
			final Object[] out = new Object[a.length];
			for (int i = 0; i < a.length; i++) {
				out[i] = a[i];
			}
			return out;
		}
		if (value instanceof boolean[] a) {
			final Object[] out = new Object[a.length];
			for (int i = 0; i < a.length; i++) {
				out[i] = a[i] ? Boolean.TRUE : Boolean.FALSE;
			}
			return out;
		}
		throw new IllegalArgumentException("Not a supported primitive array: " + value.getClass().getName());
	}

	private static boolean isPrimitiveArray(Class<?> type) {
		return type == byte[].class || type == short[].class || type == int[].class || type == long[].class || type == float[].class || type == double[].class || type == boolean[].class;
	}

	/**
	 * Writes an unsigned 8-bit value.
	 *
	 * @param value the byte (The 24 high-order bits are ignored)
	 */
	public static void writeU8(ObjectOutput out, int value) throws IOException {
		out.writeByte(value);
	}

	/**
	 * Writes a signed 16-bit value.
	 *
	 * @param value the short (The 16 high-order bits are ignored)
	 */
	public static void writeI16(ObjectOutput out, int value) throws IOException {
		out.writeShort(value);
	}

	/**
	 * Writes a signed 32-bit value.
	 *
	 * @param value the integer
	 */
	public static void writeI32(ObjectOutput out, int value) throws IOException {
		out.writeInt(value);
	}

	/**
	 * Writes a signed 64-bit value.
	 *
	 * @param value the long
	 */
	public static void writeI64(ObjectOutput out, long value) throws IOException {
		out.writeLong(value);
	}

	/**
	 * Writes a 32-bit IEEE float.
	 *
	 * @param value the float
	 */
	public static void writeF32(ObjectOutput out, float value) throws IOException {
		out.writeInt(Float.floatToIntBits(value));
	}

	/**
	 * Writes a 64-bit IEEE double.
	 *
	 * @param value the double
	 */
	public static void writeF64(ObjectOutput out, double value) throws IOException {
		out.writeLong(Double.doubleToLongBits(value));
	}

	/**
	 * Writes a UTF-8 length-prefixed string ({@link #STRING_NULL} for null).
	 */
	public static void writeUtf8(ObjectOutput out, String value) throws IOException {
		if (out instanceof WireByteSink sink) {
			writeUtf8(sink, value);
			return;
		}
		if (value == null) {
			out.writeInt(STRING_NULL);
			return;
		}
		final byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
		out.writeInt(utf8.length);
		out.write(utf8);
	}

	public static void writeUtf8(WireByteSink out, String value) {
		if (value == null) {
			out.ensureRoom(4);
			out.writeInt(STRING_NULL);
			return;
		}
		final byte coder = stringCoder(value);
		if (coder == 0) {
			final byte[] latin1 = stringValueBytes(value);
			final int utf8Len = utf8LenFromLatin1(latin1);
			out.ensureRoom(4 + utf8Len);
			out.writeInt(utf8Len);
			final int p = out.position();
			if (utf8Len == latin1.length) {
				System.arraycopy(latin1, 0, out.rawArray(), p, utf8Len);
			} else {
				encodeLatin1AsUtf8(out.rawArray(), p, latin1);
			}
			out.skip(utf8Len);
			return;
		}
		final int utf8Len = utf8ByteLengthUtf16(value);
		out.ensureRoom(4 + utf8Len);
		out.writeInt(utf8Len);
		final int pos = out.position();
		encodeUtf8(out.rawArray(), pos, value, utf8Len);
		out.skip(utf8Len);
	}

	/**
	 * @return new write position
	 */
	public static int writeUtf8(byte[] buf, int pos, String value) {
		if (value == null) {
			Bits.putInt(buf, pos, STRING_NULL);
			return pos + Integer.BYTES;
		}
		final byte coder = stringCoder(value);
		if (coder == 0) {
			final byte[] latin1 = stringValueBytes(value);
			final int utf8Len = utf8LenFromLatin1(latin1);
			return writeLatin1AsUtf8Prefixed(buf, pos, latin1, utf8Len);
		}
		final int utf8Len = utf8ByteLengthUtf16(value);
		Bits.putInt(buf, pos, utf8Len);
		return encodeUtf8(buf, pos + Integer.BYTES, value, utf8Len);
	}

	/**
	 * Length-prefixed UTF-8 write when {@code utf8Len} was already measured
	 * ({@link #utf8ByteLength}) — skips a second latin1 / UTF-16 length scan.
	 *
	 * @return new write position
	 */
	public static int writeUtf8KnownLen(byte[] buf, int pos, String value, int utf8Len) {
		if (value == null) {
			Bits.putInt(buf, pos, STRING_NULL);
			return pos + Integer.BYTES;
		}
		final byte coder = stringCoder(value);
		if (coder == 0) {
			return writeLatin1AsUtf8Prefixed(buf, pos, stringValueBytes(value), utf8Len);
		}
		Bits.putInt(buf, pos, utf8Len);
		return encodeUtf8(buf, pos + Integer.BYTES, value, utf8Len);
	}

	private static int writeLatin1AsUtf8Prefixed(byte[] buf, int pos, byte[] latin1, int utf8Len) {
		Bits.putInt(buf, pos, utf8Len);
		if (utf8Len == latin1.length) {
			System.arraycopy(latin1, 0, buf, pos + Integer.BYTES, utf8Len);
			return pos + Integer.BYTES + utf8Len;
		}
		return encodeLatin1AsUtf8(buf, pos + Integer.BYTES, latin1);
	}

	public static void writeTemporal(ObjectOutput out, Class<?> type, Object value) throws IOException {
		if (type == LocalDate.class) {
			// 1 + 1 + 4 + 4 + 4 = 1|14
			final LocalDate localDate = (LocalDate) value;
			if (localDate == null) {
				writeU8(out, 0);
			} else {
				writeU8(out, 1);
				writeU8(out, LOCAL_DATE_TYPE);
				writeI32(out, localDate.getYear());
				writeI32(out, localDate.getMonthValue());
				writeI32(out, localDate.getDayOfMonth());
			}
		} else if (type == LocalTime.class) {
			// 1 + 1 + 4 + 4 + 4 + 4 = 1|18
			final LocalTime localTime = (LocalTime) value;
			if (localTime == null) {
				writeU8(out, 0);
			} else {
				writeU8(out, 1);
				writeU8(out, LOCAL_TIME_TYPE);
				writeI32(out, localTime.getHour());
				writeI32(out, localTime.getMinute());
				writeI32(out, localTime.getSecond());
				writeI32(out, localTime.getNano());
			}
		} else if (type == OffsetTime.class) {
			// 1 + 1 + 4 + 4 + 4 + 4 + 4 = 1|22
			final OffsetTime offsetTime = (OffsetTime) value;
			if (offsetTime == null) {
				writeU8(out, 0);
			} else {
				writeU8(out, 1);
				writeU8(out, OFFSET_TIME_TYPE);
				writeI32(out, offsetTime.getHour());
				writeI32(out, offsetTime.getMinute());
				writeI32(out, offsetTime.getSecond());
				writeI32(out, offsetTime.getNano());
				writeI32(out, offsetTime.getOffset().getTotalSeconds());
			}
		} else if (type == LocalDateTime.class) {
			// 1 + 1 + 8 + 4 = 1|14
			final LocalDateTime localDateTime = (LocalDateTime) value;
			if (localDateTime == null) {
				writeU8(out, 0);
			} else {
				writeU8(out, 1);
				writeU8(out, LOCAL_DATE_TIME_TYPE);
				writeI64(out, localDateTime.toEpochSecond(ZoneOffset.UTC));
				writeI32(out, localDateTime.getNano());
			}
		} else if (type == Instant.class) {
			// 1 + 1 + 8 = 1|10
			final Instant instant = (Instant) value;
			if (instant == null) {
				writeU8(out, 0);
			} else {
				writeU8(out, 1);
				writeU8(out, INSTANT_TYPE);
				writeI64(out, instant.toEpochMilli());
			}
		} else {
			throw new RuntimeException("Unknown Temporal field type - " + type.getSimpleName() + ". " + "Available LocalDate, LocalTime, OffsetTime, LocalDateTime, Instant");
		}
	}

	public static Object readPrimitives(byte[] buf, int pos, Class<?> type) {
		Object result = null;
		if (byte.class == type || Byte.class == type) {
			result = readU8(buf, pos);
		} else if (short.class == type || Short.class == type) {
			result = readI16(buf, pos);
		} else if (int.class == type || Integer.class == type) {
			result = readI32(buf, pos);
		} else if (long.class == type || Long.class == type) {
			result = readI64(buf, pos);
		} else if (double.class == type || Double.class == type) {
			result = readF64(buf, pos);
		} else if (float.class == type || Float.class == type) {
			result = readF32(buf, pos);
		} else if (BigDecimal.class == type) {
			final String format = readUtf8(buf, pos);
			result = StringUtils.isEmpty(format) ? new BigDecimal(0) : new BigDecimal(format);
		} else if (boolean.class == type || Boolean.class == type) {
			result = readU8(buf, pos) == 1;
		} else if (String.class == type) {
			result = readUtf8(buf, pos);
		} else if (type.isArray()) {
			final int arrayLength = readI32(buf, pos);
			pos += Integer.BYTES;
			final Object[] array = new Object[arrayLength];
			final Class<?> componentType = type.getComponentType();
			for (int i = 0; i < arrayLength; i++) {
				final Object obj = readPrimitives(buf, pos, componentType);
				array[i] = obj;
				pos += getFieldOffset(componentType, obj);
			}
			result = array;
		} else {
			log.error("Can\'t read data correctly. Unknown filed type: {}", type);
		}
		return result;
	}

	public static Object readPrimitives(ObjectInput in, Class<?> type) throws IOException {
		Object result = null;
		if (byte.class == type || Byte.class == type) {
			result = readU8(in);
		} else if (short.class == type || Short.class == type) {
			result = readI16(in);
		} else if (int.class == type || Integer.class == type) {
			result = readI32(in);
		} else if (long.class == type || Long.class == type) {
			result = readI64(in);
		} else if (double.class == type || Double.class == type) {
			result = readF64(in);
		} else if (float.class == type || Float.class == type) {
			result = readF32(in);
		} else if (BigDecimal.class == type) {
			final String format = readUtf8(in);
			result = StringUtils.isEmpty(format) ? new BigDecimal(0) : new BigDecimal(format);
		} else if (boolean.class == type || Boolean.class == type) {
			result = readU8(in) == 1;
		} else if (String.class == type) {
			result = readUtf8(in);
		} else if (type.isArray()) {
			final int arrayLength = readI32(in);
			final Object[] array = new Object[arrayLength];
			final Class<?> componentType = type.getComponentType();
			for (int i = 0; i < arrayLength; i++) {
				final Object obj = readPrimitives(in, componentType);
				array[i] = obj;
			}
			result = array;
		} else {
			log.error("Can\'t read data correctly. Unknown filed type: {}", type);
		}
		return result;
	}

	/**
	 * Reads an unsigned byte.
	 *
	 * @return the unsigned byte
	 */
	public static byte readU8(byte[] array, int pos) {
		return (byte) (array[pos] & 255);
	}

	/**
	 * Reads an unsigned short.
	 *
	 * @return the unsigned short
	 */
	public static short readI16(byte[] array, int pos) {
		return (short) (Bits.getShort(array, pos) & 65535);
	}

	/**
	 * Reads an integer.
	 *
	 * @return the integer
	 */
	public static int readI32(byte[] array, int pos) {
		return Bits.getInt(array, pos);
	}

	/**
	 * Reads a long.
	 *
	 * @return the long
	 */
	public static long readI64(byte[] array, int pos) {
		return Bits.getLong(array, pos);
	}

	/**
	 * Reads a float.
	 *
	 * @return the float
	 */
	public static float readF32(byte[] array, int pos) {
		return Float.intBitsToFloat(Bits.getInt(array, pos));
	}

	/**
	 * Reads a double.
	 *
	 * @return the double
	 */
	public static double readF64(byte[] array, int pos) {
		return Double.longBitsToDouble(Bits.getLong(array, pos));
	}

	/**
	 * Reads a string (UTF-8 length-prefixed). Null wire → empty string for callers.
	 */
	public static String readUtf8(byte[] array, int pos) {
		final String s = readUtf8OrNull(array, pos);
		return s == null ? StringUtils.EMPTY : s;
	}

	/**
	 * @return decoded string, or {@code null} if {@link #STRING_NULL}
	 */
	public static String readUtf8OrNull(byte[] array, int pos) {
		if (array == null || pos < 0 || pos + 4 > array.length) {
			return "";
		}
		final int len = Bits.getInt(array, pos);
		if (len == STRING_NULL) {
			return null;
		}
		if (len < 0 || pos + 4 + len > array.length) {
			return "";
		}
		if (len == 0) {
			return "";
		}
		return new String(array, pos + 4, len, StandardCharsets.UTF_8);
	}

	/**
	 * Reads an unsigned byte.
	 *
	 * @return the unsigned byte
	 * @throws IndexOutOfBoundsException if {@code readableBytes} is less than {@code 1}
	 */
	public static byte readU8(ObjectInput in) throws IOException {
		return (byte) in.readUnsignedByte();
	}

	/**
	 * Reads an unsigned short.
	 *
	 * @return the unsigned short
	 * @throws IndexOutOfBoundsException if {@code readableBytes} is less than {@code 2}
	 */
	public static short readI16(ObjectInput in) throws IOException {
		return (short) in.readUnsignedShort();
	}

	/**
	 * Reads an integer.
	 *
	 * @return the integer
	 * @throws IndexOutOfBoundsException if {@code readableBytes} is less than {@code 4}
	 */
	public static int readI32(ObjectInput in) throws IOException {
		return in.readInt();
	}

	/**
	 * Reads a long.
	 *
	 * @return the long
	 * @throws IndexOutOfBoundsException if {@code readableBytes} is less than {@code 8}
	 */
	public static long readI64(ObjectInput in) throws IOException {
		return in.readLong();
	}

	/**
	 * Reads a float.
	 *
	 * @return the float
	 * @throws IndexOutOfBoundsException if {@code readableBytes} is less than {@code 4}
	 */
	public static float readF32(ObjectInput in) throws IOException {
		return Float.intBitsToFloat(in.readInt());
	}

	/**
	 * Reads a double.
	 *
	 * @return the double
	 * @throws IndexOutOfBoundsException if {@code readableBytes} is less than {@code 8}
	 */
	public static double readF64(ObjectInput in) throws IOException {
		return Double.longBitsToDouble(in.readLong());
	}

	/**
	 * Reads a string (UTF-8 length-prefixed). Null wire → empty string for callers.
	 */
	public static String readUtf8(ObjectInput in) throws IOException {
		final int len = in.readInt();
		if (len == STRING_NULL) {
			return StringUtils.EMPTY;
		}
		if (len <= 0) {
			return StringUtils.EMPTY;
		}
		final byte[] utf8 = new byte[len];
		in.readFully(utf8);
		return new String(utf8, StandardCharsets.UTF_8);
	}

	/**
	 * Absolute field starts (from blob base 0) + trailer byte length {@code n*4}.
	 */
	public static int writeAbsTrailer(byte[] buf, int pos, int[] starts, int n) {
		for (int i = 0; i < n; i++) {
			Bits.putInt(buf, pos, starts[i]);
			pos += 4;
		}
		Bits.putInt(buf, pos, n * 4);
		return pos + 4;
	}

	public static void writeAbsTrailer(WireByteSink out, int[] starts, int n) {
		out.ensureRoom(n * 4 + 4);
		for (int i = 0; i < n; i++) {
			out.writeInt(starts[i]);
		}
		out.writeInt(n * 4);
	}

	public static int utf8ByteLength(String value) {
		if (stringCoder(value) == 0) {
			return utf8LenFromLatin1(stringValueBytes(value));
		}
		return utf8ByteLengthUtf16(value);
	}

	private static byte stringCoder(String s) {
		if (STRING_CODER_OFFSET < 0) {
			return 1;
		}
		return UnsafeMemory.getUnsafe().getByte(s, STRING_CODER_OFFSET);
	}

	private static byte[] stringValueBytes(String s) {
		return (byte[]) UnsafeMemory.getUnsafe().getObject(s, STRING_VALUE_OFFSET);
	}

	private static int utf8LenFromLatin1(byte[] latin1) {
		int n = latin1.length;
		for (byte b : latin1) {
			if (b < 0) {
				n++;
			}
		}
		return n;
	}

	private static int encodeLatin1AsUtf8(byte[] buf, int pos, byte[] latin1) {
		int p = pos;
		for (byte b : latin1) {
			if (b >= 0) {
				buf[p++] = b;
			} else {
				final int c = b & 255;
				buf[p++] = (byte) (192 | (c >> 6));
				buf[p++] = (byte) (128 | (c & 63));
			}
		}
		return p;
	}

	private static int utf8ByteLengthUtf16(String value) {
		final int len = value.length();
		int bytes = 0;
		for (int i = 0; i < len; i++) {
			final char c = value.charAt(i);
			if (c < 128) {
				bytes++;
			} else if (c < 2048) {
				bytes += 2;
			} else if (Character.isSurrogate(c)) {
				if (Character.isHighSurrogate(c) && i + 1 < len && Character.isLowSurrogate(value.charAt(i + 1))) {
					bytes += 4;
					i++;
				} else {
					bytes += 3;
				}
			} else {
				bytes += 3;
			}
		}
		return bytes;
	}

	private static int encodeUtf8(byte[] buf, int pos, String value, int expectedLen) {
		final int len = value.length();
		int p = pos;
		for (int i = 0; i < len; i++) {
			final char c = value.charAt(i);
			if (c < 128) {
				buf[p++] = (byte) c;
			} else if (c < 2048) {
				buf[p++] = (byte) (192 | (c >> 6));
				buf[p++] = (byte) (128 | (c & 63));
			} else if (Character.isHighSurrogate(c) && i + 1 < len && Character.isLowSurrogate(value.charAt(i + 1))) {
				final int cp = Character.toCodePoint(c, value.charAt(++i));
				buf[p++] = (byte) (240 | (cp >> 18));
				buf[p++] = (byte) (128 | ((cp >> 12) & 63));
				buf[p++] = (byte) (128 | ((cp >> 6) & 63));
				buf[p++] = (byte) (128 | (cp & 63));
			} else {
				buf[p++] = (byte) (224 | (c >> 12));
				buf[p++] = (byte) (128 | ((c >> 6) & 63));
				buf[p++] = (byte) (128 | (c & 63));
			}
		}
		if (p - pos != expectedLen) {
			final byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
			System.arraycopy(utf8, 0, buf, pos, utf8.length);
			return pos + utf8.length;
		}
		return p;
	}

	private SerialUtil() {
		throw new java.lang.UnsupportedOperationException("This is a utility class and cannot be instantiated");
	}
}
