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
package index.unit.store;

import java.util.Set;

import org.genfork.grid.mem.index.IndexPointerRef;
import org.genfork.grid.mem.index.StrictUniqueUpsertUtil;
import org.genfork.grid.mem.index.btree.BPValueHelper;
import org.genfork.grid.mem.index.btree.GridPointerBPTree;
import org.genfork.grid.mem.index.btree.IndexOperationResult;
import org.genfork.grid.mem.index.btree.SingleTreeKey;
import org.genfork.grid.serial.SqlWireUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Same-PK STRICT upsert must retarget in place — no free→reinsert of the live UNIQUE owner.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
class StrictUniqueSamePkRetargetCharacterizationTest {
	private static final byte[][] EMPTY_ORDERS = new byte[0][];

	@Test
	void samePkUpsert_keepsLiveOwnerWithoutFree() {
		final GridPointerBPTree tree = new GridPointerBPTree("same-pk", true);
		final byte[] indexWire = SqlWireUtil.toGenericArray("k");
		final byte[] rowPk = SqlWireUtil.toGenericArray(7);
		final long nativeRef = BPValueHelper.toNativeValueRef(rowPk, EMPTY_ORDERS);
		final IndexPointerRef owner = new IndexPointerRef(nativeRef, rowPk);
		tree.insert(new SingleTreeKey(indexWire), owner);

		final IndexOperationResult before = tree.searchEq(new SingleTreeKey(indexWire));
		final Set<IndexPointerRef> beforePtrs = before.getPointers();
		assertNotNull(beforePtrs);
		assertEquals(1, beforePtrs.size());
		final IndexPointerRef liveBefore = beforePtrs.iterator().next();

		final long againRef = BPValueHelper.toNativeValueRef(rowPk, EMPTY_ORDERS);
		final IndexPointerRef again = new IndexPointerRef(againRef, rowPk);
		final byte[] displaced = StrictUniqueUpsertUtil.insertOrReplaceOwner(
				tree, new SingleTreeKey(indexWire), again);
		assertNull(displaced);
		assertTrue(again.isFree(), "unused same-PK ref must be freed");
		assertFalse(liveBefore.isFree(), "live UNIQUE owner must not be freed on same-PK retarget");

		final IndexOperationResult after = tree.searchEq(new SingleTreeKey(indexWire));
		assertEquals(1, after.getPointers().size());
		assertArrayEquals(rowPk, after.getPointers().iterator().next().resolveKey());
		tree.clear();
	}
}
