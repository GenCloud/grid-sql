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

import org.genfork.grid.replication.util.ApplyNackHealUtil;
import org.genfork.grid.replication.util.ApplyNackHealUtil.CoalesceState;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * APPLY_NACK heal coalesce / suppress — no heal-every-NACK storm.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
class ApplyNackHealUtilCharacterizationTest {

	@Test
	void coalesceSuppressesSecondHealInsideInterval() {
		final CoalesceState state = new CoalesceState();
		final long t0 = 1_000L;
		assertTrue(ApplyNackHealUtil.shouldScheduleHeal(
				state, "d", 0, 10L, t0, ApplyNackHealUtil.APPLY_NACK_HEAL_INTERVAL_MS, false, false));
		ApplyNackHealUtil.markScheduled(state, "d", 0, 10L, t0, false);
		assertFalse(ApplyNackHealUtil.shouldScheduleHeal(
				state, "d", 0, 11L, t0 + 100L, ApplyNackHealUtil.APPLY_NACK_HEAL_INTERVAL_MS, false, false));
		assertTrue(ApplyNackHealUtil.shouldScheduleHeal(
				state, "d", 0, 11L, t0 + ApplyNackHealUtil.APPLY_NACK_HEAL_INTERVAL_MS,
				ApplyNackHealUtil.APPLY_NACK_HEAL_INTERVAL_MS, false, false));
	}

	@Test
	void suppressWhenPeerAckCaughtUpOrAlreadyHealed() {
		final CoalesceState state = new CoalesceState();
		assertFalse(ApplyNackHealUtil.shouldScheduleHeal(
				state, "d", 0, 5L, 1L, ApplyNackHealUtil.APPLY_NACK_HEAL_INTERVAL_MS, true, false));
		ApplyNackHealUtil.markScheduled(state, "d", 0, 20L, 1L, false);
		assertFalse(ApplyNackHealUtil.shouldScheduleHeal(
				state, "d", 0, 15L, 10_000L, ApplyNackHealUtil.APPLY_NACK_HEAL_INTERVAL_MS, false, false));
	}

	@Test
	void packCapBlocksExtraPackHeals() {
		final CoalesceState state = new CoalesceState();
		for (int i = 0; i < ApplyNackHealUtil.MAX_CONCURRENT_PACKS; i++) {
			assertTrue(ApplyNackHealUtil.shouldScheduleHeal(
					state, "d" + i, i, 1L, 1L, ApplyNackHealUtil.APPLY_NACK_HEAL_INTERVAL_MS, false, true));
			ApplyNackHealUtil.markScheduled(state, "d" + i, i, 1L, 1L, true);
		}
		assertFalse(ApplyNackHealUtil.shouldScheduleHeal(
				state, "extra", 9, 1L, 1L, ApplyNackHealUtil.APPLY_NACK_HEAL_INTERVAL_MS, false, true));
		ApplyNackHealUtil.releasePackSlot(state);
		assertTrue(ApplyNackHealUtil.shouldScheduleHeal(
				state, "extra", 9, 1L, 10_000L, ApplyNackHealUtil.APPLY_NACK_HEAL_INTERVAL_MS, false, true));
	}

	@Test
	void tryScheduleRunsOnceAndSuppressesCallback() {
		final CoalesceState state = new CoalesceState();
		final AtomicInteger ran = new AtomicInteger();
		final AtomicInteger suppressed = new AtomicInteger();
		ApplyNackHealUtil.trySchedule(
				state, "d", 0, 1L, 1L, false, false,
				Runnable::run, ran::incrementAndGet, suppressed::incrementAndGet);
		ApplyNackHealUtil.trySchedule(
				state, "d", 0, 2L, 2L, false, false,
				Runnable::run, ran::incrementAndGet, suppressed::incrementAndGet);
		assertEquals(1, ran.get());
		assertEquals(1, suppressed.get());
	}
}
