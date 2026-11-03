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

import org.genfork.grid.replication.util.CatalogLagFenceUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C3: catalog lag fence — AUTH via stale (not AUTH_FAILED), stuck readiness only, DDL deny.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.0
 */
class CatalogLagFenceChaosIT {

	@Test
	void transientLagMarksStaleNotReadinessDown() {
		assertTrue(CatalogLagFenceUtil.markApplyLagStale(1L));
		assertFalse(CatalogLagFenceUtil.readinessDownForStuckLag(
				1L, 100L, CatalogLagFenceUtil.CATALOG_LAG_STUCK_MS));
	}

	@Test
	void stuckLagFlipsReadinessAndDdlDenyPeerAhead() {
		assertTrue(CatalogLagFenceUtil.readinessDownForStuckLag(
				1L, CatalogLagFenceUtil.CATALOG_LAG_STUCK_MS, CatalogLagFenceUtil.CATALOG_LAG_STUCK_MS));
		assertTrue(CatalogLagFenceUtil.denyDdlMint(0L, 3L, 4L));
		assertFalse(CatalogLagFenceUtil.denyDdlMint(0L, 4L, 4L));
	}
}
