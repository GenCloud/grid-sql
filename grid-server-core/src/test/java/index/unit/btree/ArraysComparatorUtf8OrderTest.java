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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import jodd.util.Bits;
import org.genfork.grid.mem.index.btree.comparator.ArraysComparator;
import org.genfork.grid.mem.index.btree.comparator.WireUtf8KeyOrderUtil;
import org.genfork.grid.replication.snapshot.sealed.SealedIndexKeyOrder;
import org.genfork.grid.serial.SqlWireUtil;
import org.genfork.grid.serial.WireFieldCompare;
import org.genfork.grid.utils.SerialUtil;
import org.junit.jupiter.api.Test;

/**
 * VARCHAR length-prefixed wire order == {@link String#compareTo} / payload-lex.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
class ArraysComparatorUtf8OrderTest {
	private static final ArraysComparator COMPARATOR = new ArraysComparator();

	@Test
	void name9VsName10MatchesStringCompareTo() {
		final byte[] name9 = SqlWireUtil.toGenericArray("Name-9");
		final byte[] name10 = SqlWireUtil.toGenericArray("Name-10");
		final int expected = "Name-9".compareTo("Name-10");
		assertEquals(Integer.signum(expected), Integer.signum(COMPARATOR.compare(name9, name10)));
		assertEquals(Integer.signum(expected), Integer.signum(WireFieldCompare.compare(name9, name10)));
		assertEquals(Integer.signum(expected), Integer.signum(SealedIndexKeyOrder.compare(name9, name10)));
		assertTrue(name9.length != name10.length, "wires must differ in total length (lab bug shape)");
	}

	@Test
	void utf8LenFourDoesNotUseSignedLongLane() {
		final byte[] aaaa = SqlWireUtil.toGenericArray("AAAA");
		final byte[] aaab = SqlWireUtil.toGenericArray("AAAB");
		assertEquals(WireUtf8KeyOrderUtil.LEN_LONG, aaaa.length);
		assertEquals(WireUtf8KeyOrderUtil.LEN_LONG, aaab.length);
		assertTrue(WireUtf8KeyOrderUtil.isUtf8PrefixedShape(aaaa, 0, aaaa.length));
		assertEquals(Integer.signum("AAAA".compareTo("AAAB")), Integer.signum(COMPARATOR.compare(aaaa, aaab)));
	}

	@Test
	void signedIntAndLongUnchanged() {
		final byte[] neg = intBytes(-1);
		final byte[] zero = intBytes(0);
		final byte[] one = intBytes(1);
		assertTrue(COMPARATOR.compare(one, neg) > 0);
		assertTrue(COMPARATOR.compare(neg, zero) < 0);
		final byte[] longNeg = longBytes(-1L);
		final byte[] longOne = longBytes(1L);
		assertTrue(COMPARATOR.compare(longOne, longNeg) > 0);
	}

	@Test
	void mappedCompareMatchesHeap() {
		final byte[] name9 = SqlWireUtil.toGenericArray("Name-9");
		final byte[] name10 = SqlWireUtil.toGenericArray("Name-10");
		final ByteBuffer buf = ByteBuffer.allocateDirect(name9.length).order(ByteOrder.BIG_ENDIAN);
		buf.put(0, name9);
		assertEquals(
				COMPARATOR.compare(name9, name10),
				COMPARATOR.compareMapped(buf, 0, name9.length, name10));
	}

	@Test
	void stringNullWireOrdersBeforePayload() {
		final byte[] nullWire = new byte[Integer.BYTES];
		Bits.putInt(nullWire, 0, SerialUtil.STRING_NULL);
		final byte[] empty = SqlWireUtil.toGenericArray("");
		// empty is 4-byte [0,0,0,0] → signed INT lane; null marker is also 4-byte INT -1
		assertTrue(COMPARATOR.compare(nullWire, empty) < 0);
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
