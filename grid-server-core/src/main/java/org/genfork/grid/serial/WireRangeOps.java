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

/**
 * Range hash / equals for wire byte spans (same polynomial as {@link java.util.Arrays#hashCode(byte[])}).
 * <p>
 * Shared by {@link WireSpan} and owned-copy {@link WireFieldBytes} — one algorithm, no duplication.
 *
 * @author: GenCloud
 * @date: 2025/06
 * @since: 1.0
 */
public final class WireRangeOps {
	/** Seed matching {@link java.util.Arrays#hashCode(byte[])}. */
	private static final int HASH_SEED = 1;

	/** Multiplier matching {@link java.util.Arrays#hashCode(byte[])}. */
	private static final int HASH_MULTIPLIER = 31;

	private WireRangeOps() {
	}

	/**
	 * Hash of {@code blob[offset .. offset+length)}.
	 */
	public static int hash(byte[] blob, int offset, int length) {
		if (blob == null || length <= 0) {
			return HASH_SEED;
		}
		int h = HASH_SEED;
		final int end = offset + length;
		for (int i = offset; i < end; i++) {
			h = HASH_MULTIPLIER * h + blob[i];
		}
		return h;
	}

	/**
	 * Equality of two ranges (length and content).
	 */
	public static boolean equals(
			byte[] left,
			int leftOff,
			int leftLen,
			byte[] right,
			int rightOff,
			int rightLen
	) {
		if (leftLen != rightLen) {
			return false;
		}
		if (left == right && leftOff == rightOff) {
			return true;
		}
		if (left == null || right == null) {
			return leftLen == 0;
		}
		for (int i = 0; i < leftLen; i++) {
			if (left[leftOff + i] != right[rightOff + i]) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Owned copy of a range (persistence / store-key boundary).
	 */
	public static byte[] copyOf(byte[] blob, int offset, int length) {
		if (blob == null || length <= 0) {
			return new byte[0];
		}
		final byte[] out = new byte[length];
		System.arraycopy(blob, offset, out, 0, length);
		return out;
	}
}
