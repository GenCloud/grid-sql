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
package org.genfork.grid.replication.durable;

import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Group-commit gate: concurrent waiters share one durable force covering the highest tip.
 * <p>
 * Waiters park via {@link LockSupport} (VT-safe) — no {@code Object.wait} across the force.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public final class GroupForceGate {
	private static final long PARK_SLICE_NS = 100_000L;
	/** No artificial gather — concurrent waiters still coalesce via pendingTip during force. */
	private static final long GATHER_WINDOW_NS = 0L;

	private final AtomicLong persistedTip = new AtomicLong();
	private final AtomicLong pendingTip = new AtomicLong();
	private final AtomicBoolean syncInProgress = new AtomicBoolean();
	private final ConcurrentLinkedQueue<Thread> waiters = new ConcurrentLinkedQueue<>();

	@FunctionalInterface
	public interface ForceAction {
		void force(long tipToCover) throws IOException;
	}

	public GroupForceGate() {
		this(0L);
	}

	public GroupForceGate(long initialTip) {
		persistedTip.set(initialTip);
		pendingTip.set(initialTip);
	}

	/** Reset tip after truncate / checkpoint (file already matches). */
	public void resetTip(long tip) {
		persistedTip.set(tip);
		pendingTip.set(tip);
	}

	public long persistedTip() {
		return persistedTip.get();
	}

	/**
	 * Ensure durable coverage through {@code coverThrough}. {@code forceAction} receives the
	 * tip to force (max pending) and must perform the durable syscall.
	 * <p>
	 * After a force, if {@code pendingTip} grew during the syscall, one extra force covers
	 * the new tip before waiters are unparked (no mid-drain unpark storm).
	 */
	public void awaitCovered(long coverThrough, ForceAction forceAction) throws IOException {
		if (coverThrough <= 0L) {
			return;
		}
		for (;;) {
			bumpPending(coverThrough);
			if (persistedTip.get() >= coverThrough) {
				return;
			}
			if (syncInProgress.compareAndSet(false, true)) {
				try {
					if (GATHER_WINDOW_NS > 0L) {
						LockSupport.parkNanos(GATHER_WINDOW_NS);
					}
					forceThroughPending(forceAction);
				} finally {
					syncInProgress.set(false);
					unparkWaiters();
				}
				if (persistedTip.get() >= coverThrough) {
					return;
				}
				continue;
			}
			parkUntilProgress(coverThrough);
			if (persistedTip.get() >= coverThrough) {
				return;
			}
		}
	}

	/**
	 * Leader drain: force current pending tip, then at most one extra force if pending grew.
	 */
	private void forceThroughPending(ForceAction forceAction) throws IOException {
		long tipToWrite = pendingTip.get();
		forceAction.force(tipToWrite);
		advancePersisted(tipToWrite);
		final long grewTo = pendingTip.get();
		if (grewTo > tipToWrite) {
			forceAction.force(grewTo);
			advancePersisted(grewTo);
		}
	}

	private void bumpPending(long coverThrough) {
		long cur;
		do {
			cur = pendingTip.get();
			if (coverThrough <= cur) {
				return;
			}
		} while (!pendingTip.compareAndSet(cur, coverThrough));
	}

	private void advancePersisted(long tipToWrite) {
		long prev;
		do {
			prev = persistedTip.get();
			if (tipToWrite <= prev) {
				return;
			}
		} while (!persistedTip.compareAndSet(prev, tipToWrite));
	}

	private void parkUntilProgress(long coverThrough) throws IOException {
		final Thread self = Thread.currentThread();
		waiters.offer(self);
		try {
			while (persistedTip.get() < coverThrough && syncInProgress.get()) {
				LockSupport.parkNanos(this, PARK_SLICE_NS);
				if (Thread.interrupted()) {
					Thread.currentThread().interrupt();
					throw new IOException("Interrupted during group force");
				}
			}
		} finally {
			waiters.remove(self);
		}
	}

	private void unparkWaiters() {
		Thread waiter;
		while ((waiter = waiters.poll()) != null) {
			LockSupport.unpark(waiter);
		}
	}
}
