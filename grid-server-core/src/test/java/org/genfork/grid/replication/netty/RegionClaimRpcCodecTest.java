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
package org.genfork.grid.replication.netty;

import org.genfork.grid.replication.netty.ReplicationRpcCodec.RegionClaimAck;
import org.genfork.grid.replication.netty.ReplicationRpcCodec.RegionClaimReq;
import org.genfork.grid.replication.region.RegionRole;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * REGION_CLAIM_REQ / ACK codec round-trip.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
class RegionClaimRpcCodecTest {

	private static final long CLAIM_ID = 42L;
	private static final long PROPOSED_EPOCH = 9L;
	private static final String FROM_NODE = "hold-1";
	private static final String FROM_DC = "dc-b";

	@Test
	void claimReqRoundTrip() {
		final byte[] wire = ReplicationRpcCodec.encodeRegionClaimReq(
				CLAIM_ID, PROPOSED_EPOCH, FROM_NODE, FROM_DC, RegionRole.HOLD.wireCode());
		final RegionClaimReq decoded = ReplicationRpcCodec.decodeRegionClaimReq(wire);
		assertEquals(CLAIM_ID, decoded.claimId());
		assertEquals(PROPOSED_EPOCH, decoded.proposedEpoch());
		assertEquals(FROM_NODE, decoded.fromNodeId());
		assertEquals(FROM_DC, decoded.fromDc());
		assertEquals(RegionRole.HOLD.wireCode(), decoded.claimantRoleWire());
	}

	@Test
	void claimAckRoundTrip() {
		final byte[] wire = ReplicationRpcCodec.encodeRegionClaimAck(
				CLAIM_ID, FROM_NODE, RegionRole.WITNESS.wireCode());
		final RegionClaimAck decoded = ReplicationRpcCodec.decodeRegionClaimAck(wire);
		assertEquals(CLAIM_ID, decoded.claimId());
		assertEquals(FROM_NODE, decoded.fromNodeId());
		assertEquals(RegionRole.WITNESS.wireCode(), decoded.voterRoleWire());
	}
}