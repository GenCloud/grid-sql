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

/**
 * Suppress migrate I/O unless the swarm is actively shedding load, local admission is calm,
 * and calm has held for {@link #REQUIRED_CALM_TICKS}. Prevents cool-edge / ramp speculative shed.
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
	/** Minimum peer apply lag before shed cutover I/O is allowed. */
	public static final double MIN_APPLY_LAG_FOR_MIGRATE = 64.0d;
	/** Sustained calm swarm ticks required after pressure before migrate I/O. */
	public static final int REQUIRED_CALM_TICKS = 3;

	private final AtomicInteger calmTicks = new AtomicInteger();

	public static boolean isPressureCalm(double queueDepth, double heapPressure, double applyLag) {
		return queueDepth <= MAX_QUEUE_DEPTH_FOR_MIGRATE
				&& heapPressure < MAX_HEAP_FOR_MIGRATE
				&& applyLag >= MIN_APPLY_LAG_FOR_MIGRATE;
	}

	public static boolean isPressureCalm(PlacementScore score) {
		if (score == null) {
			return false;
		}
		return isPressureCalm(score.queueDepth(), score.heapPressure(), score.applyLag());
	}

	/**
	 * Update calm-tick hysteresis; {@code true} only on {@link PlacementHint#SHED_LOAD}
	 * after {@link #REQUIRED_CALM_TICKS} consecutive calm samples.
	 */
	public boolean allowMigrateIo(PlacementScore score, PlacementHint hint) {
		if (hint != PlacementHint.SHED_LOAD || !isPressureCalm(score)) {
			calmTicks.set(0);
			return false;
		}
		return calmTicks.incrementAndGet() >= REQUIRED_CALM_TICKS;
	}

	public boolean allowMigrateIo(PlacementScore score) {
		return allowMigrateIo(score, PlacementHint.SHED_LOAD);
	}
}