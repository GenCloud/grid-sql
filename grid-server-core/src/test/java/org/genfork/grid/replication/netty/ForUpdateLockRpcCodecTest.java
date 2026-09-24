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

import org.genfork.grid.replication.netty.ReplicationRpcCodec.ForUpdateLockAck;
import org.genfork.grid.replication.netty.ReplicationRpcCodec.ForUpdateLockRelease;
import org.genfork.grid.replication.netty.ReplicationRpcCodec.ForUpdateLockReq;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FOR_UPDATE_LOCK_* wire codec round-trip.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
class ForUpdateLockRpcCodecTest {
	private static final long TX_ID = 42L;
	private static final String TABLE = "fu_peer";
	private static final byte[] KEY = new byte[]{1, 2, 3};
	private static final String NODE = "peer-1";

	@Test
	void lockReqRoundTrip() {
		final byte[] wire = ReplicationRpcCodec.encodeForUpdateLockReq(TX_ID, true, TABLE, KEY);
		final ForUpdateLockReq decoded = ReplicationRpcCodec.decodeForUpdateLockReq(wire);
		assertEquals(TX_ID, decoded.txId());
		assertTrue(decoded.skipLocked());
		assertEquals(TABLE, decoded.table());
		assertArrayEquals(KEY, decoded.key());
	}

	@Test
	void lockAckRoundTrip() {
		final byte[] wire = ReplicationRpcCodec.encodeForUpdateLockAck(TX_ID, true, NODE);
		final ForUpdateLockAck decoded = ReplicationRpcCodec.decodeForUpdateLockAck(wire);
		assertEquals(TX_ID, decoded.txId());
		assertTrue(decoded.granted());
		assertEquals(NODE, decoded.fromNodeId());
	}

	@Test
	void lockReleaseRoundTrip() {
		final byte[] wire = ReplicationRpcCodec.encodeForUpdateLockRelease(TX_ID, TABLE, KEY);
		final ForUpdateLockRelease decoded = ReplicationRpcCodec.decodeForUpdateLockRelease(wire);
		assertEquals(TX_ID, decoded.txId());
		assertEquals(TABLE, decoded.table());
		assertArrayEquals(KEY, decoded.key());
	}
}