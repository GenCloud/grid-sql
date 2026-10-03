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

import org.genfork.grid.mem.stage.WorkingSetBudget;
import org.genfork.grid.replication.metrics.DurabilityMetrics;
import org.genfork.grid.replication.snapshot.sealed.SealedMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke: durability / sealed ops counters move on WS drain and sealed metrics atoms.
 *
 * @author: GenCloud
 * @date: 2026/05
 * @since: 1.0
 */
public class DurabilityMetricsTest {
	private static final int INITIAL_CAP = 64;
	private static final int TIGHT_CAP = 8;
	private static final int FILL = 40;

	@BeforeEach
	void reset() {
		DurabilityMetrics.reset();
		SealedMetrics.reset();
	}

	@Test
	void workingSetEvictionsIncrement() {
		final WorkingSetBudget budget = new WorkingSetBudget(INITIAL_CAP);
		budget.setEvictHandler((shard, key) -> {
		});
		for (int i = 0; i < FILL; i++) {
			budget.touch(0, new byte[]{(byte) (i & 0xFF), (byte) (i >> 8)});
		}
		budget.setMaxEntries(TIGHT_CAP);
		assertTrue(DurabilityMetrics.wsEvictions() > 0L);
		assertEquals(TIGHT_CAP, DurabilityMetrics.wsMaxEntries());
	}

	@Test
	void sealedFailCountersReadable() {
		SealedMetrics.SEAL_FAIL_SIZE.incrementAndGet();
		SealedMetrics.SEALED_INDEX_PAGE_FAULT.incrementAndGet();
		assertEquals(1L, SealedMetrics.SEAL_FAIL_SIZE.get());
		assertEquals(1L, SealedMetrics.SEALED_INDEX_PAGE_FAULT.get());
	}
}