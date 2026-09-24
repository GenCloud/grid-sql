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
 * Lock-free {@link SequenceAllocator} concurrent allocate / reclaim / durable reload.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class SequenceAllocatorConcurrencyIT {
	private static final int THREADS = 8;
	private static final int PER_THREAD = 200;

	@TempDir
	Path catalogDir;

	@Test
	void concurrentMonotonicUniqueAndReload() throws Exception {
		final SequenceAllocator alloc = new SequenceAllocator(
				SequenceDef.of("conc", 1L, 1L, false), catalogDir);
		final Set<Long> values = java.util.Collections.synchronizedSet(new HashSet<>());
		runConcurrent(() -> values.add(Long.valueOf(alloc.nextVal())));
		assertEquals(THREADS * PER_THREAD, values.size());
		alloc.flush();

		final SequenceAllocator reloaded = new SequenceAllocator(
				SequenceDef.of("conc", 1L, 1L, false), catalogDir);
		final long next = reloaded.nextVal();
		assertTrue(next > THREADS * PER_THREAD,
				"reloaded next must be past reserved durable window, got " + next);
	}

	@Test
	void concurrentReclaimNoDuplicates() throws Exception {
		final SequenceAllocator alloc = new SequenceAllocator(
				SequenceDef.of("rec", 1L, 1L, true), null);
		for (long v = 1L; v <= 100L; v++) {
			alloc.release(v);
		}
		final Set<Long> values = java.util.Collections.synchronizedSet(new HashSet<>());
		final AtomicInteger errors = new AtomicInteger();
		final CountDownLatch start = new CountDownLatch(1);
		final ExecutorService pool = Executors.newFixedThreadPool(THREADS);
		for (int t = 0; t < THREADS; t++) {
			pool.submit(() -> {
				try {
					start.await();
					for (int i = 0; i < 12; i++) {
						values.add(Long.valueOf(alloc.nextVal()));
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
		assertEquals(THREADS * 12, values.size());
	}

	private static void runConcurrent(Runnable body) throws Exception {
		final CountDownLatch start = new CountDownLatch(1);
		final AtomicInteger errors = new AtomicInteger();
		final ExecutorService pool = Executors.newFixedThreadPool(THREADS);
		for (int t = 0; t < THREADS; t++) {
			pool.submit(() -> {
				try {
					start.await();
					for (int i = 0; i < PER_THREAD; i++) {
						body.run();
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
	}
}