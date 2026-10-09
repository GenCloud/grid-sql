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

import jodd.util.Bits;

import java.util.Comparator;

/**
 * Wire-key ordering for BPTree / residual compare.
 * <p>
 * Fixed-width INT (4) and LONG (8) keys use <strong>signed</strong>
 * {@link Integer#compare} / {@link Long#compare} so SQL predicates such as
 * {@code WHERE id > -1} match two's-complement semantics (unsigned order treated
 * {@code -1} as {@code 0xFFFFFFFF} and returned empty ranges). Variable-length
 * keys stay unsigned byte order.
 * <p>
 * Trees already containing negative INT/LONG keys that were built under the old
 * unsigned order need an index rebuild / reseal after upgrade.
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
	 * Same signed INT/LONG / unsigned variable rules as {@link #compare(byte[], byte[])},
	 * over {@code a[aOff .. aOff+aLen)} vs {@code b[bOff .. bOff+bLen)}.
	 */
	public int compare(byte[] a, int aOff, int aLen, byte[] b, int bOff, int bLen) {
		if (a == null) {
			return b == null ? 0 : 1;
		}
		if (b == null) {
			return -1;
		}
		if (aLen == 4 && bLen == 4) {
			return Integer.compare(Bits.getInt(a, aOff), Bits.getInt(b, bOff));
		}
		if (aLen == 8 && bLen == 8) {
			return Long.compare(Bits.getLong(a, aOff), Bits.getLong(b, bOff));
		}

		final int lenDiff = aLen - bLen;
		if (lenDiff != 0) {
			return lenDiff;
		}

		for (int i = 0; i < aLen; i++) {
			final int diff = (a[aOff + i] & 0xFF) - (b[bOff + i] & 0xFF);
			if (diff != 0) {
				return diff;
			}
		}
		return 0;
	}
}
