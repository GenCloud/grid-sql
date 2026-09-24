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

import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.lang.Thread.UncaughtExceptionHandler;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Process-wide executors: bounded I/O, CPU (~cores), logic VT, daemon scheduler.
 * <p>
 * <b>I/O</b> ({@link #getNetworkExecutor()}): platform threads, fixed size, bounded queue,
 * named {@code grid-io-*}, {@link java.util.concurrent.ThreadPoolExecutor.AbortPolicy}.
 * Blocking network / disk off Netty EL.
 * <p>
 * <b>CPU</b> ({@link #getCpuExecutor()}): platform threads, size ~= available processors,
 * bounded queue, named {@code grid-cpu-*}, AbortPolicy. Parallel scan / digest-heavy map.
 * Never {@link java.util.concurrent.ForkJoinPool#commonPool()}.
 * <p>
 * <b>Logic VT</b> ({@link #getLogicExecutor()}): virtual thread-per-task. Valid for many
 * concurrent <em>blocking waits</em> that must not pin a carrier for CPU work:
 * SQL session mailbox, record-lock park (JDK 24+), ORCHID wait off Netty EL.
 * <b>Not</b> for CPU-bound fan-out (use {@link #getCpuExecutor()}) and not a substitute
 * for bounded I/O admission.
 * <p>
 * Scheduler threads are daemon so JMH/surefire forks can exit.
 *
 * @author: GenCloud
 * @date: 2025/02
 * @since: 1.0
 */
public class ThreadService {
	private static final AtomicInteger SCHED_SEQ = new AtomicInteger();
	private static final AtomicInteger IO_SEQ = new AtomicInteger();
	private static final AtomicInteger CPU_SEQ = new AtomicInteger();

	private static final int CPU_THREADS = Math.max(1, Runtime.getRuntime().availableProcessors());
	private static final int CPU_QUEUE_CAPACITY = CPU_THREADS * 64;
	private static final int IO_THREADS = Math.max(4, CPU_THREADS * 2);
	private static final int IO_QUEUE_CAPACITY = IO_THREADS * 256;
	private static final long KEEP_ALIVE_SECONDS = 60L;

	private static volatile ScheduledThreadPoolExecutor scheduledExecutor;
	private static volatile ExecutorService networkExecutor;
	private static volatile ExecutorService cpuExecutor;
	private static volatile ExecutorService logicExecutor;
	private static volatile Scheduler scheduler;

	static {
		CommonPoolGuard.install();
		initExecutors();
	}

	private ThreadService() {
	}

	private static void initExecutors() {
		final ThreadFactory schedFactory = runnable -> {
			final Thread t = new Thread(runnable, "grid-sched-" + SCHED_SEQ.incrementAndGet());
			t.setDaemon(true);
			return t;
		};
		final ScheduledThreadPoolExecutor sched = new ScheduledThreadPoolExecutor(2, schedFactory);
		sched.setRemoveOnCancelPolicy(true);
		sched.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
		sched.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
		scheduledExecutor = sched;

		networkExecutor = newBoundedPool(
				IO_THREADS,
				IO_QUEUE_CAPACITY,
				"grid-io-",
				IO_SEQ,
				new ThreadPoolExecutor.AbortPolicy());

		cpuExecutor = newBoundedPool(
				CPU_THREADS,
				CPU_QUEUE_CAPACITY,
				"grid-cpu-",
				CPU_SEQ,
				new ThreadPoolExecutor.AbortPolicy());

		// VT: high-concurrency blocking wait (SQL mailbox / lock park), not CPU fan-out.
		logicExecutor = Executors.newThreadPerTaskExecutor(
				Thread.ofVirtual().name("LOGIC-", 0).factory()
		);

		scheduler = Schedulers.fromExecutor(logicExecutor);
	}

	private static ThreadPoolExecutor newBoundedPool(
			int threads,
			int queueCapacity,
			String namePrefix,
			AtomicInteger seq,
			RejectedExecutionHandler rejection
	) {
		final ThreadFactory factory = runnable -> {
			final Thread t = new Thread(runnable, namePrefix + seq.incrementAndGet());
			t.setDaemon(true);
			return t;
		};
		final ThreadPoolExecutor pool = new ThreadPoolExecutor(
				threads,
				threads,
				KEEP_ALIVE_SECONDS,
				TimeUnit.SECONDS,
				new ArrayBlockingQueue<>(queueCapacity),
				factory,
				rejection);
		pool.allowCoreThreadTimeOut(false);
		return pool;
	}

	/**
	 * Recreate executors after {@link #shutdownNow()} (same JVM multi-trial / unit tests).
	 */
	public static synchronized void ensureRunning() {
		final ScheduledThreadPoolExecutor sched = scheduledExecutor;
		if (sched == null || sched.isShutdown()) {
			initExecutors();
		}
	}

	/**
	 * Cancel pending schedules and shut down pools. Used by JMH {@code @TearDown} / harness cleanup.
	 * Call {@link #ensureRunning()} before further work in the same JVM.
	 */
	public static synchronized void shutdownNow() {
		final ScheduledThreadPoolExecutor sched = scheduledExecutor;
		if (sched != null) {
			sched.shutdownNow();
			scheduledExecutor = null;
		}
		final Scheduler s = scheduler;
		if (s != null) {
			s.dispose();
			scheduler = null;
		}
		shutdownPool(networkExecutor);
		networkExecutor = null;
		shutdownPool(cpuExecutor);
		cpuExecutor = null;
		shutdownPool(logicExecutor);
		logicExecutor = null;
	}

	private static void shutdownPool(ExecutorService pool) {
		if (pool != null) {
			pool.shutdownNow();
		}
	}

	public static ScheduledThreadPoolExecutor getScheduledExecutor() {
		ensureRunning();
		return scheduledExecutor;
	}

	/** Bounded I/O platform pool ({@code grid-io-*}, AbortPolicy). */
	public static ExecutorService getNetworkExecutor() {
		ensureRunning();
		return networkExecutor;
	}

	/** Alias for {@link #getNetworkExecutor()}. */
	public static ExecutorService getIoExecutor() {
		return getNetworkExecutor();
	}

	/**
	 * CPU platform pool (~{@code availableProcessors}, {@code grid-cpu-*}, AbortPolicy).
	 * Parallel scan / CPU fan-out — not commonPool.
	 */
	public static ExecutorService getCpuExecutor() {
		ensureRunning();
		return cpuExecutor;
	}

	/**
	 * Logic virtual-thread executor (SQL mailbox, lock wait off EL).
	 * Not for CPU-bound parallel map — use {@link #getCpuExecutor()}.
	 */
	public static ExecutorService getLogicExecutor() {
		ensureRunning();
		return logicExecutor;
	}

	public static Scheduler getScheduler() {
		ensureRunning();
		return scheduler;
	}

	public static int cpuPoolSize() {
		return CPU_THREADS;
	}

	public static int ioPoolSize() {
		return IO_THREADS;
	}

	public static void executeNetwork(Runnable task) {
		final ExecutorService service = getNetworkExecutor();
		if (service.isShutdown() || service.isTerminated()) {
			return;
		}
		final RunnableWrapper wrapper = new RunnableWrapper(task);
		try {
			service.execute(wrapper);
		} catch (RejectedExecutionException e) {
			// AbortPolicy: do not run on Netty EL / caller; surface backpressure.
			throw e;
		}
	}

	static class RunnableWrapper implements Runnable {
		private final Runnable runnable;

		public RunnableWrapper(Runnable runnable) {
			this.runnable = runnable;
		}

		@Override
		public void run() {
			try {
				runnable.run();
			} catch (final Throwable e) {
				final Thread t = Thread.currentThread();
				final UncaughtExceptionHandler h = t.getUncaughtExceptionHandler();
				if (h != null) {
					h.uncaughtException(t, e);
				}
			}
		}

		@Override
		public boolean equals(Object obj) {
			if (obj == this) {
				return true;
			}
			if (obj == null || obj.getClass() != this.getClass()) {
				return false;
			}
			final RunnableWrapper that = (RunnableWrapper) obj;
			return Objects.equals(this.runnable, that.runnable);
		}

		@Override
		public int hashCode() {
			return Objects.hash(runnable);
		}
	}
}