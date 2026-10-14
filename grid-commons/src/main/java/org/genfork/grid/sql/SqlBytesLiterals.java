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

/**
 * SQL-standard {@code X'hex'} binary literals (format + parse).
 * <p>
 * Shared by bind materialize, JDBC updatable {@code sqlLiteral}, and ANTLR decode.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
public final class SqlBytesLiterals {
	private static final char HEX_PREFIX = 'X';
	private static final char QUOTE = '\'';
	private static final String EMPTY_X_HEX = "X''";
	private static final char[] HEX_DIGITS = {
			'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'
	};
	private static final String ERR_NULL_BYTES = "bytes";
	private static final String ERR_NULL_HEX = "hex";
	private static final String ERR_ODD_HEX = "hex length must be even: ";
	private static final String ERR_BAD_HEX = "invalid hex digit in: ";
	private static final String ERR_BAD_X_TOKEN = "expected X'hex' token, got: ";
	private static final int X_PREFIX_LEN = 2;
	private static final int X_SUFFIX_LEN = 1;

	private SqlBytesLiterals() {
	}

	/**
	 * Format {@code bytes} as {@code X'AABB…'} (uppercase hex). Empty → {@code X''}.
	 */
	public static String formatXHex(byte[] bytes) {
		if (bytes == null) {
			throw new IllegalArgumentException(ERR_NULL_BYTES);
		}
		if (bytes.length == 0) {
			return EMPTY_X_HEX;
		}
		final StringBuilder sb = new StringBuilder(bytes.length * 2 + 3);
		sb.append(HEX_PREFIX).append(QUOTE);
		for (int i = 0; i < bytes.length; i++) {
			final int v = bytes[i] & 0xff;
			sb.append(HEX_DIGITS[v >>> 4]);
			sb.append(HEX_DIGITS[v & 0x0f]);
		}
		sb.append(QUOTE);
		return sb.toString();
	}

	/**
	 * Parse bare hex digits (no {@code X'/'} wrappers) into bytes. Empty → empty array.
	 */
	public static byte[] parseHex(String hexDigits) {
		if (hexDigits == null) {
			throw new IllegalArgumentException(ERR_NULL_HEX);
		}
		final int len = hexDigits.length();
		if ((len & 1) != 0) {
			throw new IllegalArgumentException(ERR_ODD_HEX + len);
		}
		final byte[] out = new byte[len / 2];
		for (int i = 0; i < out.length; i++) {
			final int hi = hexNibble(hexDigits.charAt(i * 2), hexDigits);
			final int lo = hexNibble(hexDigits.charAt(i * 2 + 1), hexDigits);
			out[i] = (byte) ((hi << 4) | lo);
		}
		return out;
	}

	/**
	 * Parse a full lexer token {@code X'…'} / {@code x'…'} into bytes.
	 */
	public static byte[] parseXHexToken(String token) {
		if (token == null || token.length() < X_PREFIX_LEN + X_SUFFIX_LEN) {
			throw new IllegalArgumentException(ERR_BAD_X_TOKEN + token);
		}
		final char first = token.charAt(0);
		if ((first != 'X' && first != 'x')
				|| token.charAt(1) != QUOTE
				|| token.charAt(token.length() - 1) != QUOTE) {
			throw new IllegalArgumentException(ERR_BAD_X_TOKEN + token);
		}
		return parseHex(token.substring(X_PREFIX_LEN, token.length() - X_SUFFIX_LEN));
	}

	private static int hexNibble(char c, String source) {
		if (c >= '0' && c <= '9') {
			return c - '0';
		}
		if (c >= 'A' && c <= 'F') {
			return 10 + (c - 'A');
		}
		if (c >= 'a' && c <= 'f') {
			return 10 + (c - 'a');
		}
		throw new IllegalArgumentException(ERR_BAD_HEX + source);
	}
}
