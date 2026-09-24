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

import java.util.Locale;

/**
 * Hex encode / decode helpers for compact binary sidecars (e.g. sequence reclaim masks).
 *
 * @author: GenCloud
 * @date: 2025/02
 * @since: 1.0
 */
public final class HexBytes {
	private static final char[] HEX_DIGITS = {
			'0', '1', '2', '3', '4', '5', '6', '7',
			'8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
	};
	private static final int RADIX_HEX = 16;
	private static final int NIBBLE_BITS = 4;
	private static final int NIBBLE_MASK = 0x0f;
	private static final int BYTE_MASK = 0xff;

	private HexBytes() {
	}

	/**
	 * Lower-case hex (empty string for null / empty input).
	 */
	public static String byteToHex(byte[] data) {
		if (data == null || data.length == 0) {
			return "";
		}
		final char[] out = new char[data.length * 2];
		int o = 0;
		for (byte b : data) {
			final int v = b & BYTE_MASK;
			out[o++] = HEX_DIGITS[v >>> NIBBLE_BITS];
			out[o++] = HEX_DIGITS[v & NIBBLE_MASK];
		}
		return new String(out);
	}

	/** Alias for {@link #byteToHex(byte[])}. */
	public static String toHex(byte[] data) {
		return byteToHex(data);
	}

	/**
	 * Decode lower/upper hex; odd length or bad digits throw {@link IllegalArgumentException}.
	 */
	public static byte[] hexToBytes(String hex) {
		if (hex == null || hex.isEmpty()) {
			return new byte[0];
		}
		final int len = hex.length();
		if ((len & 1) != 0) {
			throw new IllegalArgumentException("odd hex length");
		}
		final byte[] out = new byte[len / 2];
		for (int i = 0; i < out.length; i++) {
			final int hi = Character.digit(hex.charAt(i * 2), RADIX_HEX);
			final int lo = Character.digit(hex.charAt(i * 2 + 1), RADIX_HEX);
			if (hi < 0 || lo < 0) {
				throw new IllegalArgumentException("bad hex at index " + (i * 2));
			}
			out[i] = (byte) ((hi << NIBBLE_BITS) + lo);
		}
		return out;
	}

	/** Alias for {@link #hexToBytes(String)}. */
	public static byte[] fromHex(String hex) {
		return hexToBytes(hex);
	}

	/**
	 * Domain / path-safe hex of a 32-bit hash ({@link Integer#toHexString} style).
	 */
	public static String toHexInt(int value) {
		return Integer.toHexString(value);
	}

	/**
	 * Lower-case hex of a long.
	 */
	public static String toHexLong(long value) {
		return Long.toHexString(value).toLowerCase(Locale.ROOT);
	}
}