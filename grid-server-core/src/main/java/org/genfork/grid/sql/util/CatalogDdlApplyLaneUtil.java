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
package org.genfork.grid.sql.util;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Serial catalog DDL apply lane (recover + peer {@code applyReplicatedDdl}).
 * <p>
 * Callers park on a per-job future while one drain thread runs the queue — no nested
 * {@code applyingReplicatedDdl} CAS races. Must not be invoked on Netty EL (logic VT / apply
 * mailbox only). Same-thread re-entry runs inline to avoid self-deadlock.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
public final class CatalogDdlApplyLaneUtil {
	private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
	private final AtomicBoolean draining = new AtomicBoolean();
	private final ThreadLocal<Boolean> onLane = ThreadLocal.withInitial(() -> Boolean.FALSE);

	/**
	 * Enqueue {@code job} and wait until it has run (serial with other catalog DDL jobs).
	 */
	public void runSerial(Runnable job) {
		if (job == null) {
			return;
		}
		if (Boolean.TRUE.equals(onLane.get())) {
			job.run();
			return;
		}
		final CompletableFuture<Void> done = new CompletableFuture<>();
		queue.offer(() -> {
			try {
				job.run();
				done.complete(null);
			} catch (Throwable t) {
				done.completeExceptionally(t);
			}
		});
		drain();
		try {
			done.join();
		} catch (CompletionException ex) {
			final Throwable cause = ex.getCause();
			if (cause instanceof RuntimeException runtime) {
				throw runtime;
			}
			if (cause instanceof Error error) {
				throw error;
			}
			throw ex;
		}
	}

	private void drain() {
		if (!draining.compareAndSet(false, true)) {
			return;
		}
		onLane.set(Boolean.TRUE);
		try {
			Runnable next;
			while ((next = queue.poll()) != null) {
				next.run();
			}
		} finally {
			onLane.set(Boolean.FALSE);
			draining.set(false);
			if (!queue.isEmpty()) {
				drain();
			}
		}
	}
}
