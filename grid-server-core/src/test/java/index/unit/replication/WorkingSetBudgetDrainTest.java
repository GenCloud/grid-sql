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
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CLOCK drain must complete a second pass after clearing refs (batch hysteresis path).
 *
 * @author: GenCloud
 * @date: 2026/02
 * @since: 1.0
 */
class WorkingSetBudgetDrainTest {
	private static final int CAP = 64;
	private static final int OVERSHOOT = 5_000;

	@Test
	void drainOverflowEvictsAfterClockSecondChance() {
		final WorkingSetBudget budget = new WorkingSetBudget(CAP);
		final AtomicInteger evicted = new AtomicInteger();
		budget.setEvictHandler((shard, key) -> evicted.incrementAndGet());

		for (int i = 0; i < CAP + OVERSHOOT; i++) {
			budget.touch(0, ("k-" + i).getBytes(StandardCharsets.UTF_8));
		}

		assertTrue(budget.size() <= CAP + 4_096,
				"size must settle near cap+hysteresis, was " + budget.size());
		assertTrue(evicted.get() > 0, "CLOCK second pass must produce victims");
	}
}