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

import java.util.Arrays;
import java.util.Random;

import org.genfork.grid.utils.ArrayUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden and differential checks for vector byte-array operations.
 *
 * @author: GenCloud
 * @date: 2025/02
 * @since: 1.0
 */
public class ArrayUtilVectorTest {
	private static final int[] LENGTHS = {0, 1, 7, 8, 31, 64, 255, 256, 1_024, 4_096};
	private static final long RANDOM_SEED = 0x5A17C0DEL;
	private static final int RANDOM_CASES = 100;

	@Test
	void fastHashRetainsPolynomialGoldenValues() {
		assertEquals(1, ArrayUtil.fastHash(new byte[0]));
		assertEquals(31, ArrayUtil.fastHash(new byte[]{0}));
		assertEquals(991, ArrayUtil.fastHash(new byte[]{1, -1}));
	}

	@Test
	void vectorOperationsMatchJdkAcrossRemainders() {
		final Random random = new Random(RANDOM_SEED);
		for (int length : LENGTHS) {
			for (int iteration = 0; iteration < RANDOM_CASES; iteration++) {
				final byte[] left = new byte[length];
				random.nextBytes(left);
				final byte[] equal = left.clone();
				assertTrue(ArrayUtil.bytesEqual(left, equal));
				assertEquals(0, ArrayUtil.compareUnsigned(left, equal));
				assertEquals(Arrays.hashCode(left), ArrayUtil.fastHash(left));
				if (length > 0) {
					final byte[] changed = left.clone();
					changed[iteration % length] ^= 1;
					assertFalse(ArrayUtil.bytesEqual(left, changed));
					assertEquals(Integer.signum(Arrays.compareUnsigned(left, changed)),
							Integer.signum(ArrayUtil.compareUnsigned(left, changed)));
				}
			}
		}
	}

	@Test
	void equalityHandlesNullAndLengthMismatch() {
		assertTrue(ArrayUtil.bytesEqual(null, null));
		assertFalse(ArrayUtil.bytesEqual(null, new byte[0]));
		assertFalse(ArrayUtil.bytesEqual(new byte[]{1}, new byte[]{1, 0}));
		assertTrue(ArrayUtil.compareUnsigned(new byte[]{-1}, new byte[]{0}) > 0);
	}
}
