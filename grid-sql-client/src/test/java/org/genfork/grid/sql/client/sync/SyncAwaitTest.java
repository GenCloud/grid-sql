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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.netty.util.concurrent.FastThreadLocalThread;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SyncAwait timeout / cancel / EL-offload behaviour.
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
class SyncAwaitTest {
	private static final Duration SHORT_TIMEOUT = Duration.ofMillis(50);
	private static final String CUSTOM_WORKER_NAME = "custom-sync-worker";

	@AfterEach
	void resetSyncExecutor() {
		SyncExecutors.resetExecutor();
	}

	@Test
	void awaitCompletesValue() {
		final CompletableFuture<String> future = CompletableFuture.completedFuture("ok");
		assertEquals("ok", SyncAwait.await(future, Duration.ofSeconds(1)));
	}

	@Test
	void awaitJoinsInlineOffNettyEventLoop() {
		final AtomicBoolean executorInvoked = new AtomicBoolean();
		SyncExecutors.setExecutor(command -> {
			executorInvoked.set(true);
			command.run();
		});
		final CompletableFuture<String> future = new CompletableFuture<>();
		future.complete("ok");
		assertEquals("ok", SyncAwait.await(future, Duration.ofSeconds(1)));
		assertFalse(executorInvoked.get());
		assertFalse(SyncAwait.onNettyEventLoop());
	}

	@Test
	void awaitOffloadsJoinFromNettyEventLoopThread() throws Exception {
		final AtomicBoolean executorInvoked = new AtomicBoolean();
		final AtomicReference<String> workerName = new AtomicReference<>();
		SyncExecutors.setExecutor(command -> {
			executorInvoked.set(true);
			final Thread worker = new Thread(command, CUSTOM_WORKER_NAME);
			workerName.set(CUSTOM_WORKER_NAME);
			worker.start();
			try {
				worker.join(Duration.ofSeconds(5).toMillis());
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(ex);
			}
		});

		final CountDownLatch started = new CountDownLatch(1);
		final AtomicReference<String> result = new AtomicReference<>();
		final AtomicReference<Throwable> error = new AtomicReference<>();
		final CompletableFuture<String> future = new CompletableFuture<>();
		final Thread el = new FastThreadLocalThread(() -> {
			started.countDown();
			try {
				result.set(SyncAwait.await(future, Duration.ofSeconds(2)));
			} catch (Throwable t) {
				error.set(t);
			}
		}, "fake-netty-el");
		el.start();
		assertTrue(started.await(2L, TimeUnit.SECONDS));
		Thread.sleep(20L);
		future.complete("ok");
		el.join(Duration.ofSeconds(5).toMillis());
		assertEquals(null, error.get());
		assertEquals("ok", result.get());
		assertTrue(executorInvoked.get());
		assertEquals(CUSTOM_WORKER_NAME, workerName.get());
	}

	@Test
	void awaitTimesOutAndCancels() {
		final CompletableFuture<String> future = new CompletableFuture<>();
		assertThrows(SyncAwait.SyncTimeoutException.class,
				() -> SyncAwait.await(future, SHORT_TIMEOUT));
		assertTrue(future.isCancelled());
	}

	@Test
	void awaitCancelHookCancelsStage() {
		final CompletableFuture<String> future = new CompletableFuture<>();
		final AtomicReference<Runnable> cancelRef = new AtomicReference<>();
		final AtomicBoolean started = new AtomicBoolean();
		final Thread waiter = new Thread(() -> {
			started.set(true);
			assertThrows(SyncAwait.SyncCancelledException.class,
					() -> SyncAwait.await(future, Duration.ofSeconds(5), cancelRef::set));
		}, "sync-await-test");
		waiter.start();
		while (!started.get() || cancelRef.get() == null) {
			Thread.onSpinWait();
		}
		cancelRef.get().run();
		try {
			waiter.join(Duration.ofSeconds(2).toMillis());
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
		assertTrue(future.isCancelled());
	}

	@Test
	void unwrapsExecutionExceptionCause() {
		final CompletableFuture<String> future = new CompletableFuture<>();
		future.completeExceptionally(new IllegalStateException("boom"));
		final IllegalStateException ex = assertThrows(IllegalStateException.class,
				() -> SyncAwait.await(future, Duration.ofSeconds(1)));
		assertEquals("boom", ex.getMessage());
	}
}