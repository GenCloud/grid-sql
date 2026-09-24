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

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import jodd.util.Bits;

import org.genfork.grid.mem.index.btree.comparator.ArraysComparator;
import org.genfork.grid.utils.ArrayVectors;
import org.genfork.grid.utils.SerialUtil;

/**
 * Residual / JOIN compare helpers on wire field bytes (no Object deserialize).
 *
 * @author: GenCloud
 * @date: 2025/06
 * @since: 1.0
 */
public final class WireFieldCompare {
	private static final ArraysComparator ARRAYS = new ArraysComparator();

	private WireFieldCompare() {
	}

	/** Same ordering as index keys ({@link ArraysComparator}). */
	public static int compare(byte[] left, byte[] right) {
		return ARRAYS.compare(left, right);
	}

	public static boolean equals(byte[] left, byte[] right) {
		return ArrayVectors.bytesEqual(left, right);
	}

	public static boolean isNull(byte[] wire) {
		return wire == null || Arrays.equals(wire, SqlWireUtil.getNullPtr());
	}

	/**
	 * LIKE residual: needle is SQL pattern with {@code %} stripped (substring contains),
	 * matched against UTF-8 payload of a length-prefixed string wire span (no haystack copy).
	 */
	public static boolean likeContains(byte[] fieldWire, String pattern) {
		if (isNull(fieldWire) || pattern == null) {
			return false;
		}
		return likeContainsSpan(WireSpan.ofOwned(fieldWire), pattern);
	}

	/**
	 * LIKE over a zero-copy {@link WireSpan} field (haystack not copied).
	 */
	public static boolean likeContainsSpan(WireSpan field, String pattern) {
		if (field == null || field.isNullWire() || pattern == null) {
			return false;
		}
		final String needleText = stripLikeWildcards(pattern);
		if (needleText.isEmpty()) {
			return true;
		}
		final int payloadOff;
		final int payloadLen;
		final int lenPrefix = Integer.BYTES;
		if (field.length() >= lenPrefix) {
			final int declared = SerialUtil.readI32(field.blob(), field.offset());
			if (declared == SerialUtil.STRING_NULL) {
				return false;
			}
			if (declared >= 0 && lenPrefix + declared <= field.length()) {
				payloadOff = field.offset() + lenPrefix;
				payloadLen = declared;
			} else {
				payloadOff = field.offset();
				payloadLen = field.length();
			}
		} else {
			payloadOff = field.offset();
			payloadLen = field.length();
		}
		final byte[] needle = needleText.getBytes(StandardCharsets.UTF_8);
		return indexOfRange(field.blob(), payloadOff, payloadLen, needle) >= 0;
	}

	/** Numeric aggregate from wire span (int/long/double/float); else {@code 0}. */
	public static double numeric(byte[] wire, Class<?> type) {
		if (isNull(wire) || type == null) {
			return 0d;
		}
		if (type == Integer.class || type == int.class) {
			if (wire.length < 4) {
				return 0d;
			}
			return SerialUtil.readI32(wire, 0);
		}
		if (type == Long.class || type == long.class) {
			if (wire.length < 8) {
				return 0d;
			}
			return SerialUtil.readI64(wire, 0);
		}
		if (type == Double.class || type == double.class) {
			if (wire.length < 8) {
				return 0d;
			}
			return SerialUtil.readF64(wire, 0);
		}
		if (type == Float.class || type == float.class) {
			if (wire.length < 4) {
				return 0d;
			}
			return Float.intBitsToFloat(Bits.getInt(wire, 0));
		}
		if (type == Short.class || type == short.class) {
			if (wire.length < 2) {
				return 0d;
			}
			return Bits.getShort(wire, 0);
		}
		if (type == Byte.class || type == byte.class) {
			if (wire.length < 1) {
				return 0d;
			}
			return wire[0];
		}
		return 0d;
	}

	private static String stripLikeWildcards(String pattern) {
		final int len = pattern.length();
		final StringBuilder sb = new StringBuilder(len);
		for (int i = 0; i < len; i++) {
			final char c = pattern.charAt(i);
			if (c != '%') {
				sb.append(c);
			}
		}
		return sb.toString();
	}

	/** Skip 4-byte length prefix when present; otherwise treat whole span as UTF-8 (owned copy). */
	private static byte[] utf8Payload(byte[] fieldWire) {
		if (fieldWire.length >= 4) {
			final int len = SerialUtil.readI32(fieldWire, 0);
			if (len == SerialUtil.STRING_NULL) {
				return null;
			}
			if (len >= 0 && 4 + len <= fieldWire.length) {
				final byte[] out = new byte[len];
				System.arraycopy(fieldWire, 4, out, 0, len);
				return out;
			}
		}
		return fieldWire;
	}

	private static int indexOfRange(byte[] haystack, int hayOff, int hayLen, byte[] needle) {
		if (needle.length == 0) {
			return 0;
		}
		if (needle.length > hayLen) {
			return -1;
		}
		outer:
		for (int i = 0; i <= hayLen - needle.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (haystack[hayOff + i + j] != needle[j]) {
					continue outer;
				}
			}
			return i;
		}
		return -1;
	}

	private static int indexOf(byte[] haystack, byte[] needle) {
		return indexOfRange(haystack, 0, haystack.length, needle);
	}
}