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
package org.genfork.grid.metrics;

import java.util.concurrent.atomic.LongAdder;

/**
 * Process-wide distributed query fan-in, map-reduce, and cancellation fairness counters.
 *
 * @author: GenCloud
 * @date: 2026/07
 * @since: 1.0
 */
public final class DistributedQueryMetrics {
	private static final LongAdder FAN_IN_CALLS = new LongAdder();
	private static final LongAdder FAN_IN_SOURCES = new LongAdder();
	private static final LongAdder FAN_IN_ROWS = new LongAdder();
	private static final LongAdder MAP_REDUCE_CALLS = new LongAdder();
	private static final LongAdder MAP_REDUCE_STAGES = new LongAdder();
	private static final LongAdder MAP_REDUCE_ROWS = new LongAdder();
	private static final LongAdder CANCEL_REQUESTS = new LongAdder();
	private static final LongAdder CANCEL_ACTIVE = new LongAdder();
	private static final LongAdder AQE_RESPLIT = new LongAdder();
	private static final LongAdder AQE_COALESCE = new LongAdder();

	private DistributedQueryMetrics() {
	}

	public static void recordFanIn(int sources, int rows) {
		FAN_IN_CALLS.increment();
		FAN_IN_SOURCES.add(Math.max(0, sources));
		FAN_IN_ROWS.add(Math.max(0, rows));
	}

	/**
	 * Record one adaptive / distributed map-reduce fan-in (stage count + merged rows).
	 */
	public static void recordMapReduce(int stages, int rows) {
		MAP_REDUCE_CALLS.increment();
		MAP_REDUCE_STAGES.add(Math.max(0, stages));
		MAP_REDUCE_ROWS.add(Math.max(0, rows));
	}

	public static void recordCancel(boolean activeRequest) {
		CANCEL_REQUESTS.increment();
		if (activeRequest) {
			CANCEL_ACTIVE.increment();
		}
	}

	/** Mid-flight AQE re-split of remaining work. */
	public static void recordAqeResplit() {
		AQE_RESPLIT.increment();
	}

	/** Mid-flight AQE coalesce of tiny unfinished chunks. */
	public static void recordAqeCoalesce() {
		AQE_COALESCE.increment();
	}

	public static long fanInCalls() {
		return FAN_IN_CALLS.sum();
	}

	public static long fanInSources() {
		return FAN_IN_SOURCES.sum();
	}

	public static long fanInRows() {
		return FAN_IN_ROWS.sum();
	}

	public static long mapReduceCalls() {
		return MAP_REDUCE_CALLS.sum();
	}

	public static long mapReduceStages() {
		return MAP_REDUCE_STAGES.sum();
	}

	public static long mapReduceRows() {
		return MAP_REDUCE_ROWS.sum();
	}

	public static long cancelRequests() {
		return CANCEL_REQUESTS.sum();
	}

	public static long cancelActive() {
		return CANCEL_ACTIVE.sum();
	}

	public static long aqeResplit() {
		return AQE_RESPLIT.sum();
	}

	public static long aqeCoalesce() {
		return AQE_COALESCE.sum();
	}
}