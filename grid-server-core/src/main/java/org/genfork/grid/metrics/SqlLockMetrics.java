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
 * Process-wide SQL record-lock wait / timeout / cancel counters.
 *
 * @author: GenCloud
 * @date: 2026/07
 * @since: 1.0
 */
public final class SqlLockMetrics {
	private static final LongAdder WAIT_ACQUIRES = new LongAdder();
	private static final LongAdder WAIT_TIMEOUTS = new LongAdder();
	private static final LongAdder WAIT_CANCELS = new LongAdder();
	private static final LongAdder WAIT_NANOS = new LongAdder();

	private SqlLockMetrics() {
	}

	public static void recordAcquire(long waitedNanos) {
		WAIT_ACQUIRES.increment();
		if (waitedNanos > 0L) {
			WAIT_NANOS.add(waitedNanos);
		}
	}

	public static void recordTimeout() {
		WAIT_TIMEOUTS.increment();
	}

	public static void recordCancel() {
		WAIT_CANCELS.increment();
	}

	public static long waitAcquires() {
		return WAIT_ACQUIRES.sum();
	}

	public static long waitTimeouts() {
		return WAIT_TIMEOUTS.sum();
	}

	public static long waitCancels() {
		return WAIT_CANCELS.sum();
	}

	public static long waitNanosTotal() {
		return WAIT_NANOS.sum();
	}
}
