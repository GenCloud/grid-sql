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

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.genfork.grid.mem.index.IndexPointerRef;
import org.genfork.grid.mem.index.btree.AbstractBPTree;
import org.genfork.grid.mem.index.btree.BPValueHelper;
import org.genfork.grid.mem.index.btree.CompositeTreeKey;
import org.genfork.grid.mem.index.btree.GridPointerBPTree;
import org.genfork.grid.mem.index.btree.GridPointerCompositeBPTree;
import org.genfork.grid.mem.index.btree.IndexOperationResult;
import org.genfork.grid.mem.index.btree.SingleTreeKey;
import org.genfork.grid.serial.SqlWireUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Multi-leaf BPTree open-upper-bound / NE completeness (DEFAULT_ORDER=36).
 * <p>
 * Single-leaf unit suites hide InternalNode LT/LE/NE descending to the bound leaf.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
class MultiLeafOpenBoundScanTest {
	private static final byte[][] EMPTY_ORDERS = new byte[0][];
	/** Enough unique keys to force ≥3 leaves at DEFAULT_ORDER=36. */
	private static final int KEY_COUNT = 200;
	private static final int MID_EXCLUSIVE = 100;
	private static final int RANGE_LO = 40;
	private static final int RANGE_HI = 160;
	private static final int NE_DROP_KEY = 77;
	private static final int PAST_MAX_BOUND = KEY_COUNT + 50_000;

	private GridPointerBPTree tree;

	@BeforeEach
	void setUp() {
		tree = new GridPointerBPTree("multi-leaf-open-bound", false);
		for (int key = 0; key < KEY_COUNT; key++) {
			final byte[] keyBytes = SqlWireUtil.toGenericArray(key);
			final long nativeRef = BPValueHelper.toNativeValueRef(keyBytes, EMPTY_ORDERS);
			tree.insert(new SingleTreeKey(keyBytes), new IndexPointerRef(nativeRef));
		}
		assertTrue(countLeaves(tree) >= 3, "fixture must span multiple leaves");
	}

	@AfterEach
	void tearDown() {
		if (tree != null) {
			tree.clear();
			tree = null;
		}
	}

	@Test
	void lessThanOrEqualPastMaxReturnsAllKeys() {
		final IndexOperationResult result =
				tree.searchLessThanOrEqual(new SingleTreeKey(SqlWireUtil.toGenericArray(PAST_MAX_BOUND)));
		assertEquals(KEY_COUNT, result.getPointers().size());
	}

	@Test
	void lessThanMidExclusiveCount() {
		final IndexOperationResult result =
				tree.searchLessThan(new SingleTreeKey(SqlWireUtil.toGenericArray(MID_EXCLUSIVE)));
		assertEquals(MID_EXCLUSIVE, result.getPointers().size());
	}

	@Test
	void lessThanOrEqualMidInclusiveCount() {
		final IndexOperationResult result =
				tree.searchLessThanOrEqual(new SingleTreeKey(SqlWireUtil.toGenericArray(MID_EXCLUSIVE)));
		assertEquals(MID_EXCLUSIVE + 1, result.getPointers().size());
	}

	@Test
	void geAndLeIntersectMatchesSearchRange() {
		final SingleTreeKey lo = new SingleTreeKey(SqlWireUtil.toGenericArray(RANGE_LO));
		final SingleTreeKey hi = new SingleTreeKey(SqlWireUtil.toGenericArray(RANGE_HI));
		final IndexOperationResult ge = tree.searchGreaterThanOrEqual(lo);
		final IndexOperationResult le = tree.searchLessThanOrEqual(hi);
		final IndexOperationResult range = tree.searchRange(lo, hi);

		final Set<IndexPointerRef> intersection = new HashSet<>(ge.getPointers());
		intersection.retainAll(le.getPointers());

		final int expected = RANGE_HI - RANGE_LO + 1;
		assertEquals(expected, range.getPointers().size());
		assertEquals(expected, intersection.size());
		assertEquals(range.getPointers(), intersection);
	}

	@Test
	void notEqualDropsOneKey() {
		final IndexOperationResult result =
				tree.searchNotEq(new SingleTreeKey(SqlWireUtil.toGenericArray(NE_DROP_KEY)));
		assertEquals(KEY_COUNT - 1, result.getPointers().size());
	}

	@Test
	void compositeLessThanOrEqualPastMaxReturnsAllKeys() {
		final GridPointerCompositeBPTree composite =
				new GridPointerCompositeBPTree(List.of("a", "b"), "multi-leaf-comp", false);
		try {
			for (int key = 0; key < KEY_COUNT; key++) {
				final byte[][] parts = new byte[][]{
						SqlWireUtil.toGenericArray(key),
						SqlWireUtil.toGenericArray(0)
				};
				final byte[] rowKey = SqlWireUtil.toGenericArray(key);
				final long nativeRef = BPValueHelper.toNativeValueRef(rowKey, EMPTY_ORDERS);
				composite.insert(composite.createKey(parts), new IndexPointerRef(nativeRef));
			}
			assertTrue(countLeaves(composite) >= 3, "composite fixture must span multiple leaves");
			final IndexOperationResult result = composite.searchLessThanOrEqual(
					composite.createKey(new byte[][]{
							SqlWireUtil.toGenericArray(PAST_MAX_BOUND),
							SqlWireUtil.toGenericArray(0)
					}));
			assertEquals(KEY_COUNT, result.getPointers().size());
		} finally {
			composite.clear();
		}
	}

	private static int countLeaves(GridPointerBPTree bpTree) {
		int leaves = 0;
		AbstractBPTree.LeafNode<byte[], SingleTreeKey> leaf = bpTree.getFirstLeafNode();
		while (leaf != null) {
			leaves++;
			leaf = leaf.next;
		}
		return leaves;
	}

	private static int countLeaves(GridPointerCompositeBPTree bpTree) {
		int leaves = 0;
		AbstractBPTree.LeafNode<byte[][], CompositeTreeKey> leaf = bpTree.getFirstLeafNode();
		while (leaf != null) {
			leaves++;
			leaf = leaf.next;
		}
		return leaves;
	}
}
