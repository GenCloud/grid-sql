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

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.genfork.grid.replication.netty.ReplicationRpcCodec.ForUpdateLockAck;
import org.genfork.grid.sql.tx.SqlRecordLockManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FOR_UPDATE_LOCK_* transport handler path without a live Netty peer channel.
 * <p>
 * Full two-node Netty IT remains optional; in-process peer locks stay in
 * {@code DistForUpdatePeerLockIT}. This test proves local lock manager + ACK correlator.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
class ForUpdateLockTransportHandlerTest {
	private static final long TX_ID = 7L;
	private static final String TABLE = "fu_netty";
	private static final byte[] KEY = new byte[]{9, 8, 7};
	private static final String PEER_ID = "peer-a";
	private static final long JOIN_MS = 2_000L;

	private NettyReplicationTransport transport;
	private SqlRecordLockManager locks;

	@BeforeEach
	void setUp() {
		locks = new SqlRecordLockManager();
		transport = new NettyReplicationTransport(
				"local-node",
				"cluster",
				"dc0",
				1L,
				"127.0.0.1",
				0,
				1_000L,
				1 << 20,
				List.of());
		transport.setForUpdateLockManager(locks);
	}

	@AfterEach
	void tearDown() {
		transport.close();
	}

	@Test
	void handleReqGrantsAndReleaseUnlocks() {
		final byte[] req = ReplicationRpcCodec.encodeForUpdateLockReq(TX_ID, false, TABLE, KEY);
		final byte[] ackBody = transport.handleForUpdateLockReq(req);
		final ForUpdateLockAck ack = ReplicationRpcCodec.decodeForUpdateLockAck(ackBody);
		assertTrue(ack.granted());
		assertFalse(locks.tryLock(TABLE, KEY), "peer manager must hold after GRANT");

		transport.handleForUpdateLockRelease(
				ReplicationRpcCodec.encodeForUpdateLockRelease(TX_ID, TABLE, KEY));
		assertTrue(locks.tryLock(TABLE, KEY), "peer manager free after RELEASE");
		locks.unlock(TABLE, KEY);
	}

	@Test
	void handleReqSkipLockedNacksWhenHeld() {
		locks.lock(TABLE, KEY);
		try {
			final byte[] req = ReplicationRpcCodec.encodeForUpdateLockReq(TX_ID, true, TABLE, KEY);
			final byte[] ackBody = transport.handleForUpdateLockReq(req);
			final ForUpdateLockAck ack = ReplicationRpcCodec.decodeForUpdateLockAck(ackBody);
			assertFalse(ack.granted());
		} finally {
			locks.unlock(TABLE, KEY);
		}
	}

	@Test
	void handleAckCompletesPendingWaiter() throws Exception {
		final CompletableFuture<Boolean> waiter = transport.offerForUpdateLockWaiter(PEER_ID, TX_ID);
		transport.handleForUpdateLockAck(
				ReplicationRpcCodec.encodeForUpdateLockAck(TX_ID, true, PEER_ID));
		assertTrue(waiter.get(JOIN_MS, TimeUnit.MILLISECONDS));
	}

	@Test
	void inactivePeerSkipLockedReturnsFalse() {
		assertFalse(transport.sendForUpdateLockReq(PEER_ID, TX_ID, true, TABLE, KEY));
	}
}
