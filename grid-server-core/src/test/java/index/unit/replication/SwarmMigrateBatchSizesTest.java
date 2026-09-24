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
package index.unit.replication;

import org.genfork.grid.replication.swarm.SwarmMigrateBatchSizes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Lag-aware migrate batch sizing.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
class SwarmMigrateBatchSizesTest {

	@Test
	void zeroLagUsesMaxBatch() {
		assertEquals(SwarmMigrateBatchSizes.MAX_BATCH, SwarmMigrateBatchSizes.forApplyLag(0L));
	}

	@Test
	void highLagUsesMinBatch() {
		assertEquals(SwarmMigrateBatchSizes.MIN_BATCH, SwarmMigrateBatchSizes.forApplyLag(20_000L));
	}

	@Test
	void midLagInterpolates() {
		final int batch = SwarmMigrateBatchSizes.forApplyLag(5_000L);
		if (batch < SwarmMigrateBatchSizes.MIN_BATCH || batch > SwarmMigrateBatchSizes.MAX_BATCH) {
			throw new AssertionError("expected between min and max, got " + batch);
		}
	}
}