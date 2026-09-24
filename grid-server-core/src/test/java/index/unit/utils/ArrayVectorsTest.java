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
package index.unit.utils;

import org.genfork.grid.utils.ArrayUtil;
import org.genfork.grid.utils.ArrayVectors;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden tests for {@link ArrayVectors} kernels.
 *
 * @author: GenCloud
 * @date: 2025/02
 * @since: 1.0
 */
class ArrayVectorsTest {
	private static final int[] EQUAL_LENGTHS = {0, 1, 7, 16, 63, 128, 1024};
	private static final int[] COMPARE_LENGTHS = {1, 3, 5, 7, 9, 15, 17, 31, 63};
	private static final int[] CRC32_LENGTHS = {0, 1, 8, 63, 128, 1024, 4096};
	private static final long RANDOM_SEED = 42L;

	@Test
	void bytesEqualMatchesArrays() {
		final Random rnd = new Random(RANDOM_SEED);
		for (int len : EQUAL_LENGTHS) {
			final byte[] a = new byte[len];
			rnd.nextBytes(a);
			final byte[] b = Arrays.copyOf(a, len);
			assertTrue(ArrayVectors.bytesEqual(a, b));
			if (len > 0) {
				b[len / 2] ^= 1;
				assertFalse(ArrayVectors.bytesEqual(a, b));
			}
		}
	}

	@Test
	void compareUnsignedMatchesScalarForOddLengths() {
		final Random rnd = new Random(RANDOM_SEED);
		for (int len : COMPARE_LENGTHS) {
			final byte[] a = new byte[len];
			final byte[] b = new byte[len];
			rnd.nextBytes(a);
			rnd.nextBytes(b);
			assertEquals(scalarCompareUnsigned(a, b), ArrayVectors.compareUnsigned(a, b));
			assertEquals(Arrays.compareUnsigned(a, b), ArrayVectors.compareUnsigned(a, b));

			final byte[] equalCopy = Arrays.copyOf(a, len);
			assertEquals(0, ArrayVectors.compareUnsigned(a, equalCopy));

			equalCopy[len - 1] = (byte) (a[len - 1] ^ 0xff);
			assertEquals(scalarCompareUnsigned(a, equalCopy), ArrayVectors.compareUnsigned(a, equalCopy));
		}
	}

	@Test
	void crc32MatchesJavaUtilZip() {
		final Random rnd = new Random(RANDOM_SEED);
		for (int len : CRC32_LENGTHS) {
			final byte[] data = new byte[len];
			if (len > 0) {
				rnd.nextBytes(data);
			}
			final CRC32 reference = new CRC32();
			if (len > 0) {
				reference.update(data);
			}
			assertEquals((int) reference.getValue(), ArrayVectors.crc32(data));
		}
		assertEquals(0, ArrayVectors.crc32(null));
	}

	@Test
	void updateCrc32MatchesChunkedUpdate() {
		final byte[] data = new byte[257];
		new Random(RANDOM_SEED).nextBytes(data);
		final CRC32 expected = new CRC32();
		expected.update(data);
		final CRC32 actual = new CRC32();
		ArrayVectors.updateCrc32(actual, data);
		assertEquals(expected.getValue(), actual.getValue());
	}

	@Test
	void fastHashCompatibleDelegates() {
		final byte[] a = new byte[]{1, 2, 3, 4, 5};
		assertEquals(ArrayUtil.fastHash(a), ArrayVectors.fastHashCompatible(a));
	}

	private static int scalarCompareUnsigned(byte[] left, byte[] right) {
		final int min = Math.min(left.length, right.length);
		for (int i = 0; i < min; i++) {
			final int c = Byte.compareUnsigned(left[i], right[i]);
			if (c != 0) {
				return c;
			}
		}
		return Integer.compare(left.length, right.length);
	}
}