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
package index.unit.serial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.genfork.grid.serial.WireFieldBytes;
import org.genfork.grid.serial.WireRangeOps;
import org.genfork.grid.serial.WireSpan;
import org.junit.jupiter.api.Test;

/**
 * WireSpan zero-copy hash/equals vs owned WireFieldBytes.
 *
 * @author: GenCloud
 * @date: 2025/06
 * @since: 1.0
 */
class WireSpanTest {

	@Test
	void spanEqualsOwnedCopy() {
		final byte[] blob = new byte[]{0, 1, 2, 3, 4, 5, 6, 7};
		final WireSpan span = WireSpan.wrapForTest(blob, 2, 4);
		final WireFieldBytes owned = new WireFieldBytes(Arrays.copyOfRange(blob, 2, 6));
		assertEquals(span.hashCode(), owned.hashCode());
		assertEquals(span, owned);
		assertEquals(owned, span);
		assertTrue(span.equals(WireFieldBytes.copyOf(span)));
	}

	@Test
	void rangeHashMatchesArrays() {
		final byte[] blob = new byte[]{9, 8, 7, 6, 5};
		final int off = 1;
		final int len = 3;
		final byte[] copy = Arrays.copyOfRange(blob, off, off + len);
		assertEquals(Arrays.hashCode(copy), WireRangeOps.hash(blob, off, len));
	}

	@Test
	void nullSpanRecognized() {
		assertTrue(WireSpan.nullSpan().isNullWire());
		assertFalse(WireSpan.of(new byte[]{1, 2}, 0, 2).isNullWire());
	}

	@Test
	void differentRangesNotEqual() {
		final byte[] blob = new byte[]{1, 2, 3, 4};
		assertNotEquals(WireSpan.of(blob, 0, 2), WireSpan.of(blob, 2, 2));
	}
}