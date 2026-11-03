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

import org.genfork.grid.replication.util.CatalogLagFenceUtil;
import org.genfork.grid.replication.util.CatalogLagFenceUtil.ProgressTracker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit invariants: transient catalog lag ≠ readiness DOWN; stuck lag does; AUTH stale ≠ AUTH_FAILED.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
class CatalogLagFenceUtilCharacterizationTest {

	@Test
	void transientLagMarksStaleButNotReadinessDown() {
		assertTrue(CatalogLagFenceUtil.markApplyLagStale(1L));
		assertFalse(CatalogLagFenceUtil.markApplyLagStale(0L));
		assertFalse(CatalogLagFenceUtil.readinessDownForStuckLag(
				1L, CatalogLagFenceUtil.CATALOG_LAG_STUCK_MS - 1L, CatalogLagFenceUtil.CATALOG_LAG_STUCK_MS));
	}

	@Test
	void stuckLagFlipsReadinessDown() {
		assertTrue(CatalogLagFenceUtil.readinessDownForStuckLag(
				2L, CatalogLagFenceUtil.CATALOG_LAG_STUCK_MS, CatalogLagFenceUtil.CATALOG_LAG_STUCK_MS));
		assertFalse(CatalogLagFenceUtil.readinessDownForStuckLag(0L, 60_000L, CatalogLagFenceUtil.CATALOG_LAG_STUCK_MS));
	}

	@Test
	void ddlDenyWhenLagOrPeerAhead() {
		assertTrue(CatalogLagFenceUtil.denyDdlMint(1L, 5L, 5L));
		assertTrue(CatalogLagFenceUtil.denyDdlMint(0L, 5L, 6L));
		assertFalse(CatalogLagFenceUtil.denyDdlMint(0L, 5L, 5L));
		assertFalse(CatalogLagFenceUtil.denyDdlMint(0L, 5L, 4L));
	}

	@Test
	void progressTrackerResetsStuckClockOnAdvance() {
		final ProgressTracker tracker = new ProgressTracker();
		tracker.noteApplied(1L);
		final long early = tracker.millisSinceProgress(System.currentTimeMillis());
		assertTrue(early < CatalogLagFenceUtil.CATALOG_LAG_STUCK_MS);
		tracker.noteApplied(2L);
		assertTrue(tracker.millisSinceProgress(System.currentTimeMillis()) < 1_000L);
	}
}
