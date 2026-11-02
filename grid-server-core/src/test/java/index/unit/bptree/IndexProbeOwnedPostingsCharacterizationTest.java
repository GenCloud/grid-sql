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

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.genfork.grid.mem.index.IndexPointerRef;
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

/**
 * Exact searchEq must return owned postings — iterating outside the probe domain while
 * the leaf mutates must not CME or lose the UNIQUE owner key.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
class IndexProbeOwnedPostingsCharacterizationTest {
	private static final byte[][] EMPTY_ORDERS = new byte[0][];
	private static final int MUTATE_ROUNDS = 2_000;
	private static final long AWAIT_SECONDS = 30L;

	@Test
	void exactSearchEqOwned_iterateWhileMutate_noCmeKeepsOwnerKey() throws Exception {
		final GridPointerBPTree tree = new GridPointerBPTree("owned-eq", false);
		final byte[] indexWire = SqlWireUtil.toGenericArray("Castle");
		final byte[] rowPk = SqlWireUtil.toGenericArray(1);
		final long nativeRef = BPValueHelper.toNativeValueRef(rowPk, EMPTY_ORDERS);
		final IndexPointerRef owner = new IndexPointerRef(nativeRef, rowPk);
		tree.insert(new SingleTreeKey(indexWire), owner);

		final IndexOperationResult probe = tree.searchEq(new SingleTreeKey(indexWire));
		assertNotNull(probe);
		final Set<IndexPointerRef> postings = probe.getPointers();
		assertNotNull(postings);
		assertEquals(1, postings.size());

		final ExecutorService pool = Executors.newSingleThreadExecutor();
		final CountDownLatch go = new CountDownLatch(1);
		final AtomicReference<Throwable> mutateError = new AtomicReference<>();
		try {
			final Future<?> mutate = pool.submit(() -> {
				try {
					go.await();
					for (int i = 0; i < MUTATE_ROUNDS; i++) {
						final byte[] extraPk = SqlWireUtil.toGenericArray(100_000 + i);
						final long extraRef = BPValueHelper.toNativeValueRef(extraPk, EMPTY_ORDERS);
						tree.insert(new SingleTreeKey(indexWire), new IndexPointerRef(extraRef, extraPk));
					}
				} catch (Throwable t) {
					mutateError.compareAndSet(null, t);
				}
			});
			go.countDown();
			int resolved = 0;
			for (IndexPointerRef ptr : postings) {
				assertNotNull(ptr);
				final byte[] key = ptr.resolveKey();
				assertNotNull(key, "owned posting must survive concurrent leaf mutate");
				assertArrayEquals(rowPk, key);
				resolved++;
			}
			assertEquals(1, resolved);
			mutate.get(AWAIT_SECONDS, TimeUnit.SECONDS);
			assertNull(mutateError.get(), "mutate failed: " + mutateError.get());
			assertFalse(postings.isEmpty());
		} finally {
			pool.shutdownNow();
			assertEquals(true, pool.awaitTermination(AWAIT_SECONDS, TimeUnit.SECONDS));
			tree.clear();
		}
	}
}
