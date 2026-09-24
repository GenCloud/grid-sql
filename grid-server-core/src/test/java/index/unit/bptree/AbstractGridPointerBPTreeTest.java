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

import org.genfork.grid.mem.index.IndexPointerRef;
import org.genfork.grid.mem.index.btree.BPValueHelper;
import org.genfork.grid.mem.index.btree.GridPointerBPTree;
import org.genfork.grid.mem.index.btree.SingleTreeKey;
import org.genfork.grid.serial.SqlWireUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * @author: GenCloud
 * @date: 2026/12
 * @since: 1.0
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName.class)
public abstract class AbstractGridPointerBPTreeTest {
	protected static final byte[][] EMPTY_ORDERS = new byte[0][];

	protected GridPointerBPTree laxTree;
	protected GridPointerBPTree strictTree;

	protected final Map<Long, byte[]> pointerToValueMap = new LinkedHashMap<>();

	@BeforeAll
	public void setup() {
		laxTree = new GridPointerBPTree("", false);
		strictTree = new GridPointerBPTree("", true);

		for (int key = 0; key < fillCount(); key++) {
			final Object value = computeValue();
			final byte[] keyArray = SqlWireUtil.toGenericArray(key);
			final byte[] valueArray = SqlWireUtil.toGenericArray(value);

			final long nativeValueRef = BPValueHelper.toNativeValueRef(keyArray, EMPTY_ORDERS);
			laxTree.insert(new SingleTreeKey(valueArray), new IndexPointerRef(nativeValueRef));

			pointerToValueMap.put(nativeValueRef, valueArray);
		}
	}

	@AfterAll
	public void destroy() {
		laxTree.clear();
		strictTree.clear();
		pointerToValueMap.clear();
	}

	protected abstract int fillCount();

	protected abstract Object computeValue();

	protected byte[] getRndStoredValue(int skip) {
		return pointerToValueMap.values()
				.stream()
				.skip(skip)
				.findFirst()
				.orElse(null);
	}

	protected byte[] getFirstPointerValue(Set<IndexPointerRef> pointers) {
		final long pointer = pointers.stream().findFirst().map(IndexPointerRef::getPointer).orElse(-1L);
		if (pointer == -1) {
			return null;
		}

		return pointerToValueMap.get(pointer);
	}

	protected byte[] getLastPointerValue(Set<IndexPointerRef> pointers) {
		final long pointer = pointers.stream().skip(pointers.size() - 1).findFirst().map(IndexPointerRef::getPointer).orElse(-1L);
		if (pointer == -1) {
			return null;
		}

		return pointerToValueMap.get(pointer);
	}

	protected boolean matchAnyPointers(Set<IndexPointerRef> pointers, byte[] expectedValue) {
		for (IndexPointerRef pointer : pointers) {
			if (Arrays.equals(pointerToValueMap.get(pointer.getPointer()), expectedValue)) {
				return true;
			}
		}

		return false;
	}
}
