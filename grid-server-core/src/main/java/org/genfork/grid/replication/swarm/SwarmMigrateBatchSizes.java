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

/**
 * Lag-aware OpLog migrate batch sizing for {@code ShardMigrator.migrateRange}.
 *
 * @author: GenCloud
 * @date: 2026/06
 * @since: 1.0
 */
public final class SwarmMigrateBatchSizes {
	public static final int MIN_BATCH = 64;
	public static final int MAX_BATCH = 1024;
	private static final long HIGH_LAG_OPS = 10_000L;

	private SwarmMigrateBatchSizes() {
	}

	/**
	 * @param applyLag max apply lag (ops) across streams; higher lag yields smaller batches
	 */
	public static int forApplyLag(long applyLag) {
		if (applyLag <= 0L) {
			return MAX_BATCH;
		}
		if (applyLag >= HIGH_LAG_OPS) {
			return MIN_BATCH;
		}
		final double ratio = applyLag / (double) HIGH_LAG_OPS;
		return (int) Math.round(MAX_BATCH - ratio * (MAX_BATCH - MIN_BATCH));
	}
}