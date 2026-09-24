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

		final int len1 = o1.length;
		final int len2 = o2.length;
		if (len1 == 4 && len2 == 4) {
			return Integer.compareUnsigned(Bits.getInt(o1, 0), Bits.getInt(o2, 0));
		}
		if (len1 == 8 && len2 == 8) {
			return Long.compareUnsigned(Bits.getLong(o1, 0), Bits.getLong(o2, 0));
		}

		final int lenDiff = len1 - len2;
		if (lenDiff != 0) {
			return lenDiff;
		}

		for (int i = 0; i < len1; i++) {
			final int diff = (o1[i] & 0xFF) - (o2[i] & 0xFF);
			if (diff != 0) {
				return diff;
			}
		}

		return 0;
	}
}
