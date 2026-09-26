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
package index.unit.btree;

import jodd.util.Bits;
import org.genfork.grid.mem.index.btree.comparator.ArraysComparator;
import org.genfork.grid.serial.WireFieldCompare;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Signed INT/LONG wire order for index + residual compare.
 *
 * @author: GenCloud
 * @date: 2025/04
 * @since: 1.0
 */
class ArraysComparatorSignedTest {
	private static final ArraysComparator COMPARATOR = new ArraysComparator();

	@Test
	void signedIntOrderMatchesIntegerCompare() {
		final byte[] negOne = intBytes(-1);
		final byte[] zero = intBytes(0);
		final byte[] one = intBytes(1);
		assertTrue(COMPARATOR.compare(one, negOne) > 0);
		assertTrue(COMPARATOR.compare(negOne, zero) < 0);
		assertTrue(COMPARATOR.compare(negOne, one) < 0);
		assertTrue(WireFieldCompare.compare(one, negOne) > 0);
		assertTrue(WireFieldCompare.compare(negOne, zero) < 0);
	}

	@Test
	void signedLongOrderMatchesLongCompare() {
		final byte[] negOne = longBytes(-1L);
		final byte[] zero = longBytes(0L);
		final byte[] one = longBytes(1L);
		assertTrue(COMPARATOR.compare(one, negOne) > 0);
		assertTrue(COMPARATOR.compare(negOne, zero) < 0);
		assertTrue(COMPARATOR.compare(negOne, one) < 0);
	}

	private static byte[] intBytes(int value) {
		final byte[] out = new byte[Integer.BYTES];
		Bits.putInt(out, 0, value);
		return out;
	}

	private static byte[] longBytes(long value) {
		final byte[] out = new byte[Long.BYTES];
		Bits.putLong(out, 0, value);
		return out;
	}
}