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

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Serial mailbox: tasks run one-at-a-time without {@code synchronized} / parking.
 * Safe on virtual threads — never pins a carrier waiting on a monitor.
 *
 * @author: GenCloud
 * @date: 2025/02
 * @since: 1.0
 */
public final class SerialTaskQueue {
	private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
	private final AtomicBoolean draining = new AtomicBoolean();

	public void submit(Executor executor, Runnable task) {
		queue.offer(task);
		trySchedule(executor);
	}

	private void trySchedule(Executor executor) {
		if (!draining.compareAndSet(false, true)) {
			return;
		}
		executor.execute(() -> {
			try {
				Runnable next;
				while ((next = queue.poll()) != null) {
					next.run();
				}
			} finally {
				draining.set(false);
				if (!queue.isEmpty()) {
					trySchedule(executor);
				}
			}
		});
	}
}
