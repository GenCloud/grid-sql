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
package index.unit.bptree;

import org.genfork.grid.exceptions.NonUniqueValueException;
import org.genfork.grid.mem.index.IndexPointerRef;
import org.genfork.grid.mem.index.btree.BPValueHelper;
import org.genfork.grid.mem.index.btree.IndexOperationResult;
import org.genfork.grid.mem.index.btree.SingleTreeKey;
import org.genfork.grid.serial.SqlWireUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * @author: GenCloud
 * @date: 2026/12
 * @since: 1.0
 */
public class GridPointerBPTreeStringValueTest extends AbstractGridPointerBPTreeTest {
	private static final String[] VALUES = {
			"number",
			"string",
			"date",
			"package",
			"scanning",
			"locate",
			"future ",
			"dEbuGGing",
			"practices",
			"Test",
	};

	private int value;

	@Override
	protected int fillCount() {
		return 10;
	}

	@Override
	protected Object computeValue() {
		return VALUES[value++];
	}

	@Test
	public void _01_expect_equal() {
		final byte[] valueArray = SqlWireUtil.toGenericArray(VALUES[2]);
		final IndexOperationResult result = laxTree.searchEq(new SingleTreeKey(valueArray));
		Assertions.assertEquals(1, result.getPointers().size());
		Assertions.assertArrayEquals(getFirstPointerValue(result.getPointers()), valueArray);
	}

	@Test
	public void _02_expect_not_equal() {
		final byte[] valueArray = SqlWireUtil.toGenericArray(VALUES[2]);
		final IndexOperationResult result = laxTree.searchNotEq(new SingleTreeKey(valueArray));
		Assertions.assertEquals(9, result.getPointers().size());
		Assertions.assertFalse(matchAnyPointers(result.getPointers(), valueArray));
	}

	@Test
	public void _03_expect_range() {
		final byte[] lo = SqlWireUtil.toGenericArray(VALUES[2]);
		final byte[] hi = SqlWireUtil.toGenericArray(VALUES[6]);
		final IndexOperationResult result = laxTree.searchRange(new SingleTreeKey(lo), new SingleTreeKey(hi));
		Assertions.assertEquals(5, result.getPointers().size());
		Assertions.assertArrayEquals(getFirstPointerValue(result.getPointers()), lo);
		Assertions.assertArrayEquals(getLastPointerValue(result.getPointers()), hi);
	}

	@Test
	public void _04_expect_not_exists() {
		final byte[] valueArray = SqlWireUtil.toGenericArray("____");
		final IndexOperationResult result = laxTree.searchEq(new SingleTreeKey(valueArray));
		Assertions.assertNull(result.getPointers());
	}

	@Test
	public void _05_expect_delete_and_not_exists() {
		final byte[] valueArray = SqlWireUtil.toGenericArray(VALUES[9]);
		laxTree.delete(new SingleTreeKey(valueArray));

		final IndexOperationResult result = laxTree.searchEq(new SingleTreeKey(valueArray));
		Assertions.assertNull(result.getPointers());
	}

	@Test
	public void _06_expect_like() {
		final byte[] valueArray = SqlWireUtil.toGenericArray("%buG%");
		final IndexOperationResult result = laxTree.searchLike(new SingleTreeKey(valueArray));
		Assertions.assertEquals(1, result.getPointers().size());

		Assertions.assertEquals(VALUES[7], "dEbuGGing");

		final byte[] expectedArray = SqlWireUtil.toGenericArray(VALUES[7]);
		Assertions.assertArrayEquals(getFirstPointerValue(result.getPointers()), expectedArray);
	}

	@Test
	public void _07_expect_strict_append_error() {
		final byte[] keyArray = SqlWireUtil.toGenericArray(0x586458ab);
		final byte[] valueArray = SqlWireUtil.toGenericArray(VALUES[9]);

		final long nativeValueRef = BPValueHelper.toNativeValueRef(keyArray, EMPTY_ORDERS);
		strictTree.insert(new SingleTreeKey(valueArray), new IndexPointerRef(nativeValueRef));

		Assertions.assertThrows(NonUniqueValueException.class, () -> strictTree.insert(new SingleTreeKey(valueArray), new IndexPointerRef(nativeValueRef)));
	}
}
