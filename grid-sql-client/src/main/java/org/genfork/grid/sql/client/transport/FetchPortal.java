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
package org.genfork.grid.sql.client.transport;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;

/**
 * Shared FETCH window + row buffer used by exec and batch statement slots.
 * <p>
 * Reactor-free: demand drives {@code FETCH(n)}; rows buffer for sync take or push to a
 * single {@link RowPortal.RowListener}.
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public final class FetchPortal implements RowPortal {
	private static final Object DONE_SENTINEL = new Object();
	private static final Duration DEFAULT_TAKE_TIMEOUT = Duration.ofSeconds(30);
	private static final String ERR_TAKE_TIMEOUT = "row portal take timed out";
	private static final String ERR_LISTENER_REQUIRED = "listener required";
	private static final String ERR_LISTENER_ATTACHED = "row listener already attached";

	private final int fetchWindow;
	private final IntConsumer fetchSender;
	private final Runnable cancelSender;
	private final AtomicBoolean batchCancelled;
	private final AtomicBoolean done = new AtomicBoolean();
	private final AtomicBoolean cancelled = new AtomicBoolean();
	/** True while waiting for the initial server window or an in-flight FETCH. */
	private final AtomicBoolean fetchInFlight = new AtomicBoolean(true);
	private final AtomicInteger receivedSinceFetch = new AtomicInteger();
	private final AtomicLong demand = new AtomicLong();
	private volatile int lastFetchSize;
	private final ConcurrentLinkedQueue<Object[]> buffer = new ConcurrentLinkedQueue<>();
	private final LinkedBlockingQueue<Object> takeQueue = new LinkedBlockingQueue<>();
	private final CompletableFuture<Void> completion = new CompletableFuture<>();
	private final AtomicReference<RowListener> listener = new AtomicReference<>();
	private volatile Throwable error;

	public FetchPortal(int fetchWindow, IntConsumer fetchSender, Runnable cancelSender) {
		this(fetchWindow, fetchSender, cancelSender, null);
	}

	/**
	 * @param batchCancelled when non-null, cancel is shared across batch statement slots
	 */
	public FetchPortal(
			int fetchWindow,
			IntConsumer fetchSender,
			Runnable cancelSender,
			AtomicBoolean batchCancelled
	) {
		this.fetchWindow = Math.max(1, fetchWindow);
		this.fetchSender = fetchSender;
		this.cancelSender = cancelSender;
		this.batchCancelled = batchCancelled;
		this.lastFetchSize = this.fetchWindow;
	}

	@Override
	public void request(long n) {
		if (n > 0L) {
			demand.addAndGet(n);
			tryFetch();
		}
	}

	@Override
	public Object[] poll() {
		return buffer.poll();
	}

	@Override
	public Object[] take(Duration timeout) throws InterruptedException, TimeoutException {
		final Duration wait = timeout == null || timeout.isNegative() || timeout.isZero()
				? DEFAULT_TAKE_TIMEOUT
				: timeout;
		final Object item = takeQueue.poll(wait.toMillis(), TimeUnit.MILLISECONDS);
		if (item == null) {
			throw new TimeoutException(ERR_TAKE_TIMEOUT);
		}
		if (item == DONE_SENTINEL) {
			return null;
		}
		if (item instanceof Throwable t) {
			if (t instanceof RuntimeException re) {
				throw re;
			}
			if (t instanceof Error err) {
				throw err;
			}
			throw new IllegalStateException(t);
		}
		return (Object[]) item;
	}

	@Override
	public void cancel() {
		if (done.get()) {
			return;
		}
		if (batchCancelled != null) {
			if (!batchCancelled.compareAndSet(false, true)) {
				return;
			}
		} else if (!cancelled.compareAndSet(false, true)) {
			return;
		}
		if (cancelSender != null) {
			cancelSender.run();
		}
	}

	@Override
	public CompletionStage<Void> completion() {
		return completion;
	}

	@Override
	public boolean isDone() {
		return done.get();
	}

	@Override
	public Throwable error() {
		return error;
	}

	@Override
	public void attach(RowListener rowListener) {
		if (rowListener == null) {
			throw new IllegalArgumentException(ERR_LISTENER_REQUIRED);
		}
		if (!listener.compareAndSet(null, rowListener)) {
			throw new IllegalStateException(ERR_LISTENER_ATTACHED);
		}
		Object[] row;
		while ((row = buffer.poll()) != null) {
			rowListener.onRow(row);
		}
		if (done.get()) {
			if (error != null) {
				rowListener.onError(error);
			} else {
				rowListener.onComplete();
			}
		}
	}

	/** Inbound ROW_DATA. */
	public void offerRow(Object[] row) {
		if (done.get()) {
			return;
		}
		final RowListener live = listener.get();
		if (live != null) {
			live.onRow(row);
		} else {
			buffer.offer(row);
		}
		takeQueue.offer(row);
		if (receivedSinceFetch.incrementAndGet() >= lastFetchSize) {
			fetchInFlight.set(false);
			tryFetch();
		}
	}

	/** Portal completed normally (EXEC_DONE after RESULT_SET). */
	public void complete() {
		if (!done.compareAndSet(false, true)) {
			return;
		}
		fetchInFlight.set(false);
		takeQueue.offer(DONE_SENTINEL);
		completion.complete(null);
		final RowListener live = listener.get();
		if (live != null) {
			live.onComplete();
		}
	}

	/** Portal failed. */
	public void fail(Throwable t) {
		done.set(true);
		error = t;
		fetchInFlight.set(false);
		takeQueue.offer(t);
		completion.completeExceptionally(t);
		final RowListener live = listener.get();
		if (live != null) {
			live.onError(t);
		}
	}

	private void tryFetch() {
		if (done.get() || fetchSender == null) {
			return;
		}
		if (batchCancelled != null && batchCancelled.get()) {
			return;
		}
		if (cancelled.get()) {
			return;
		}
		final long d = demand.get();
		if (d <= 0L) {
			return;
		}
		if (!fetchInFlight.compareAndSet(false, true)) {
			return;
		}
		final int want = (int) Math.min(d, fetchWindow);
		if (want <= 0) {
			fetchInFlight.set(false);
			return;
		}
		demand.addAndGet(-want);
		lastFetchSize = want;
		receivedSinceFetch.set(0);
		fetchSender.accept(want);
	}
}