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
package org.genfork.grid.sql.tx;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import com.google.common.annotations.VisibleForTesting;

import org.genfork.grid.metrics.SqlLockMetrics;

/**
 * Fair per-(table, key) locks for SQL TX / DML. Blocking acquire parks on the calling
 * logic VT only — never hold a monitor across park; never park on Netty EL.
 * <p>
 * Ownership is <em>not</em> thread-scoped: {@link org.genfork.grid.threading.SerialTaskQueue}
 * may run EXEC and COMMIT on different logic VTs, so {@link #unlock} must release regardless
 * of caller thread. Same-TX re-acquire is handled by {@link SqlTxBuffer#holdsLock} before
 * calling {@link #lock}.
 * <p>
 * Wait is bounded by {@link #DEFAULT_LOCK_WAIT_MS} (or configured nanos). CANCEL / interrupt
 * unparks waiters via {@link Thread#interrupt()}.
 *
 * @author: GenCloud
 * @date: 2026/01
 * @since: 1.0
 */
public final class SqlRecordLockManager {
	/** Default max lock wait — must stay ≤ typical client OP_TIMEOUT (10s). */
	public static final long DEFAULT_LOCK_WAIT_MS = 8_000L;
	private static final long PARK_NS = TimeUnit.MILLISECONDS.toNanos(1L);

	private final ConcurrentHashMap<LockKey, Entry> locks = new ConcurrentHashMap<>();
	private volatile long defaultWaitNanos = TimeUnit.MILLISECONDS.toNanos(DEFAULT_LOCK_WAIT_MS);

	public SqlRecordLockManager() {
	}

	public SqlRecordLockManager(long lockWaitTimeoutMs) {
		setLockWaitTimeoutMs(lockWaitTimeoutMs);
	}

	/**
	 * Configure max wait for {@link #lock(String, byte[])}. {@code <= 0} keeps default.
	 */
	public void setLockWaitTimeoutMs(long lockWaitTimeoutMs) {
		if (lockWaitTimeoutMs > 0L) {
			this.defaultWaitNanos = TimeUnit.MILLISECONDS.toNanos(lockWaitTimeoutMs);
		}
	}

	public long lockWaitTimeoutMs() {
		return TimeUnit.NANOSECONDS.toMillis(defaultWaitNanos);
	}

	/**
	 * Blocking acquire with configured wait budget.
	 *
	 * @throws LockWaitTimeoutException if deadline elapses
	 * @throws LockWaitCancelledException if interrupted / CANCEL'd while waiting
	 */
	public void lock(String table, byte[] key) {
		lock(table, key, defaultWaitNanos);
	}

	/**
	 * Blocking acquire with explicit wait budget.
	 *
	 * @param waitNanos max park budget; {@code <= 0} means fail immediately if not free
	 */
	public void lock(String table, byte[] key, long waitNanos) {
		Objects.requireNonNull(table, "table");
		Objects.requireNonNull(key, "key");
		final LockKey lk = new LockKey(table, key);
		final Thread self = Thread.currentThread();
		final long startNs = System.nanoTime();
		final long budget = Math.max(0L, waitNanos);
		final long deadlineNs = startNs + budget;

		for (;;) {
			if (Thread.interrupted()) {
				SqlLockMetrics.recordCancel();
				throw new LockWaitCancelledException();
			}
			final Entry created = new Entry();
			final Entry existing = locks.putIfAbsent(lk, created);
			if (existing == null) {
				SqlLockMetrics.recordAcquire(System.nanoTime() - startNs);
				return;
			}
			final long now = System.nanoTime();
			if (now >= deadlineNs) {
				SqlLockMetrics.recordTimeout();
				throw new LockWaitTimeoutException();
			}
			existing.waiter.set(self);
			final long remaining = deadlineNs - now;
			LockSupport.parkNanos(existing, Math.min(PARK_NS, remaining));
			existing.waiter.compareAndSet(self, null);
			if (Thread.interrupted()) {
				SqlLockMetrics.recordCancel();
				throw new LockWaitCancelledException();
			}
		}
	}

	/**
	 * Non-blocking try. Returns {@code false} if the key is already locked.
	 * Same-TX re-acquire is handled by {@link SqlTxBuffer#holdsLock} before this call
	 * (do not treat thread identity as ownership — logic VT may change per EXEC).
	 */
	public boolean tryLock(String table, byte[] key) {
		Objects.requireNonNull(table, "table");
		Objects.requireNonNull(key, "key");
		final LockKey lk = new LockKey(table, key);
		final Entry created = new Entry();
		final Entry existing = locks.putIfAbsent(lk, created);
		return existing == null;
	}

	/**
	 * Release a held key. Safe from any thread (COMMIT mailbox VT may differ from acquire VT).
	 */
	public void unlock(String table, byte[] key) {
		Objects.requireNonNull(table, "table");
		Objects.requireNonNull(key, "key");
		final LockKey lk = new LockKey(table, key);
		final Entry e = locks.get(lk);
		if (e == null) {
			return;
		}
		if (locks.remove(lk, e)) {
			final Thread w = e.waiter.getAndSet(null);
			if (w != null) {
				LockSupport.unpark(w);
			}
		}
	}

	@VisibleForTesting
	public void setDefaultWaitNanosForTest(long nanos) {
		this.defaultWaitNanos = Math.max(0L, nanos);
	}

	@VisibleForTesting
	public int size() {
		return locks.size();
	}

	private static final class Entry {
		private final AtomicReference<Thread> waiter = new AtomicReference<>();
	}

	private static final class LockKey {
		private final String table;
		private final KeyWrapper key;

		private LockKey(String table, byte[] key) {
			this.table = table;
			this.key = new KeyWrapper(key);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof LockKey other)) {
				return false;
			}
			return table.equals(other.table) && key.equals(other.key);
		}

		@Override
		public int hashCode() {
			return 31 * table.hashCode() + key.hashCode();
		}
	}
}
