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
package org.genfork.grid.replication.tx;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Serializes begin→data→commit OpLog units per {@code domain#shard} so concurrent TX
 * cannot interleave {@code TX_BEGIN} on the same stream (ship-buffer / replica staging poison).
 * <p>
 * Locks are acquired in sorted stream-key order to avoid deadlock across multi-shard TX.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public final class StreamCommitSerializer {
	private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

	public void runWithLocks(Collection<String> streamKeys, Runnable action) {
		final List<String> ordered = orderedKeys(streamKeys);
		lockAll(ordered);
		try {
			action.run();
		} finally {
			unlockAll(ordered);
		}
	}

	public <T> T callWithLocks(Collection<String> streamKeys, Supplier<T> action) {
		final List<String> ordered = orderedKeys(streamKeys);
		lockAll(ordered);
		try {
			return action.get();
		} finally {
			unlockAll(ordered);
		}
	}

	private static List<String> orderedKeys(Collection<String> streamKeys) {
		if (streamKeys == null || streamKeys.isEmpty()) {
			return List.of();
		}
		final List<String> ordered = new ArrayList<>(streamKeys);
		Collections.sort(ordered);
		return ordered;
	}

	private void lockAll(List<String> ordered) {
		for (int i = 0; i < ordered.size(); i++) {
			locks.computeIfAbsent(ordered.get(i), ignored -> new ReentrantLock()).lock();
		}
	}

	private void unlockAll(List<String> ordered) {
		for (int i = ordered.size() - 1; i >= 0; i--) {
			final ReentrantLock lock = locks.get(ordered.get(i));
			if (lock != null) {
				lock.unlock();
			}
		}
	}
}