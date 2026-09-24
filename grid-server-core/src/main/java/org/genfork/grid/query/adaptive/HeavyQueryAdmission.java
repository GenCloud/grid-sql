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
package org.genfork.grid.query.adaptive;

import com.google.common.annotations.VisibleForTesting;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Process-wide admission gate for concurrent heavy adaptive parallel scans.
 * <p>
 * Uses CAS on {@link AtomicInteger} only — no monitor wait on VT / Reactor / Netty.
 * When the concurrent quota is saturated, callers must fall back to serial scan
 * (fail-closed parallel path).
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
public final class HeavyQueryAdmission {
	private static final int DEFAULT_MAX_CONCURRENT_HEAVY = 4;
	private static final int DEFAULT_MAX_WORKERS_PER_HEAVY = 4;
	private static final int ACQUIRE_UNIT = 1;
	private static final int RELEASE_FLOOR = 0;

	private static final HeavyQueryAdmission GLOBAL = new HeavyQueryAdmission(
			DEFAULT_MAX_CONCURRENT_HEAVY,
			DEFAULT_MAX_WORKERS_PER_HEAVY
	);

	private final int maxConcurrentHeavy;
	private final int maxWorkersPerHeavy;
	private final AtomicInteger inflightHeavy = new AtomicInteger(RELEASE_FLOOR);

	/**
	 * @param maxConcurrentHeavy  max simultaneous heavy parallel scans ({@code < 1} → 1)
	 * @param maxWorkersPerHeavy  worker cap per admitted heavy scan ({@code < 1} → 1)
	 */
	public HeavyQueryAdmission(int maxConcurrentHeavy, int maxWorkersPerHeavy) {
		this.maxConcurrentHeavy = Math.max(ACQUIRE_UNIT, maxConcurrentHeavy);
		this.maxWorkersPerHeavy = Math.max(ACQUIRE_UNIT, maxWorkersPerHeavy);
	}

	/** Shared process admission (default quotas). */
	public static HeavyQueryAdmission global() {
		return GLOBAL;
	}

	public int maxConcurrentHeavy() {
		return maxConcurrentHeavy;
	}

	public int maxWorkersPerHeavy() {
		return maxWorkersPerHeavy;
	}

	public int inflightHeavy() {
		return inflightHeavy.get();
	}

	/**
	 * Try to admit one heavy parallel scan.
	 *
	 * @return {@code true} if admitted; {@code false} when saturated (caller falls back to serial)
	 */
	public boolean tryAcquire() {
		while (true) {
			final int current = inflightHeavy.get();
			if (current >= maxConcurrentHeavy) {
				return false;
			}
			if (inflightHeavy.compareAndSet(current, current + ACQUIRE_UNIT)) {
				return true;
			}
		}
	}

	/** Release one previously acquired heavy slot (idempotent at floor 0). */
	public void release() {
		while (true) {
			final int current = inflightHeavy.get();
			if (current <= RELEASE_FLOOR) {
				return;
			}
			if (inflightHeavy.compareAndSet(current, current - ACQUIRE_UNIT)) {
				return;
			}
		}
	}

	/** Test helper: reset inflight to zero without changing quotas. */
	@VisibleForTesting
	public void resetInflightForTests() {
		inflightHeavy.set(RELEASE_FLOOR);
	}
}