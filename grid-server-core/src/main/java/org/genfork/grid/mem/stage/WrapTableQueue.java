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
package org.genfork.grid.mem.stage;

import java.util.Collection;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * @author: GenCloud
 * @date: 2026/02
 * @since: 1.0
 */
public class WrapTableQueue implements TableQueue<GridEntriesProcessor.Entry> {
	private static final int MAX_DRAIN_SIZE = 100_000;

	private final Queue<GridEntriesProcessor.Entry> queue = new ConcurrentLinkedQueue<>();

	@Override
	public void addLast(GridEntriesProcessor.Entry e) {
		queue.offer(e);
	}

	@Override
	public void drainTo(Collection<GridEntriesProcessor.Entry> collection) {
		Objects.requireNonNull(collection);

		int i = 0;
		GridEntriesProcessor.Entry entry;
		while(i < MAX_DRAIN_SIZE && (entry = queue.poll()) != null) {
			collection.add(entry);
			i++;
		}
	}

	@Override
	public int size() {
		return queue.size();
	}

	@Override
	public void clear() {
		queue.clear();
	}
}
