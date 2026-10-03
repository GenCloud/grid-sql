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
package org.genfork.grid.replication.metrics;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Working-set / durability ops counters (Prometheus via GridMetricsBinder).
 *
 * @author: GenCloud
 * @date: 2026/05
 * @since: 1.0
 */
public final class DurabilityMetrics {
	private static final LongAdder WS_EVICTIONS = new LongAdder();
	private static final AtomicInteger WS_SIZE = new AtomicInteger();
	private static final AtomicInteger WS_MAX_ENTRIES = new AtomicInteger();

	private DurabilityMetrics() {
	}

	public static void recordWsEviction() {
		WS_EVICTIONS.increment();
	}

	public static void recordWsEvictions(int count) {
		if (count > 0) {
			WS_EVICTIONS.add(count);
		}
	}

	public static long wsEvictions() {
		return WS_EVICTIONS.sum();
	}

	public static void setWsSize(int size) {
		WS_SIZE.set(Math.max(0, size));
	}

	public static int wsSize() {
		return WS_SIZE.get();
	}

	public static void setWsMaxEntries(int maxEntries) {
		WS_MAX_ENTRIES.set(Math.max(0, maxEntries));
	}

	public static int wsMaxEntries() {
		return WS_MAX_ENTRIES.get();
	}

	public static void reset() {
		WS_EVICTIONS.reset();
		WS_SIZE.set(0);
		WS_MAX_ENTRIES.set(0);
	}
}