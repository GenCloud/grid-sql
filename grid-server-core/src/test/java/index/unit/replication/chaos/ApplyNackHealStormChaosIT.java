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
package index.unit.replication.chaos;

import org.genfork.grid.replication.metrics.ReplicationMetrics;
import org.genfork.grid.replication.util.ApplyNackHealUtil;
import org.genfork.grid.replication.util.ApplyNackHealUtil.CoalesceState;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C4: APPLY_NACK storm must coalesce — no heal-every-NACK feedback loop.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
class ApplyNackHealStormChaosIT {

	@Test
	void nackStormSchedulesOneHealAndSuppressesRest() {
		final CoalesceState state = new CoalesceState();
		final AtomicInteger heals = new AtomicInteger();
		final AtomicInteger suppressed = new AtomicInteger();
		final long t0 = 10_000L;
		for (int i = 0; i < 50; i++) {
			ApplyNackHealUtil.trySchedule(
					state,
					"storm",
					0,
					100L + i,
					t0 + i,
					false,
					false,
					Runnable::run,
					() -> {
						heals.incrementAndGet();
						ReplicationMetrics.recordApplyNackHealScheduled();
					},
					() -> {
						suppressed.incrementAndGet();
						ReplicationMetrics.recordApplyNackHealSuppressed();
					});
		}
		assertEquals(1, heals.get());
		assertEquals(49, suppressed.get());
		assertTrue(ReplicationMetrics.applyNackHealSuppressed() >= 49L);
	}
}
