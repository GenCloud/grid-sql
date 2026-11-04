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
import java.util.Comparator;

import jodd.util.Bits;

/**
 * Wire-key ordering for BPTree / residual / sealed compare.
 * <p>
 * Fixed-width INT ({@link WireUtf8KeyOrderUtil#LEN_INT}) and LONG
 * ({@link WireUtf8KeyOrderUtil#LEN_LONG}) use <strong>signed</strong>
 * {@link Integer#compare} / {@link Long#compare}. Length-prefixed UTF-8 string
 * wires (total length &gt; 4, or utf8Len==4 occupying 8 bytes) use payload-lex
 * via {@link WireUtf8KeyOrderUtil}. Other variable-length keys use unsigned
 * lexicographic byte order (common prefix, then length).
 * <p>
 * Trees / {@code .sbpt} built under prior length-first varlen order need
 * reseal ({@code VERSION 3}).
 *
 * @author: GenCloud
 * @date: 2025/04
 * @since: 1.0
 */
public class ArraysComparator implements Comparator<byte[]> {
	@Override
	public int compare(byte[] o1, byte[] o2) {
		if (o1 == o2) {
			return 0;
		}
		if (o1 == null) {
			return 1;
		}
		if (o2 == null) {
			return -1;
		}
		return compare(o1, 0, o1.length, o2, 0, o2.length);
	}

	/**
	 * Same signed INT/LONG / UTF-8 / unsigned-lex rules as {@link #compare(byte[], byte[])},
	 * over {@code a[aOff .. aOff+aLen)} vs {@code b[bOff .. bOff+bLen)}.
	 */
	public int compare(byte[] a, int aOff, int aLen, byte[] b, int bOff, int bLen) {
		if (a == null) {
			return b == null ? 0 : 1;
		}
		if (b == null) {
			return -1;
		}
		if (aLen == WireUtf8KeyOrderUtil.LEN_INT && bLen == WireUtf8KeyOrderUtil.LEN_INT) {
			return Integer.compare(Bits.getInt(a, aOff), Bits.getInt(b, bOff));
		}
		if (aLen == WireUtf8KeyOrderUtil.LEN_LONG && bLen == WireUtf8KeyOrderUtil.LEN_LONG) {
			if (WireUtf8KeyOrderUtil.isUtf8PrefixedShape(a, aOff, aLen)
					&& WireUtf8KeyOrderUtil.isUtf8PrefixedShape(b, bOff, bLen)) {
				return WireUtf8KeyOrderUtil.compareUtf8Prefixed(a, aOff, aLen, b, bOff, bLen);
			}
			return Long.compare(Bits.getLong(a, aOff), Bits.getLong(b, bOff));
		}
		if (WireUtf8KeyOrderUtil.isUtf8PrefixedShape(a, aOff, aLen)
				&& WireUtf8KeyOrderUtil.isUtf8PrefixedShape(b, bOff, bLen)) {
			return WireUtf8KeyOrderUtil.compareUtf8Prefixed(a, aOff, aLen, b, bOff, bLen);
		}
		return WireUtf8KeyOrderUtil.compareUnsignedLex(a, aOff, aLen, b, bOff, bLen);
	}

	/**
	 * Mapped sealed page key vs heap key — identical contract to
	 * {@link #compare(byte[], int, int, byte[], int, int)}.
	 */
	public int compareMapped(ByteBuffer buffer, int offset, int length, byte[] key) {
		if (key == null) {
			return -1;
		}
		final int keyLen = key.length;
		if (length == WireUtf8KeyOrderUtil.LEN_INT && keyLen == WireUtf8KeyOrderUtil.LEN_INT) {
			final int a = buffer.getInt(offset);
			final int b = Bits.getInt(key, 0);
			return Integer.compare(a, b);
		}
		if (length == WireUtf8KeyOrderUtil.LEN_LONG && keyLen == WireUtf8KeyOrderUtil.LEN_LONG) {
			if (WireUtf8KeyOrderUtil.isUtf8PrefixedShape(buffer, offset, length)
					&& WireUtf8KeyOrderUtil.isUtf8PrefixedShape(key, 0, keyLen)) {
				return WireUtf8KeyOrderUtil.compareUtf8PrefixedMapped(buffer, offset, length, key, 0, keyLen);
			}
			final long a = buffer.getLong(offset);
			return Long.compare(a, Bits.getLong(key, 0));
		}
		if (WireUtf8KeyOrderUtil.isUtf8PrefixedShape(buffer, offset, length)
				&& WireUtf8KeyOrderUtil.isUtf8PrefixedShape(key, 0, keyLen)) {
			return WireUtf8KeyOrderUtil.compareUtf8PrefixedMapped(buffer, offset, length, key, 0, keyLen);
		}
		return WireUtf8KeyOrderUtil.compareUnsignedLexMapped(buffer, offset, length, key, 0, keyLen);
	}
}
