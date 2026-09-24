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
package org.genfork.grid.mem;

import java.util.concurrent.locks.AbstractQueuedSynchronizer;

/**
 * Shared-permit lifecycle gate for mmap / unmap of sealed or off-heap structures.
 * <p>
 * Hold {@link #lockRead()} / {@link #lockWrite()} only around map, unmap, or close of the
 * underlying memory — never across SQL execution, digest quorum, or Netty event-loop wait.
 *
 * @author: GenCloud
 * @date: 2025/02
 * @since: 1.0
 */
public class LifecycleRwGate extends AbstractQueuedSynchronizer {
	private static final int READ_PERMITS = 1;
	private static final int WRITE_PERMITS = 0xFFFF;

	public LifecycleRwGate() {
		setState(WRITE_PERMITS);
	}

	public final void lockRead() {
		super.acquireShared(READ_PERMITS);
	}

	public final void unlockRead() {
		super.releaseShared(READ_PERMITS);
	}

	public final void lockWrite() {
		super.acquireShared(WRITE_PERMITS);
	}

	public final void unlockWrite() {
		super.releaseShared(WRITE_PERMITS);
	}

	@Override
	protected int tryAcquireShared(int acquires) {
		for (; ; ) {
			final int state = getState();
			final int remaining = state - acquires;
			if (remaining < 0 || compareAndSetState(state, remaining)) {
				return remaining;
			}

			Thread.onSpinWait();
		}
	}

	@Override
	protected final boolean tryReleaseShared(int releases) {
		for (; ; ) {
			final int state = getState();
			final int remaining = state + releases;
			if (compareAndSetState(state, remaining)) {
				return true;
			}

			Thread.onSpinWait();
		}
	}
}
