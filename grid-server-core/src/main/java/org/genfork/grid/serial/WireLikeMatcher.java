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

import com.google.common.annotations.VisibleForTesting;

import jodd.util.Bits;

import org.genfork.grid.utils.SerialUtil;

/**
 * SQL {@code LIKE} matching on wire UTF-8 string payloads ({@code %}/{@code _} wildcards).
 * <p>
 * Operates on {@code byte[]} mid-pipeline — no {@link String} decode. Shared by RAM BPTree
 * and sealed {@code .sbpt} cold-path merge.
 *
 * @author: GenCloud
 * @date: 2025/06
 * @since: 1.0
 */
public final class WireLikeMatcher {
	private static final byte WILDCARD_ANY = (byte) '%';
	private static final byte WILDCARD_ONE = (byte) '_';
	private static final int WIRE_LEN_BYTES = Integer.BYTES;

	private WireLikeMatcher() {
	}

	/**
	 * Match a wire-encoded string key against a wire-encoded LIKE pattern.
	 */
	public static boolean matchesWire(byte[] wireKey, byte[] wirePattern) {
		return matches(utf8Payload(wireKey), utf8Payload(wirePattern));
	}

	/**
	 * Match UTF-8 payloads (no wire length header) with {@code %}/{@code _} semantics.
	 */
	public static boolean matches(byte[] bytesKey, byte[] patternKey) {
		if (bytesKey == null || patternKey == null) {
			return false;
		}
		return matchesAt(bytesKey, 0, patternKey, 0);
	}

	/**
	 * When the pattern is a literal prefix followed by a single trailing {@code %},
	 * return that literal; otherwise {@code null}. Used for fast-path starts-with checks.
	 */
	@VisibleForTesting
	public static byte[] trailingPercentLiteral(byte[] patternPayload) {
		if (patternPayload == null || patternPayload.length == 0) {
			return null;
		}
		if (patternPayload[patternPayload.length - 1] != WILDCARD_ANY) {
			return null;
		}
		for (int i = 0; i < patternPayload.length - 1; i++) {
			final byte b = patternPayload[i];
			if (b == WILDCARD_ANY || b == WILDCARD_ONE) {
				return null;
			}
		}
		if (patternPayload.length == 1) {
			return new byte[0];
		}
		final byte[] literal = new byte[patternPayload.length - 1];
		System.arraycopy(patternPayload, 0, literal, 0, literal.length);
		return literal;
	}

	/**
	 * Extract UTF-8 payload from a SerialUtil length-prefixed string wire, or return the
	 * raw bytes when the buffer is too short / not a string header.
	 */
	public static byte[] utf8Payload(byte[] wire) {
		if (wire == null) {
			return new byte[0];
		}
		if (wire.length < WIRE_LEN_BYTES) {
			return wire;
		}
		final int len = Bits.getInt(wire, 0);
		if (len == SerialUtil.STRING_NULL || len <= 0) {
			return new byte[0];
		}
		if (WIRE_LEN_BYTES + len > wire.length) {
			return wire;
		}
		final byte[] out = new byte[len];
		System.arraycopy(wire, WIRE_LEN_BYTES, out, 0, len);
		return out;
	}

	private static boolean matchesAt(byte[] bytesKey, int from, byte[] patternKey, int to) {
		final byte[] trailingLiteral = to == 0 ? trailingPercentLiteral(patternKey) : null;
		if (trailingLiteral != null) {
			return startsWith(bytesKey, from, trailingLiteral);
		}
		if (to == patternKey.length) {
			return from == bytesKey.length;
		}
		if (patternKey[to] == WILDCARD_ANY) {
			for (int i = from; i <= bytesKey.length; i++) {
				if (matchesAt(bytesKey, i, patternKey, to + 1)) {
					return true;
				}
			}
			return false;
		}
		if (patternKey[to] == WILDCARD_ONE) {
			if (from < bytesKey.length) {
				return matchesAt(bytesKey, from + 1, patternKey, to + 1);
			}
			return false;
		}
		if (from < bytesKey.length && bytesKey[from] == patternKey[to]) {
			return matchesAt(bytesKey, from + 1, patternKey, to + 1);
		}
		return false;
	}

	private static boolean startsWith(byte[] bytesKey, int from, byte[] literal) {
		if (from + literal.length > bytesKey.length) {
			return false;
		}
		for (int i = 0; i < literal.length; i++) {
			if (bytesKey[from + i] != literal[i]) {
				return false;
			}
		}
		return true;
	}
}
