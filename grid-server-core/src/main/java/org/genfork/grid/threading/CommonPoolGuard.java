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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Fail-fast detector: org.genfork.grid.* frames on ForkJoinPool.commonPool().
 * <p>
 * Hits trip {@link GridProcessFence} and log; the sampler daemon must keep running.
 * {@link #assertNotOnCommonPool} also throws so the offending call fails explicitly.
 * Complements {@code NoCompletableFutureCommonPoolTest}.
 *
 * @author: GenCloud
 * @date: 2025/02
 * @since: 1.0
 */
public final class CommonPoolGuard {
	private static final Logger log = LoggerFactory.getLogger(CommonPoolGuard.class);

	private static final String GRID_PACKAGE_PREFIX = "org.genfork.grid.";
	private static final String GUARD_CLASS = CommonPoolGuard.class.getName();
	private static final String COMMON_POOL_THREAD_PREFIX = "ForkJoinPool.commonPool-worker-";
	private static final long SAMPLE_INTERVAL_NS = TimeUnit.SECONDS.toNanos(2L);
	private static final int STACK_DEPTH = 64;
	private static final int SUMMARY_MAX_FRAMES = 6;
	private static final String HIT_MSG_PREFIX = "org.genfork.grid work on ForkJoinPool.commonPool() at ";

	private static final AtomicBoolean INSTALLED = new AtomicBoolean();
	private static final AtomicLong HITS = new AtomicLong();
	private static final AtomicBoolean LOGGED_FIRST = new AtomicBoolean();

	private CommonPoolGuard() {
	}

	/** Idempotent; starts the daemon sampler. */
	public static void install() {
		if (!INSTALLED.compareAndSet(false, true)) {
			return;
		}
		final Thread sampler = Thread.ofPlatform()
				.name("grid-common-pool-guard")
				.daemon(true)
				.unstarted(CommonPoolGuard::sampleLoop);
		sampler.start();
		log.info("CommonPoolGuard fail-fast sampler started");
	}

	public static boolean isCurrentThreadCommonPoolWorker() {
		final Thread t = Thread.currentThread();
		if (t instanceof ForkJoinWorkerThread worker) {
			return worker.getPool() == ForkJoinPool.commonPool();
		}
		final String name = t.getName();
		return name != null && name.startsWith(COMMON_POOL_THREAD_PREFIX);
	}

	public static void assertNotOnCommonPool(String where) {
		if (isCurrentThreadCommonPoolWorker()) {
			onHit(where, Thread.currentThread().getStackTrace(), true);
		}
	}


	private static void sampleLoop() {
		final ThreadMXBean bean = ManagementFactory.getThreadMXBean();
		while (!Thread.currentThread().isInterrupted()) {
			LockSupport.parkNanos(SAMPLE_INTERVAL_NS);
			if (Thread.interrupted()) {
				return;
			}
			try {
				sampleOnce(bean);
			} catch (Throwable t) {
				log.warn("CommonPoolGuard sample failed: {}", t.toString());
			}
		}
	}

	private static void sampleOnce(ThreadMXBean bean) {
		final long[] ids = bean.getAllThreadIds();
		for (long id : ids) {
			// Name-only probe first — full stack dump for every JVM thread was hot under WRITE_ONLY.
			final ThreadInfo nameOnly = bean.getThreadInfo(id, 0);
			if (nameOnly == null) {
				continue;
			}
			final String name = nameOnly.getThreadName();
			if (name == null || !name.startsWith(COMMON_POOL_THREAD_PREFIX)) {
				continue;
			}
			final ThreadInfo info = bean.getThreadInfo(id, STACK_DEPTH);
			if (info == null) {
				continue;
			}
			final StackTraceElement[] stack = info.getStackTrace();
			if (stack == null || stack.length == 0) {
				continue;
			}
			for (StackTraceElement frame : stack) {
				final String cn = frame.getClassName();
				if (cn.startsWith(GRID_PACKAGE_PREFIX)
						&& !cn.equals(GUARD_CLASS)
						&& !cn.startsWith(GUARD_CLASS + "$")) {
					onHit("sampler:" + name, stack, false);
					break;
				}
			}
		}
	}

	/**
	 * @param throwToCaller when true (assert path), throw after tripping fence
	 */
	private static void onHit(String where, StackTraceElement[] stack, boolean throwToCaller) {
		final long n = HITS.incrementAndGet();
		final String msg = HIT_MSG_PREFIX + where + " hit#" + n + " stack=" + summarize(stack);
		GridProcessFence.trip(msg);
		if (LOGGED_FIRST.compareAndSet(false, true)) {
			log.error(msg);
		} else {
			log.error("CommonPoolGuard repeat hit#{} at {}", n, where);
		}
		if (throwToCaller) {
			throw new IllegalStateException(msg);
		}
	}

	private static String summarize(StackTraceElement[] stack) {
		final StringBuilder sb = new StringBuilder(256);
		int shown = 0;
		for (StackTraceElement frame : stack) {
			if (!frame.getClassName().startsWith(GRID_PACKAGE_PREFIX)) {
				continue;
			}
			if (shown > 0) {
				sb.append(" <- ");
			}
			sb.append(frame.getClassName()).append('#').append(frame.getMethodName())
					.append(':').append(frame.getLineNumber());
			shown++;
			if (shown >= SUMMARY_MAX_FRAMES) {
				break;
			}
		}
		return shown == 0 ? "(no grid frames)" : sb.toString();
	}
}
