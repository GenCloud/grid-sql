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
package org.genfork.grid.query.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.util.CollectionUtils;
import org.genfork.grid.fs.GridFs;
import org.genfork.grid.mem.stage.TableQueue;
import org.genfork.grid.threading.ThreadService;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
public class TmpFileManager {
	private static final java.util.concurrent.atomic.AtomicReference<java.lang.Object> instance = new java.util.concurrent.atomic.AtomicReference<java.lang.Object>();
	private final TmpFileDeletionQueue deletionQueue = new TmpFileDeletionQueue();
	private final TmpFileWorker worker = new TmpFileWorker();

	public void addTmpForDeletionPath(Path path) {
		deletionQueue.addLast(path);
		if (!worker.running.get()) {
			worker.start();
		}
	}


	class TmpFileWorker implements Runnable {
		private final AtomicBoolean running = new AtomicBoolean();

		public void start() {
			if (running.compareAndSet(false, true)) {
				ThreadService.getScheduledExecutor().schedule(this, 100, MILLISECONDS);
			}
		}

		public void stop() {
			running.set(false);
		}

		@Override
		public void run() {
			try {
				final List<Path> entries = new ArrayList<>();
				deletionQueue.drainTo(entries);
				if (!CollectionUtils.isEmpty(entries)) {
					for (Path entry : entries) {
						try {
							GridFs.deleteIfExists(entry);
						} catch (IOException e) {
							throw new RuntimeException(e);
						}
					}
				}
			} finally {
				if (running.get()) {
					ThreadService.getScheduledExecutor().schedule(this, 100, MILLISECONDS);
				}
			}
		}
	}


	static class TmpFileDeletionQueue implements TableQueue<Path> {
		private static final int MAX_DRAIN_SIZE = 5;
		private final Queue<Path> queue = new ConcurrentLinkedQueue<>();

		@Override
		public void addLast(Path path) {
			queue.offer(path);
		}

		@Override
		public void drainTo(Collection<Path> collection) {
			Objects.requireNonNull(collection);
			int i = 0;
			Path path;
			while (i < MAX_DRAIN_SIZE && (path = queue.poll()) != null) {
				collection.add(path);
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

	@java.lang.SuppressWarnings({"all", "unchecked"})
	public static TmpFileManager getInstance() {
		java.lang.Object $value = TmpFileManager.instance.get();
		if ($value == null) {
			synchronized (TmpFileManager.instance) {
				$value = TmpFileManager.instance.get();
				if ($value == null) {
					final TmpFileManager actualValue = new TmpFileManager();
					$value = actualValue == null ? TmpFileManager.instance : actualValue;
					TmpFileManager.instance.set($value);
				}
			}
		}
		return (TmpFileManager) ($value == TmpFileManager.instance ? null : $value);
	}
}
