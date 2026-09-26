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
package org.genfork.grid.replication.snapshot.sealed;

import jodd.util.Bits;

import java.util.Objects;

/**
 * Wire-key compare for sealed open-range cold-merge (single-column keys).
 * <p>
 * Fixed-width INT (4) / LONG (8) match signed {@link org.genfork.grid.mem.index.btree.comparator.ArraysComparator}.
 * Other lengths stay unsigned byte order with length-last tie-break (unlike length-first BPTree).
 *
 * @author: GenCloud
 * @date: 2026/05
 * @since: 1.0
 */
public final class SealedIndexKeyOrder {
	private SealedIndexKeyOrder() {
	}

	public static int compare(byte[] left, byte[] right) {
		Objects.requireNonNull(left, "left");
		Objects.requireNonNull(right, "right");
		if (left.length == 4 && right.length == 4) {
			return Integer.compare(Bits.getInt(left, 0), Bits.getInt(right, 0));
		}
		if (left.length == 8 && right.length == 8) {
			return Long.compare(Bits.getLong(left, 0), Bits.getLong(right, 0));
		}
		final int min = Math.min(left.length, right.length);
		for (int i = 0; i < min; i++) {
			final int a = Byte.toUnsignedInt(left[i]);
			final int b = Byte.toUnsignedInt(right[i]);
			if (a != b) {
				return Integer.compare(a, b);
			}
		}
		return Integer.compare(left.length, right.length);
	}
}
