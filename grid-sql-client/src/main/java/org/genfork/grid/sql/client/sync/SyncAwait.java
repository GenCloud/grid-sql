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

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import io.netty.util.concurrent.FastThreadLocalThread;

/**
 * Blocking await for {@link CompletionStage} only.
 * <p>
 * Sync / JDBC must never use {@code Mono.toFuture}; reactive SPI stays on {@code Mono}/{@code Flux}
 * via {@code ReactiveExecExchange} / {@code ReactiveBatchExchange} (Sinks).
 * <p>
 * Join runs <strong>inline on the caller</strong> unless the caller is a Netty event-loop thread
 * ({@link FastThreadLocalThread}) — then the join is offloaded to {@link SyncExecutors} so the EL
 * is never blocked. Already-done stages always join inline (no hop).
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public final class SyncAwait {
	/** Default await budget when caller omits timeout. */
	public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

	private static final String ERR_NULL_STAGE = "completion stage required";
	private static final String ERR_TIMEOUT = "operation timed out";
	private static final String ERR_CANCELLED = "operation cancelled";
	private static final String ERR_INTERRUPTED = "operation interrupted";

	private SyncAwait() {
	}

	public static <T> T await(CompletionStage<T> stage) {
		return await(stage, DEFAULT_TIMEOUT, null);
	}

	public static <T> T await(CompletionStage<T> stage, Duration timeout) {
		return await(stage, timeout, null);
	}

	/**
	 * @param cancelSlot if non-null, receives a {@link Runnable} that cancels the in-flight await
	 */
	public static <T> T await(CompletionStage<T> stage, Duration timeout, Consumer<Runnable> cancelSlot) {
		return await(stage, timeout, cancelSlot, null);
	}

	/**
	 * @param executor if null, {@link SyncExecutors#executor()} (override or default pool); used only
	 *                 when the caller is on a Netty event loop
	 */
	public static <T> T await(
			CompletionStage<T> stage,
			Duration timeout,
			Consumer<Runnable> cancelSlot,
			Executor executor
	) {
		Objects.requireNonNull(stage, ERR_NULL_STAGE);
		final Duration wait = normalize(timeout);
		final CompletableFuture<T> future = stage.toCompletableFuture();
		if (cancelSlot != null) {
			cancelSlot.accept(() -> future.cancel(true));
		}
		try {
			if (future.isDone() || !onNettyEventLoop()) {
				return joinFuture(future, wait);
			}
			final Executor syncExecutor = executor == null ? SyncExecutors.executor() : executor;
			return SyncExecutors.call(syncExecutor, () -> joinFuture(future, wait));
		} finally {
			if (cancelSlot != null) {
				cancelSlot.accept(null);
			}
		}
	}

	public static void awaitVoid(CompletionStage<Void> stage, Duration timeout, Executor executor) {
		await(stage, timeout, null, executor);
	}

	/**
	 * Netty EL threads implement {@link FastThreadLocalThread}; Sync/JDBC must not {@code get()} there.
	 */
	static boolean onNettyEventLoop() {
		return Thread.currentThread() instanceof FastThreadLocalThread;
	}

	private static Duration normalize(Duration timeout) {
		if (timeout == null || timeout.isNegative() || timeout.isZero()) {
			return DEFAULT_TIMEOUT;
		}
		return timeout;
	}

	private static <T> T joinFuture(CompletableFuture<T> future, Duration wait) {
		try {
			return future.get(wait.toMillis(), TimeUnit.MILLISECONDS);
		} catch (TimeoutException ex) {
			future.cancel(true);
			throw new SyncTimeoutException(ERR_TIMEOUT, ex);
		} catch (CancellationException ex) {
			throw new SyncCancelledException(ERR_CANCELLED, ex);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			future.cancel(true);
			throw new SyncInterruptedException(ERR_INTERRUPTED, ex);
		} catch (ExecutionException ex) {
			throw unwrapExecution(ex);
		}
	}

	private static RuntimeException unwrapExecution(ExecutionException ex) {
		final Throwable cause = ex.getCause();
		if (cause instanceof RuntimeException re) {
			return re;
		}
		if (cause instanceof Error err) {
			throw err;
		}
		return new IllegalStateException(cause == null ? ex : cause);
	}

	/**
	 * Await timed out.
	 *
	 * @author: GenCloud
	 * @date: 2026/08
	 * @since: 1.0
	 */
	public static final class SyncTimeoutException extends RuntimeException {
		public SyncTimeoutException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	/**
	 * Await cancelled via cancel hook or stage cancel.
	 *
	 * @author: GenCloud
	 * @date: 2026/08
	 * @since: 1.0
	 */
	public static final class SyncCancelledException extends RuntimeException {
		public SyncCancelledException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	/**
	 * Caller thread interrupted while awaiting.
	 *
	 * @author: GenCloud
	 * @date: 2026/08
	 * @since: 1.0
	 */
	public static final class SyncInterruptedException extends RuntimeException {
		public SyncInterruptedException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
