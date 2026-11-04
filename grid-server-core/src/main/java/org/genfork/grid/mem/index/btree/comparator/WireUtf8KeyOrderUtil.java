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
package org.genfork.grid.mem.index.btree.comparator;

import java.nio.ByteBuffer;

import jodd.util.Bits;

import org.genfork.grid.utils.SerialUtil;
import org.genfork.grid.utils.UnsafeMemory;

/**
 * Length-prefixed UTF-8 wire key order (payload lex, length tie-break).
 * <p>
 * Matches {@link String#compareTo} for ASCII and EXTERNAL-ORDER string compare.
 * Shape detection never applies to total length {@link #LEN_INT} (signed INT lane).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
public final class WireUtf8KeyOrderUtil {
	/** Fixed-width INT / empty-string wire total length — always signed INT in {@link ArraysComparator}. */
	public static final int LEN_INT = Integer.BYTES;
	/** Fixed-width LONG total length; utf8Len==4 strings also occupy this size. */
	public static final int LEN_LONG = Long.BYTES;
	private static final int PREFIX_BYTES = Integer.BYTES;

	private WireUtf8KeyOrderUtil() {
	}

	/**
	 * {@code true} when span is {@code [u32 BE len|STRING_NULL][payload?]} with
	 * {@code PREFIX_BYTES + declared == len} (or null marker with {@code len == PREFIX_BYTES}).
	 * Never true for {@code len == LEN_INT} callers should use signed INT instead —
	 * this method returns false for {@code len <= LEN_INT} so INT0 is not treated as empty string.
	 */
	public static boolean isUtf8PrefixedShape(byte[] wire, int off, int len) {
		if (wire == null || len <= LEN_INT || off < 0 || off + len > wire.length) {
			return false;
		}
		final int declared = Bits.getInt(wire, off);
		if (declared == SerialUtil.STRING_NULL) {
			return len == PREFIX_BYTES;
		}
		return declared >= 0 && PREFIX_BYTES + declared == len;
	}

	/**
	 * Mapped-page variant of {@link #isUtf8PrefixedShape(byte[], int, int)}.
	 */
	public static boolean isUtf8PrefixedShape(ByteBuffer buffer, int offset, int len) {
		if (buffer == null || len <= LEN_INT || offset < 0) {
			return false;
		}
		final int declared = buffer.getInt(offset);
		if (declared == SerialUtil.STRING_NULL) {
			return len == PREFIX_BYTES;
		}
		return declared >= 0 && PREFIX_BYTES + declared == len;
	}

	/**
	 * Payload-lex compare of two length-prefixed UTF-8 wires (absolute Unsafe addresses).
	 * <p>
	 * Used by EXTERNAL-ORDER {@code StringByteArrayComparator} after skipping the order-prop blob length.
	 */
	public static int compareUtf8PrefixedMemory(long addr1, int wireLen1, long addr2, int wireLen2) {
		if (wireLen1 < PREFIX_BYTES) {
			return Integer.compare(wireLen1, wireLen2);
		}
		if (wireLen2 < PREFIX_BYTES) {
			return Integer.compare(wireLen1, wireLen2);
		}
		final int strLen1 = readIntBE(addr1);
		final int strLen2 = readIntBE(addr2);
		if (strLen1 == SerialUtil.STRING_NULL) {
			return strLen2 == SerialUtil.STRING_NULL ? 0 : -1;
		}
		if (strLen2 == SerialUtil.STRING_NULL) {
			return 1;
		}
		final long p1 = addr1 + PREFIX_BYTES;
		final long p2 = addr2 + PREFIX_BYTES;
		final int n = Math.min(Math.max(0, strLen1), Math.max(0, strLen2));
		for (int i = 0; i < n; i++) {
			final int diff = Integer.compare(
					UnsafeMemory.readByte(p1 + i) & 0xFF,
					UnsafeMemory.readByte(p2 + i) & 0xFF);
			if (diff != 0) {
				return diff;
			}
		}
		return Integer.compare(strLen1, strLen2);
	}

	/**
	 * Payload-lex compare of two heap UTF-8 wires.
	 */
	public static int compareUtf8Prefixed(
			byte[] a, int aOff, int aLen,
			byte[] b, int bOff, int bLen
	) {
		if (aLen < PREFIX_BYTES) {
			return Integer.compare(aLen, bLen);
		}
		if (bLen < PREFIX_BYTES) {
			return Integer.compare(aLen, bLen);
		}
		final int strLen1 = Bits.getInt(a, aOff);
		final int strLen2 = Bits.getInt(b, bOff);
		if (strLen1 == SerialUtil.STRING_NULL) {
			return strLen2 == SerialUtil.STRING_NULL ? 0 : -1;
		}
		if (strLen2 == SerialUtil.STRING_NULL) {
			return 1;
		}
		final int p1 = aOff + PREFIX_BYTES;
		final int p2 = bOff + PREFIX_BYTES;
		final int n = Math.min(Math.max(0, strLen1), Math.max(0, strLen2));
		for (int i = 0; i < n; i++) {
			final int diff = (a[p1 + i] & 0xFF) - (b[p2 + i] & 0xFF);
			if (diff != 0) {
				return diff;
			}
		}
		return Integer.compare(strLen1, strLen2);
	}

	/**
	 * Mapped UTF-8 wire vs heap key (same payload-lex rules).
	 */
	public static int compareUtf8PrefixedMapped(
			ByteBuffer buffer, int offset, int length,
			byte[] key, int keyOff, int keyLen
	) {
		if (length < PREFIX_BYTES) {
			return Integer.compare(length, keyLen);
		}
		if (keyLen < PREFIX_BYTES) {
			return Integer.compare(length, keyLen);
		}
		final int strLen1 = buffer.getInt(offset);
		final int strLen2 = Bits.getInt(key, keyOff);
		if (strLen1 == SerialUtil.STRING_NULL) {
			return strLen2 == SerialUtil.STRING_NULL ? 0 : -1;
		}
		if (strLen2 == SerialUtil.STRING_NULL) {
			return 1;
		}
		final int p1 = offset + PREFIX_BYTES;
		final int p2 = keyOff + PREFIX_BYTES;
		final int n = Math.min(Math.max(0, strLen1), Math.max(0, strLen2));
		for (int i = 0; i < n; i++) {
			final int diff = (buffer.get(p1 + i) & 0xFF) - (key[p2 + i] & 0xFF);
			if (diff != 0) {
				return diff;
			}
		}
		return Integer.compare(strLen1, strLen2);
	}

	/**
	 * Unsigned lexicographic byte order (common prefix, then length) — non-string varlen keys.
	 */
	public static int compareUnsignedLex(
			byte[] a, int aOff, int aLen,
			byte[] b, int bOff, int bLen
	) {
		final int n = Math.min(aLen, bLen);
		for (int i = 0; i < n; i++) {
			final int diff = (a[aOff + i] & 0xFF) - (b[bOff + i] & 0xFF);
			if (diff != 0) {
				return diff;
			}
		}
		return Integer.compare(aLen, bLen);
	}

	/**
	 * Mapped vs heap unsigned lex.
	 */
	public static int compareUnsignedLexMapped(
			ByteBuffer buffer, int offset, int length,
			byte[] key, int keyOff, int keyLen
	) {
		final int n = Math.min(length, keyLen);
		for (int i = 0; i < n; i++) {
			final int diff = (buffer.get(offset + i) & 0xFF) - (key[keyOff + i] & 0xFF);
			if (diff != 0) {
				return diff;
			}
		}
		return Integer.compare(length, keyLen);
	}

	private static int readIntBE(long addr) {
		return ((UnsafeMemory.readByte(addr) & 0xFF) << 24)
				| ((UnsafeMemory.readByte(addr + 1) & 0xFF) << 16)
				| ((UnsafeMemory.readByte(addr + 2) & 0xFF) << 8)
				| (UnsafeMemory.readByte(addr + 3) & 0xFF);
	}
}
