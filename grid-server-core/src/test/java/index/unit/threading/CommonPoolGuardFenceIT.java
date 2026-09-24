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
package index.unit.threading;

import org.genfork.grid.common.GridProcessFence;
import org.genfork.grid.threading.CommonPoolGuard;
import org.genfork.grid.threading.CommonPoolGuardProbe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * CommonPoolGuard must trip {@link GridProcessFence} without killing the sampler daemon.
 *
 * @author: GenCloud
 * @date: 2025/02
 * @since: 1.0
 */
class CommonPoolGuardFenceIT {

	private static final long WAIT_MS = 8_000L;

	@BeforeEach
	void setUp() {
		GridProcessFence.resetForTests();
		CommonPoolGuard.install();
	}

	@AfterEach
	void tearDown() {
		GridProcessFence.resetForTests();
	}

	@Test
	void assertNotOnCommonPoolTripsFenceAndThrows() throws Exception {
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
		assertTrue(GridProcessFence.isTripped());
		assertTrue(samplerThreadAlive());
	}

	@Test
	void samplerHitTripsFenceWithoutKillingSampler() throws Exception {
		final CountDownLatch entered = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		ForkJoinPool.commonPool().execute(() ->
				CommonPoolGuardProbe.hold(entered, release, WAIT_MS));
		assertTrue(entered.await(WAIT_MS, TimeUnit.MILLISECONDS));
		final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MS);
		while (!GridProcessFence.isTripped() && System.nanoTime() < deadline) {
			Thread.sleep(200L);
		}
		release.countDown();
		assertTrue(GridProcessFence.isTripped(), "sampler should trip fence while grid frame is on commonPool");
		assertTrue(samplerThreadAlive(), "sampler daemon must survive hit");
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