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
package org.genfork.grid.sql.client.sync;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Configurable executor for Sync* / JDBC blocking awaits.
 * <p>
 * Blocking {@link SyncAwait} work always runs on this executor (by construction),
 * never on the Netty SQL client event loop. If unset, a dedicated default pool is used.
 * Call {@link #setExecutor(Executor)} from app bootstrap to override; {@link #resetExecutor()}
 * restores the default (tests).
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public final class SyncExecutors {
	private static final String THREAD_NAME_PREFIX = "sql-client-sync-";
	private static final String ERR_NULL_EXECUTOR = "executor";
	private static final String ERR_NULL_ACTION = "action";
	private static final String ERR_INTERRUPTED = "sync executor call interrupted";

	private static final AtomicReference<Executor> OVERRIDE = new AtomicReference<>();
	private static final AtomicReference<ExecutorService> DEFAULT_POOL = new AtomicReference<>();
	private static final ThreadLocal<Boolean> ON_SYNC_WORKER = ThreadLocal.withInitial(() -> Boolean.FALSE);

	private SyncExecutors() {
	}

	/**
	 * Install a process-wide sync executor (tooling / JDBC / Sync* awaits).
	 */
	public static void setExecutor(Executor executor) {
		OVERRIDE.set(Objects.requireNonNull(executor, ERR_NULL_EXECUTOR));
	}

	/**
	 * Clear override so the next {@link #executor()} call uses the default pool again.
	 */
	public static void resetExecutor() {
		OVERRIDE.set(null);
	}

	/**
	 * Effective executor: override if set, otherwise lazy default {@code sql-client-sync-*} pool.
	 */
	public static Executor executor() {
		final Executor override = OVERRIDE.get();
		if (override != null) {
			return override;
		}
		return defaultPool();
	}

	/**
	 * {@code true} when the current thread is inside {@link #call(Supplier)} on a sync worker.
	 */
	public static boolean isSyncWorkerThread() {
		return Boolean.TRUE.equals(ON_SYNC_WORKER.get());
	}

	/**
	 * Run {@code action} on {@link #executor()}; if already on a sync worker, run inline.
	 * The calling thread joins the result (IDE / JDBC threads).
	 */
	public static <T> T call(Supplier<T> action) {
		return call(executor(), action);
	}

	/**
	 * Run {@code action} on {@code executor}; if already on a sync worker marked by this type, run inline.
	 * <p>
	 * Uses latch + refs (not {@link java.util.concurrent.CompletableFuture}) to avoid per-await CF alloc.
	 */
	public static <T> T call(Executor executor, Supplier<T> action) {
		Objects.requireNonNull(executor, ERR_NULL_EXECUTOR);
		Objects.requireNonNull(action, ERR_NULL_ACTION);
		if (isSyncWorkerThread()) {
			return action.get();
		}
		final CountDownLatch done = new CountDownLatch(1);
		final AtomicReference<T> value = new AtomicReference<>();
		final AtomicReference<Throwable> error = new AtomicReference<>();
		executor.execute(() -> {
			ON_SYNC_WORKER.set(Boolean.TRUE);
			try {
				value.set(action.get());
			} catch (Throwable t) {
				error.set(t);
			} finally {
				ON_SYNC_WORKER.remove();
				done.countDown();
			}
		});
		try {
			done.await();
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new SyncAwait.SyncInterruptedException(ERR_INTERRUPTED, ex);
		}
		final Throwable err = error.get();
		if (err != null) {
			if (err instanceof RuntimeException re) {
				throw re;
			}
			if (err instanceof Error e) {
				throw e;
			}
			throw new IllegalStateException(err);
		}
		return value.get();
	}

	private static ExecutorService defaultPool() {
		final ExecutorService existing = DEFAULT_POOL.get();
		if (existing != null) {
			return existing;
		}
		final ExecutorService created = Executors.newCachedThreadPool(syncThreadFactory());
		if (DEFAULT_POOL.compareAndSet(null, created)) {
			return created;
		}
		created.shutdown();
		return DEFAULT_POOL.get();
	}

	private static ThreadFactory syncThreadFactory() {
		return Thread.ofPlatform()
				.name(THREAD_NAME_PREFIX, 0)
				.daemon(true)
				.factory();
	}
}