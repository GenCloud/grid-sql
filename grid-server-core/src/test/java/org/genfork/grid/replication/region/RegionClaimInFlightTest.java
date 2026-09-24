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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In-flight claim ACK collection (self + peer voters).
 *
 * @author: GenCloud
 * @date: 2026/06
 * @since: 1.0
 */
class RegionClaimInFlightTest {

	private static final String CLAIMANT = "hold-a";
	private static final String VOTER_B = "hold-b";
	private static final String VOTER_W = "witness-1";
	private static final long PROPOSED_EPOCH = 7L;
	private static final int VOTERS_THREE = 3;

	@Test
	void selfAckAloneMeetsQuorumWhenVotersOne() {
		final RegionClaimInFlight inflight = RegionClaimInFlight.start(CLAIMANT, PROPOSED_EPOCH, 1);
		assertEquals(1, inflight.ackCount());
		assertTrue(inflight.hasQuorum());
	}

	@Test
	void threeVotersNeedTwoAcks() {
		final RegionClaimInFlight inflight = RegionClaimInFlight.start(CLAIMANT, PROPOSED_EPOCH, VOTERS_THREE);
		assertFalse(inflight.hasQuorum());
		assertTrue(inflight.recordAck(VOTER_B));
		assertTrue(inflight.hasQuorum());
		assertFalse(inflight.recordAck(VOTER_B));
		assertTrue(inflight.recordAck(VOTER_W));
		assertEquals(3, inflight.ackCount());
	}
}