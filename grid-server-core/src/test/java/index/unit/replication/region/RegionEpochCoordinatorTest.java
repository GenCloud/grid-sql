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
package index.unit.replication.region;

import java.nio.file.Files;
import java.nio.file.Path;

import org.genfork.grid.replication.region.RegionClaimQuorum;
import org.genfork.grid.replication.region.RegionEpochOps;
import org.genfork.grid.replication.region.RegionEpochStore;
import org.genfork.grid.replication.region.RegionLeaseState;
import org.genfork.grid.replication.region.RegionRole;
import org.genfork.grid.replication.region.RegionRoleCoordinator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Region epoch fencing, persist, and claim quorum.
 *
 * @author: GenCloud
 * @date: 2026/06
 * @since: 1.0
 */
public class RegionEpochCoordinatorTest {

	@Test
	void epochOpsFenceAndClaim() {
		assertTrue(RegionEpochOps.mustFenceOnHigherEpoch(1L, 2L));
		assertFalse(RegionEpochOps.mustFenceOnHigherEpoch(2L, 2L));
		assertEquals(3L, RegionEpochOps.nextClaimEpoch(2L));
		final RegionLeaseState active = RegionLeaseState.bootstrap(RegionRole.ACTIVE, 2L, "a1", "dc-a");
		assertTrue(RegionEpochOps.regionAllowsWrites(active, 2L));
		assertFalse(RegionEpochOps.regionAllowsWrites(active, 3L));
		assertFalse(RegionEpochOps.regionAllowsWrites(active.fenceToHold(), 2L));
	}

	@Test
	void claimQuorumMajority() {
		assertEquals(1, RegionClaimQuorum.requiredAcks(1));
		assertEquals(2, RegionClaimQuorum.requiredAcks(2));
		assertEquals(2, RegionClaimQuorum.requiredAcks(3));
		assertTrue(RegionClaimQuorum.hasQuorum(2, 3));
		assertFalse(RegionClaimQuorum.hasQuorum(1, 3));
	}

	@Test
	void storeRoundTrip(@TempDir Path dir) throws Exception {
		final RegionLeaseState boot = RegionLeaseState.bootstrap(RegionRole.HOLD, 4L, "b1", "dc-b");
		try (RegionEpochStore store = new RegionEpochStore(dir.resolve("region"), false)) {
			store.store(boot);
		}
		try (RegionEpochStore store = new RegionEpochStore(dir.resolve("region"), false)) {
			final RegionLeaseState loaded = store.loadOrDefault(
					RegionLeaseState.bootstrap(RegionRole.ACTIVE, 1L, "x", "dc-a"));
			assertEquals(RegionRole.HOLD, loaded.role());
			assertEquals(4L, loaded.epoch());
			assertEquals("b1", loaded.claimedByNodeId());
		}
	}

	@Test
	void coordinatorFenceAndClaim(@TempDir Path dataDir) throws Exception {
		try (RegionRoleCoordinator coord = new RegionRoleCoordinator(
				dataDir, false, true, RegionRole.ACTIVE, 1L, "a1", "dc-a", 1L, 2)) {
			assertFalse(coord.regionAllowsWrites(), "Active cold start unconfirmed");
			assertFalse(coord.isEpochConfirmed());
			coord.confirmEpoch();
			assertTrue(coord.regionAllowsWrites());
			assertTrue(coord.observePeerEpoch(2L, "b1"));
			assertEquals(RegionRole.HOLD, coord.regionRole());
			assertFalse(coord.regionAllowsWrites());
			assertFalse(coord.tryCompleteClaim(1));
			assertTrue(coord.tryCompleteClaim(2));
			assertEquals(RegionRole.ACTIVE, coord.regionRole());
			assertEquals(3L, coord.regionEpoch());
			assertTrue(coord.regionAllowsWrites());
		}
	}

	@Test
	void reviveConfirmsOrFencesOnRemoteHello(@TempDir Path dataDir) throws Exception {
		try (RegionRoleCoordinator revived = new RegionRoleCoordinator(
				dataDir.resolve("a"), false, true, RegionRole.ACTIVE, 1L, "a1", "dc-a", 5_000L, 2)) {
			assertFalse(revived.regionAllowsWrites());
			assertFalse(revived.noteRemotePeerRegion(1L, RegionRole.HOLD.wireCode()));
			assertTrue(revived.isEpochConfirmed());
			assertTrue(revived.regionAllowsWrites());
		}
		try (RegionRoleCoordinator behind = new RegionRoleCoordinator(
				dataDir.resolve("a2"), false, true, RegionRole.ACTIVE, 1L, "a1", "dc-a", 5_000L, 2)) {
			assertTrue(behind.noteRemotePeerRegion(2L, RegionRole.ACTIVE.wireCode()));
			assertEquals(RegionRole.HOLD, behind.regionRole());
			assertFalse(behind.regionAllowsWrites());
		}
		try (RegionRoleCoordinator dual = new RegionRoleCoordinator(
				dataDir.resolve("a3"), false, true, RegionRole.ACTIVE, 2L, "a1", "dc-a", 5_000L, 2)) {
			dual.confirmEpoch();
			assertTrue(dual.noteRemotePeerRegion(2L, RegionRole.ACTIVE.wireCode()));
			assertEquals(RegionRole.HOLD, dual.regionRole());
		}
	}

	@Test
	void epochOpsDualActiveAndConfirm() {
		assertTrue(RegionEpochOps.mustFenceDualActive(
				RegionRole.ACTIVE, 2L, RegionRole.ACTIVE.wireCode(), 2L, true));
		assertFalse(RegionEpochOps.mustFenceDualActive(
				RegionRole.ACTIVE, 2L, RegionRole.HOLD.wireCode(), 2L, true));
		assertFalse(RegionEpochOps.mustFenceDualActive(
				RegionRole.ACTIVE, 2L, RegionRole.ACTIVE.wireCode(), 2L, false));
		assertTrue(RegionEpochOps.canConfirmEpochAfterRemotePeer(2L, 2L));
		assertTrue(RegionEpochOps.canConfirmEpochAfterRemotePeer(2L, 1L));
		assertFalse(RegionEpochOps.canConfirmEpochAfterRemotePeer(2L, 3L));
	}

	@Test
	void disabledCoordinatorAlwaysAllows(@TempDir Path dataDir) throws Exception {
		try (RegionRoleCoordinator coord = new RegionRoleCoordinator(
				dataDir, false, false, RegionRole.HOLD, 1L, "a1", "dc-a", 5_000L, 2)) {
			assertTrue(coord.regionAllowsWrites());
			assertFalse(coord.observePeerEpoch(9L, "x"));
		}
	}
}