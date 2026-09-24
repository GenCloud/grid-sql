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
package index.benchmarks;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import org.genfork.grid.sql.tx.LockWaitTimeoutException;
import org.genfork.grid.sql.tx.SqlRecordLockManager;
import org.genfork.grid.threading.ThreadService;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * JMH: record-lock acquire under contention, timeout fail path, cancel unblock.
 *
 * @author: GenCloud
 * @date: 2026/12
 * @since: 1.0
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(0)
@State(Scope.Benchmark)
public class SqlRecordLockWaitBenchmark extends AbstractLatencyBenchmark {
	private static final String TABLE = "jmh_lock";
	private static final byte[] KEY = new byte[]{9, 9, 9};
	private static final long LOCK_WAIT_MS = 50L;
	private static final long LONG_WAIT_NS = TimeUnit.SECONDS.toNanos(30L);
	private static final long PARK_SLICE_NS = TimeUnit.MILLISECONDS.toNanos(10L);
	private static final long SETUP_AWAIT_SEC = 5L;
	private static final long CANCEL_PARK_NS = TimeUnit.MILLISECONDS.toNanos(5L);

	private SqlRecordLockManager locks;
	private Thread holder;
	private final AtomicBoolean hold = new AtomicBoolean(true);

	@Setup(Level.Trial)
	public void setup() throws Exception {
		locks = new SqlRecordLockManager(LOCK_WAIT_MS);
		final CountDownLatch held = new CountDownLatch(1);
		holder = Thread.ofVirtual().start(() -> {
			locks.lock(TABLE, KEY, LONG_WAIT_NS);
			held.countDown();
			while (hold.get()) {
				LockSupport.parkNanos(PARK_SLICE_NS);
			}
			locks.unlock(TABLE, KEY);
		});
		if (!held.await(SETUP_AWAIT_SEC, TimeUnit.SECONDS)) {
			throw new IllegalStateException("holder did not acquire");
		}
	}

	@TearDown(Level.Trial)
	public void tearDown() throws Exception {
		hold.set(false);
		if (holder != null) {
			holder.interrupt();
			holder.join(TimeUnit.SECONDS.toMillis(2L));
		}
		ThreadService.shutdownNow();
	}

	@Benchmark
	@Threads(1)
	public void timeoutFailPath(Blackhole bh) {
		try {
			locks.lock(TABLE, KEY);
			bh.consume(false);
		} catch (LockWaitTimeoutException ex) {
			bh.consume(true);
		}
	}

	@Benchmark
	@Threads(1)
	public void cancelUnblocksWaiter(Blackhole bh) throws Exception {
		final CountDownLatch started = new CountDownLatch(1);
		final AtomicBoolean cancelled = new AtomicBoolean(false);
		final Thread waiter = Thread.ofVirtual().start(() -> {
			started.countDown();
			try {
				locks.lock(TABLE, KEY, LONG_WAIT_NS);
			} catch (Throwable ex) {
				cancelled.set(true);
			}
		});
		started.await(1L, TimeUnit.SECONDS);
		LockSupport.parkNanos(CANCEL_PARK_NS);
		waiter.interrupt();
		waiter.join(TimeUnit.SECONDS.toMillis(2L));
		bh.consume(cancelled.get());
	}
}
