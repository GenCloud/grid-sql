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
package index.unit.replication;

import org.genfork.grid.replication.durable.GroupForceGate;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrent group-force coalescing via {@link GroupForceGate}.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
class GroupForceGateTest {
	private static final int THREADS = 16;
	private static final long FORCE_SLEEP_MS = 5L;

	@Test
	void concurrentWaitersShareOneForce() throws Exception {
		final GroupForceGate gate = new GroupForceGate(0L);
		final AtomicInteger forceCalls = new AtomicInteger();
		final AtomicLong maxForcedTip = new AtomicLong();
		final ExecutorService pool = Executors.newFixedThreadPool(THREADS);
		final CountDownLatch start = new CountDownLatch(1);
		final CountDownLatch done = new CountDownLatch(THREADS);
		try {
			for (int i = 1; i <= THREADS; i++) {
				final long tip = i;
				pool.execute(() -> {
					try {
						start.await();
						gate.awaitCovered(tip, cover -> {
							forceCalls.incrementAndGet();
							try {
								Thread.sleep(FORCE_SLEEP_MS);
							} catch (InterruptedException e) {
								Thread.currentThread().interrupt();
								throw new java.io.IOException(e);
							}
							maxForcedTip.accumulateAndGet(cover, Math::max);
						});
					} catch (Exception e) {
						throw new RuntimeException(e);
					} finally {
						done.countDown();
					}
				});
			}
			start.countDown();
			assertTrue(done.await(10, TimeUnit.SECONDS));
			assertEquals(THREADS, gate.persistedTip());
			assertTrue(forceCalls.get() >= 1);
			assertTrue(forceCalls.get() < THREADS);
			assertEquals(THREADS, maxForcedTip.get());
		} finally {
			pool.shutdownNow();
		}
	}
}
