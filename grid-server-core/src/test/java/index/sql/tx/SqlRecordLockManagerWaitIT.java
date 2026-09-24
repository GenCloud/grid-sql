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
package index.sql.tx;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.genfork.grid.metrics.SqlLockMetrics;
import org.genfork.grid.sql.tx.LockWaitCancelledException;
import org.genfork.grid.sql.tx.LockWaitTimeoutException;
import org.genfork.grid.sql.tx.SqlRecordLockManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bound lock wait + interrupt cancel for {@link SqlRecordLockManager}.
 *
 * @author: GenCloud
 * @date: 2026/01
 * @since: 1.0
 */
public class SqlRecordLockManagerWaitIT {
	private static final String TABLE = "t";
	private static final byte[] KEY = new byte[]{1, 2, 3};
	private static final long SHORT_WAIT_MS = 200L;
	private static final long HOLDER_HOLD_MS = 2_000L;
	private static final long JOIN_TIMEOUT_MS = 5_000L;

	private SqlRecordLockManager locks;

	@BeforeEach
	void setUp() {
		locks = new SqlRecordLockManager(SHORT_WAIT_MS);
	}

	@Test
	void waitTimeoutWhenHolderDoesNotRelease() throws Exception {
		final CountDownLatch held = new CountDownLatch(1);
		final Thread holder = Thread.ofVirtual().start(() -> {
			locks.lock(TABLE, KEY);
			held.countDown();
			try {
				Thread.sleep(HOLDER_HOLD_MS);
			} catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			} finally {
				locks.unlock(TABLE, KEY);
			}
		});
		assertTrue(held.await(JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS));
		final long before = SqlLockMetrics.waitTimeouts();
		assertThrows(LockWaitTimeoutException.class, () -> locks.lock(TABLE, KEY));
		assertTrue(SqlLockMetrics.waitTimeouts() > before);
		holder.interrupt();
		holder.join(JOIN_TIMEOUT_MS);
	}

	@Test
	void unlockFromOtherThreadReleasesWaiters() throws Exception {
		final CountDownLatch held = new CountDownLatch(1);
		final CountDownLatch unlocked = new CountDownLatch(1);
		final Thread acquirer = Thread.ofVirtual().start(() -> {
			locks.lock(TABLE, KEY);
			held.countDown();
			try {
				assertTrue(unlocked.await(JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		assertTrue(held.await(JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS));
		final Thread releaser = Thread.ofVirtual().start(() -> locks.unlock(TABLE, KEY));
		releaser.join(JOIN_TIMEOUT_MS);
		unlocked.countDown();
		locks.lock(TABLE, KEY);
		locks.unlock(TABLE, KEY);
		acquirer.join(JOIN_TIMEOUT_MS);
	}

	@Test
	void interruptUnblocksWaiter() throws Exception {
		final CountDownLatch held = new CountDownLatch(1);
		final CountDownLatch waiterStarted = new CountDownLatch(1);
		final AtomicReference<Throwable> waiterError = new AtomicReference<>();
		final Thread holder = Thread.ofVirtual().start(() -> {
			locks.lock(TABLE, KEY);
			held.countDown();
			try {
				Thread.sleep(HOLDER_HOLD_MS);
			} catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			} finally {
				locks.unlock(TABLE, KEY);
			}
		});
		assertTrue(held.await(JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS));
		locks.setDefaultWaitNanosForTest(TimeUnit.SECONDS.toNanos(30L));
		final Thread waiter = Thread.ofVirtual().start(() -> {
			waiterStarted.countDown();
			try {
				locks.lock(TABLE, KEY);
			} catch (Throwable ex) {
				waiterError.set(ex);
			}
		});
		assertTrue(waiterStarted.await(JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS));
		Thread.sleep(50L);
		waiter.interrupt();
		waiter.join(JOIN_TIMEOUT_MS);
		assertInstanceOf(LockWaitCancelledException.class, waiterError.get());
		holder.interrupt();
		holder.join(JOIN_TIMEOUT_MS);
	}
}
