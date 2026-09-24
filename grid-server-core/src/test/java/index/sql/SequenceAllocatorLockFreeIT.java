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
package index.sql;

import org.genfork.grid.catalog.SequenceAllocator;
import org.genfork.grid.catalog.SequenceDef;
import org.genfork.grid.fs.GridFs;
import org.genfork.grid.utils.AtomicBitSet;
import org.genfork.grid.utils.HexBytes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lock-free SequenceAllocator: batch persist + RECLAIM CAS.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
@DisplayName("SequenceAllocatorLockFreeIT")
class SequenceAllocatorLockFreeIT {
	private static final int THREADS = 8;
	private static final int PER_THREAD = 40;

	@TempDir
	Path tmp;

	@Test
	void monotonic_batchesPersist() {
		final SequenceDef def = SequenceDef.of("batch_seq", 1L, 1L, false);
		final SequenceAllocator alloc = new SequenceAllocator(def, tmp);
		final int batch = alloc.persistBatchAllocs();
		for (int i = 0; i < batch + 3; i++) {
			alloc.nextVal();
		}
		assertTrue(alloc.durableLimitForTest() >= (long) batch);
		assertTrue(GridFs.isRegularFile(tmp.resolve("batch_seq.seq")));
	}

	@Test
	void reclaim_concurrentReleaseAndNext() throws Exception {
		final SequenceDef def = SequenceDef.of("reclaim_seq", 1L, 1L, true);
		final SequenceAllocator alloc = new SequenceAllocator(def, tmp);
		final Set<Long> first = new HashSet<>();
		for (int i = 0; i < 16; i++) {
			first.add(alloc.nextVal());
		}
		for (Long v : first) {
			alloc.release(v);
		}
		alloc.flush();
		final ExecutorService pool = Executors.newFixedThreadPool(THREADS);
		final CountDownLatch start = new CountDownLatch(1);
		final Set<Long> values = java.util.Collections.synchronizedSet(new HashSet<>());
		final AtomicInteger errors = new AtomicInteger();
		for (int t = 0; t < THREADS; t++) {
			pool.submit(() -> {
				try {
					start.await();
					for (int i = 0; i < PER_THREAD; i++) {
						values.add(alloc.nextVal());
					}
				} catch (Exception e) {
					errors.incrementAndGet();
				}
			});
		}
		start.countDown();
		pool.shutdown();
		assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
		assertEquals(0, errors.get());
		assertEquals(THREADS * PER_THREAD, values.size());
	}

	@Test
	void hexBytes_roundTrip() {
		final byte[] raw = new byte[] {0x0a, (byte) 0xff, 0x00, 0x1b};
		assertEquals("0aff001b", HexBytes.toHex(raw));
		final byte[] back = HexBytes.fromHex("0aFF001b");
		assertEquals(raw.length, back.length);
		for (int i = 0; i < raw.length; i++) {
			assertEquals(raw[i], back[i]);
		}
	}

	@Test
	void atomicBitSet_claimNext() {
		final AtomicBitSet bits = new AtomicBitSet();
		bits.set(3);
		bits.set(7);
		assertEquals(3, bits.claimNextSetBit());
		assertEquals(7, bits.claimNextSetBit());
		assertEquals(-1, bits.claimNextSetBit());
		assertEquals(-1, bits.nextSetBit(0));
	}
}