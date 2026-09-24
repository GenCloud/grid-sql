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
package org.genfork.grid.sql.netty;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.annotations.VisibleForTesting;

/**
 * Maps in-flight EXEC / BATCH_EXEC {@code requestId} → logic VT for cooperative CANCEL interrupt.
 * <p>
 * Atomics / {@link ConcurrentHashMap} only — no monitor wait on VT / Reactor / Netty EL.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlExecInterruptRegistry {
	private final Map<Integer, Thread> inflight = new ConcurrentHashMap<>();
	private final AtomicInteger sizeHint = new AtomicInteger();

	/**
	 * Register the current thread as the logic VT for {@code requestId}.
	 * Call from the session mailbox task before {@code engine.execute}.
	 */
	public void register(int requestId) {
		final Thread prev = inflight.put(requestId, Thread.currentThread());
		if (prev == null) {
			sizeHint.incrementAndGet();
		}
	}

	/**
	 * Unregister only if the current thread still owns {@code requestId}.
	 */
	public void unregister(int requestId) {
		if (inflight.remove(requestId, Thread.currentThread())) {
			sizeHint.decrementAndGet();
		}
	}

	/**
	 * Interrupt the logic VT for {@code requestId} if still registered.
	 *
	 * @return {@code true} if a thread was interrupted
	 */
	public boolean interrupt(int requestId) {
		final Thread t = inflight.get(requestId);
		if (t == null) {
			return false;
		}
		t.interrupt();
		return true;
	}

	public void clear() {
		inflight.clear();
		sizeHint.set(0);
	}

	@VisibleForTesting
	int size() {
		return inflight.size();
	}

	@VisibleForTesting
	Thread peek(int requestId) {
		return inflight.get(requestId);
	}
}
