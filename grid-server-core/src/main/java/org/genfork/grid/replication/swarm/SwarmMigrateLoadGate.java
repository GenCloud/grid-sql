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
package org.genfork.grid.replication.swarm;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Suppress migrate I/O unless the swarm is actively shedding load, local admission is calm,
 * apply lag is below the suppress floor, and calm has held for {@link #REQUIRED_CALM_TICKS}.
 * High apply lag suppresses (does not arm) migrate so cutover I/O cannot amplify lag.
 * Sensors/hints still update; only sealed/OpLog {@code migrateRange} emits are gated.
 *
 * @author: GenCloud
 * @date: 2026/06
 * @since: 1.0
 */
public final class SwarmMigrateLoadGate {
	/** Max replication inflight (queueDepth sensor) while still allowing migrateRange. */
	public static final double MAX_QUEUE_DEPTH_FOR_MIGRATE = 256.0d;
	/** Max heap pressure while still allowing migrateRange. */
	public static final double MAX_HEAP_FOR_MIGRATE = 0.80d;
	/**
	 * Apply lag at or above this value suppresses migrate I/O (named suppress floor).
	 */
	public static final double MAX_APPLY_LAG_FOR_MIGRATE = 64.0d;
	/** Sustained calm swarm ticks required after pressure before migrate I/O. */
	public static final int REQUIRED_CALM_TICKS = 3;

	private final AtomicInteger calmTicks = new AtomicInteger();
	private final LongAdder allowedCount = new LongAdder();
	private final LongAdder suppressedPressure = new LongAdder();
	private final LongAdder suppressedLag = new LongAdder();
	private final LongAdder suppressedHint = new LongAdder();

	/**
	 * True when queue/heap are calm and apply lag is below the suppress floor.
	 */
	public static boolean isPressureCalm(double queueDepth, double heapPressure, double applyLag) {
		return queueDepth <= MAX_QUEUE_DEPTH_FOR_MIGRATE
				&& heapPressure < MAX_HEAP_FOR_MIGRATE
				&& applyLag < MAX_APPLY_LAG_FOR_MIGRATE;
	}

	public static boolean isPressureCalm(PlacementScore score) {
		if (score == null) {
			return false;
		}
		return isPressureCalm(score.queueDepth(), score.heapPressure(), score.applyLag());
	}

	/**
	 * Update calm-tick hysteresis; {@code true} only on {@link PlacementHint#SHED_LOAD}
	 * after {@link #REQUIRED_CALM_TICKS} consecutive calm samples with lag below suppress floor.
	 */
	public boolean allowMigrateIo(PlacementScore score, PlacementHint hint) {
		if (hint != PlacementHint.SHED_LOAD) {
			calmTicks.set(0);
			suppressedHint.increment();
			return false;
		}
		if (score == null) {
			calmTicks.set(0);
			suppressedPressure.increment();
			return false;
		}
		if (score.applyLag() >= MAX_APPLY_LAG_FOR_MIGRATE) {
			calmTicks.set(0);
			suppressedLag.increment();
			return false;
		}
		if (!isPressureCalm(score.queueDepth(), score.heapPressure(), score.applyLag())) {
			calmTicks.set(0);
			suppressedPressure.increment();
			return false;
		}
		final boolean armed = calmTicks.incrementAndGet() >= REQUIRED_CALM_TICKS;
		if (armed) {
			allowedCount.increment();
		} else {
			suppressedPressure.increment();
		}
		return armed;
	}

	public long migrateIoAllowedCount() {
		return allowedCount.sum();
	}

	public long migrateIoSuppressedPressureCount() {
		return suppressedPressure.sum();
	}

	public long migrateIoSuppressedLagCount() {
		return suppressedLag.sum();
	}

	public long migrateIoSuppressedHintCount() {
		return suppressedHint.sum();
	}

	public long migrateIoSuppressedTotal() {
		return suppressedPressure.sum() + suppressedLag.sum() + suppressedHint.sum();
	}
}
