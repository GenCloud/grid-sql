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
import org.genfork.grid.mem.index.btree.IndexOperationResult;
import org.genfork.grid.mem.index.btree.SingleTreeKey;
import org.genfork.grid.serial.SqlWireUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Mass delete must prune empty leaves without breaking internal separators (searchAll).
 *
 * @author: GenCloud
 * @date: 2025/02
 * @since: 1.0
 */
class GridPointerBPTreePruneEmptyLeafTest {
	private static final byte[][] EMPTY_ORDERS = new byte[0][];
	private static final int INSERT_COUNT = 5_000;

	@Test
	void massDeleteThenSearchAllStaysConsistent() {
		final GridPointerBPTree tree = new GridPointerBPTree("prune-test", false);
		try {
			for (int i = 0; i < INSERT_COUNT; i++) {
				final byte[] keyArray = SqlWireUtil.toGenericArray(i);
				final byte[] valueArray = SqlWireUtil.toGenericArray(i);
				final long nativeRef = BPValueHelper.toNativeValueRef(keyArray, EMPTY_ORDERS);
				tree.insert(new SingleTreeKey(valueArray), new IndexPointerRef(nativeRef));
			}
			for (int i = 0; i < INSERT_COUNT; i++) {
				final byte[] valueArray = SqlWireUtil.toGenericArray(i);
				tree.delete(new SingleTreeKey(valueArray));
			}
			final IndexOperationResult all = assertDoesNotThrow(tree::searchAll);
			assertEquals(0, all.getPointers().size());
		} finally {
			tree.clear();
		}
	}
}