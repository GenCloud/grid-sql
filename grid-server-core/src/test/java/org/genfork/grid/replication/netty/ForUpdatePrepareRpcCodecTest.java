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

import org.genfork.grid.replication.netty.ReplicationRpcCodec.ForUpdateCommitDec;
import org.genfork.grid.replication.netty.ReplicationRpcCodec.ForUpdatePrepareAck;
import org.genfork.grid.replication.netty.ReplicationRpcCodec.ForUpdatePrepareReq;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FOR_UPDATE_PREPARE_* / COMMIT_DEC wire codec round-trip (E2 scaffold).
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
class ForUpdatePrepareRpcCodecTest {
	private static final long TX_ID = 99L;
	private static final String NODE = "coord-1";

	@Test
	void prepareReqRoundTrip() {
		final byte[] wire = ReplicationRpcCodec.encodeForUpdatePrepareReq(TX_ID, NODE);
		final ForUpdatePrepareReq decoded = ReplicationRpcCodec.decodeForUpdatePrepareReq(wire);
		assertEquals(TX_ID, decoded.txId());
		assertEquals(NODE, decoded.fromNodeId());
	}

	@Test
	void prepareAckRoundTrip() {
		final byte[] wire = ReplicationRpcCodec.encodeForUpdatePrepareAck(TX_ID, true, NODE);
		final ForUpdatePrepareAck decoded = ReplicationRpcCodec.decodeForUpdatePrepareAck(wire);
		assertEquals(TX_ID, decoded.txId());
		assertTrue(decoded.prepared());
		assertEquals(NODE, decoded.fromNodeId());
	}

	@Test
	void commitDecRoundTrip() {
		final byte[] wire = ReplicationRpcCodec.encodeForUpdateCommitDec(TX_ID, false, NODE);
		final ForUpdateCommitDec decoded = ReplicationRpcCodec.decodeForUpdateCommitDec(wire);
		assertEquals(TX_ID, decoded.txId());
		assertFalse(decoded.commit());
		assertEquals(NODE, decoded.fromNodeId());
	}
}
