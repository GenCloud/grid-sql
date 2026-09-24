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
package org.genfork.grid.replication.region;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Region claim uses Netty Active peer-link reachability + silence ([TD-HA-001]).
 *
 * @author: GenCloud
 * @date: 2026/06
 * @since: 1.0
 */
class RegionPeerLinkClaimTest {

	private static final long CLAIM_TIMEOUT_MS = 100L;
	private static final int QUORUM_VOTERS = 1;

	@TempDir
	Path tempDir;

	@Test
	void claimBlockedWhilePeerLinkReachable() throws Exception {
		try (RegionRoleCoordinator coord = newHoldCoordinator()) {
			assertFalse(coord.shouldAttemptClaim(true));
		}
	}

	@Test
	void claimAllowedAfterPeerLinkDownPastTimeout() throws Exception {
		try (RegionRoleCoordinator coord = newHoldCoordinator()) {
			coord.forcePeerLinkSilenceMs(CLAIM_TIMEOUT_MS + 50L);
			assertTrue(coord.shouldAttemptClaim(false));
			assertFalse(coord.shouldAttemptClaim(true));
		}
	}

	@Test
	void claimBlockedAgainAfterBriefPeerLinkFlap() throws Exception {
		try (RegionRoleCoordinator coord = newHoldCoordinator()) {
			coord.forcePeerLinkSilenceMs(CLAIM_TIMEOUT_MS + 50L);
			assertTrue(coord.shouldAttemptClaim(false));
			// Brief recover resets silence clock — claim must wait full timeout again.
			assertFalse(coord.shouldAttemptClaim(true));
			assertFalse(coord.shouldAttemptClaim(false));
			coord.forcePeerLinkSilenceMs(CLAIM_TIMEOUT_MS + 50L);
			assertTrue(coord.shouldAttemptClaim(false));
		}
	}

	private RegionRoleCoordinator newHoldCoordinator() throws Exception {
		return new RegionRoleCoordinator(
				tempDir,
				false,
				true,
				RegionRole.HOLD,
				1L,
				"hold-1",
				"dc-b",
				CLAIM_TIMEOUT_MS,
				QUORUM_VOTERS);
	}
}