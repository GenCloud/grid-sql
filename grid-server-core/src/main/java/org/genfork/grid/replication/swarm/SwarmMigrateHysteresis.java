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

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Composite-score hysteresis for placement migrate search.
 * <p>
 * Arms when {@code composite >= enterThreshold}; stays armed until
 * {@code composite < exitThreshold}. Prevents flap around {@code migrateThreshold}.
 *
 * @author: GenCloud
 * @date: 2026/06
 * @since: 1.0
 */
public final class SwarmMigrateHysteresis {
	public static final double DEFAULT_ENTER_DELTA = 0.25d;
	public static final double DEFAULT_EXIT_DELTA = 0.05d;

	private final double enterThreshold;
	private final double exitThreshold;
	private final AtomicBoolean migrateArmed = new AtomicBoolean(false);

	public SwarmMigrateHysteresis(double migrateThreshold, double enterDelta, double exitDelta) {
		final double enter = Math.max(0.0d, enterDelta);
		final double exit = Math.max(0.0d, exitDelta);
		this.enterThreshold = migrateThreshold + enter;
		this.exitThreshold = Math.max(0.0d, migrateThreshold - exit);
	}

	public double enterThreshold() {
		return enterThreshold;
	}

	public double exitThreshold() {
		return exitThreshold;
	}

	/**
	 * Update arm state from the latest composite and return whether migrate search/plans
	 * may emit migrations this tick.
	 */
	public boolean allowMigrateSearch(double composite) {
		if (migrateArmed.get()) {
			if (composite < exitThreshold) {
				migrateArmed.set(false);
				return false;
			}
			return true;
		}
		if (composite >= enterThreshold) {
			migrateArmed.set(true);
			return true;
		}
		return false;
	}

	/**
	 * Floor used by the optimizer idle-churn gate (must be less than or equal to enter).
	 */
	public double searchFloor() {
		return exitThreshold;
	}
}