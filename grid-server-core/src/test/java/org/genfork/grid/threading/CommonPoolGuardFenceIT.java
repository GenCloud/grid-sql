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
package org.genfork.grid.threading;

import org.genfork.grid.common.GridProcessFence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * CommonPoolGuard must trip {@link GridProcessFence} without killing the sampler daemon.
 * <p>
 * Single method — fence is process-global (no product reset). Surefire runs this class in a
 * dedicated JVM ({@code common-pool-fence-isolated}) so a tripped fence cannot poison
 * {@code SqlEngine} / store ITs that share the default fork.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
@ResourceLock(value = "grid-process-fence", mode = ResourceAccessMode.READ_WRITE)
class CommonPoolGuardFenceIT {

	private static final long WAIT_MS = 15_000L;

	@Test
	void samplerThenAssertTripFenceWithoutKillingSampler() throws Exception {
		assumeFalse(GridProcessFence.isTripped(), "fence already tripped in this JVM");
		ThreadService.ensureRunning();
		CommonPoolGuard.install();
		assertTrue(samplerThreadAlive(), "sampler daemon must be running before occupy");
		final CountDownLatch entered = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		ForkJoinPool.commonPool().execute(() -> {
			entered.countDown();
			parkOnCommonPoolWithGridFrameUntil(release);
		});
		assertTrue(entered.await(WAIT_MS, TimeUnit.MILLISECONDS));
		final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MS);
		while (!GridProcessFence.isTripped() && System.nanoTime() < deadline) {
			Thread.sleep(200L);
		}
		release.countDown();
		assertTrue(GridProcessFence.isTripped(), "sampler should trip fence while grid frame is on commonPool");
		assertTrue(samplerThreadAlive(), "sampler daemon must survive hit");

		final CountDownLatch done = new CountDownLatch(1);
		final AtomicReference<Throwable> err = new AtomicReference<>();
		ForkJoinPool.commonPool().execute(() -> {
			try {
				CommonPoolGuard.assertNotOnCommonPool("it-assert");
				fail("expected IllegalStateException");
			} catch (IllegalStateException expected) {
				err.set(expected);
			} catch (Throwable t) {
				err.set(t);
			} finally {
				done.countDown();
			}
		});
		assertTrue(done.await(WAIT_MS, TimeUnit.MILLISECONDS));
		assertNotNull(err.get());
		assertInstanceOf(IllegalStateException.class, err.get());
		assertTrue(samplerThreadAlive());
	}

	/**
	 * Test-local occupy loop in {@code org.genfork.grid.*} so the daemon sampler sees grid frames.
	 */
	private static void parkOnCommonPoolWithGridFrameUntil(CountDownLatch release) {
		try {
			while (release.getCount() > 0) {
				for (int burst = 0; burst < 256; burst++) {
					ThreadService.ensureRunning();
				}
				if (release.await(1L, TimeUnit.MILLISECONDS)) {
					return;
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static boolean samplerThreadAlive() {
		for (Thread t : Thread.getAllStackTraces().keySet()) {
			if ("grid-common-pool-guard".equals(t.getName()) && t.isAlive()) {
				return true;
			}
		}
		return false;
	}
}
