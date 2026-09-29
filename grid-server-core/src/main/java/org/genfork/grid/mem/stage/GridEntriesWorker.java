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

import org.genfork.grid.mem.stage.GridEntriesProcessor.Entry;
import org.genfork.grid.threading.ThreadService;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Event-driven drain of {@link GridEntriesProcessor} queue (no 100ms floor on busy path).
 * <p>
 * One wake never overlaps {@code process()} on the same shard (stale LWW). Work per wake is
 * capped so a busy shard cannot monopolize the shared {@code grid-sched-*} pool (size 2)
 * and starve orchid / peer-shard drains (WRITE p99).
 *
 * @author: GenCloud
 * @date: 2026/02
 * @since: 1.0
 */
public class GridEntriesWorker implements Runnable {
	/**
	 * Max {@code drainTo}+{@code process} rounds per scheduled wake before yielding the
	 * sched thread and re-arming via {@link #signal()}.
	 */
	private static final int MAX_DRAIN_BATCHES_PER_WAKE = 8;

	private final GridEntriesProcessor gridEntriesProcessor;
	private final AtomicBoolean running = new AtomicBoolean();
	private final AtomicBoolean scheduled = new AtomicBoolean();

	public GridEntriesWorker(GridEntriesProcessor gridEntriesProcessor) {
		this.gridEntriesProcessor = gridEntriesProcessor;
		gridEntriesProcessor.setWakeSignal(this::signal);
	}

	public void start() {
		if (running.compareAndSet(false, true)) {
			signal();
		}
	}

	public void stop() {
		running.set(false);
	}

	/** Wake for immediate drain (coalesced: at most one pending schedule). */
	public void signal() {
		if (!running.get()) {
			return;
		}
		if (scheduled.compareAndSet(false, true)) {
			try {
				ThreadService.getScheduledExecutor().schedule(this, 0, MILLISECONDS);
			} catch (RuntimeException ex) {
				scheduled.set(false);
				throw ex;
			}
		}
	}

	@Override
	public void run() {
		if (!running.get()) {
			scheduled.set(false);
			return;
		}

		try {
			// Hold scheduled=true while processing so signal() cannot start a second
			// process() on this shard (overlapping coalesce → stale last-write-wins).
			int batches = 0;
			while (running.get() && batches < MAX_DRAIN_BATCHES_PER_WAKE) {
				final List<Entry> entries = new ArrayList<>();
				gridEntriesProcessor.getQueue().drainTo(entries);
				if (CollectionUtils.isEmpty(entries)) {
					break;
				}
				gridEntriesProcessor.process(entries);
				batches++;
			}
			if (running.get() && !gridEntriesProcessor.isIdle()) {
				gridEntriesProcessor.reclaimCommittedStaging();
			}
		} finally {
			scheduled.set(false);
			if (running.get()) {
				if (gridEntriesProcessor.getQueue().size() > 0
						|| !gridEntriesProcessor.isIdle()) {
					signal();
				}
			}
		}
	}
}
