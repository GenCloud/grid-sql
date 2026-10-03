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
package index.unit.query;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.replication.netty.NettyReplicationTransport;
import org.genfork.grid.replication.transport.ReplicationPeer;
import org.genfork.grid.replication.tx.TxEnvelopeCoordinator;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlSession;
import org.genfork.grid.sql.tx.DistForUpdatePeerLockLease;
import org.genfork.grid.sql.tx.DistForUpdatePrepareVotes;
import org.genfork.grid.sql.tx.LockWaitTimeoutException;
import org.genfork.grid.sql.tx.NettyDistForUpdatePeerLockAgent;
import org.genfork.grid.sql.tx.SqlRecordLockManager;
import org.genfork.grid.sql.tx.SqlTxBuffer;
import org.genfork.grid.threading.ThreadService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live Netty peer FOR UPDATE lock + prepare/commit-dec fail-closed path.
 *
 * @author: GenCloud
 * @date: 2026/01
 * @since: 1.0
 */
public class DistForUpdateNettyPeerLockIT {
	private static final String CLUSTER = "fu-netty-it";
	private static final String DC = "dc0";
	private static final String NODE_A = "n-a";
	private static final String NODE_B = "n-b";
	private static final String TABLE = "fu_netty";
	private static final byte[] KEY = new byte[]{3, 1, 4};
	private static final long CONNECT_TIMEOUT_MS = 3_000L;
	private static final int MAX_FRAME = 1 << 20;
	private static final long AWAIT_CHANNEL_MS = 5_000L;
	private static final long POLL_MS = 50L;
	private static final long SCHEMA_EPOCH = 1L;

	private NettyReplicationTransport transportA;
	private NettyReplicationTransport transportB;
	private SqlRecordLockManager locksA;
	private SqlRecordLockManager locksB;
	private SqlEngine engineA;
	private TxEnvelopeCoordinator envelope;

	@BeforeEach
	void setUp() throws Exception {
		ThreadService.ensureRunning();
		final int portA = freePort();
		final int portB = freePort();

		locksA = new SqlRecordLockManager();
		locksB = new SqlRecordLockManager();
		locksA.setLockWaitTimeoutMs(500L);
		locksB.setLockWaitTimeoutMs(500L);

		transportA = new NettyReplicationTransport(
				NODE_A, CLUSTER, DC, SCHEMA_EPOCH, "127.0.0.1", portA, CONNECT_TIMEOUT_MS, MAX_FRAME,
				List.of(new ReplicationPeer(NODE_B, "127.0.0.1", portB, DC)));
		transportB = new NettyReplicationTransport(
				NODE_B, CLUSTER, DC, SCHEMA_EPOCH, "127.0.0.1", portB, CONNECT_TIMEOUT_MS, MAX_FRAME,
				List.of(new ReplicationPeer(NODE_A, "127.0.0.1", portA, DC)));

		bindMinimal(transportA);
		bindMinimal(transportB);
		transportA.setForUpdateLockManager(locksA);
		transportB.setForUpdateLockManager(locksB);

		transportA.start();
		transportB.start();
		awaitPeerChannel(transportA, NODE_B);

		engineA = new SqlEngine(new TableCatalog(), null, 4);
		envelope = new TxEnvelopeCoordinator();
	}

	@AfterEach
	void tearDown() {
		if (transportA != null) {
			transportA.close();
		}
		if (transportB != null) {
			transportB.close();
		}
	}

	@Test
	void peerLockBlocksLocalAcquireOnPeer() throws Exception {
		final NettyDistForUpdatePeerLockAgent agent =
				new NettyDistForUpdatePeerLockAgent(transportA, NODE_B);
		assertTrue(agent.lock(42L, TABLE, KEY, false));
		assertFalse(locksB.tryLock(TABLE, KEY));
		agent.unlock(42L, TABLE, KEY);
		awaitUnlocked(locksB, TABLE, KEY);
	}

	@Test
	void prepareSucceedsWhenPeerHoldsKeyThenFinishCommit() {
		final SqlSession session = openTx();
		final SqlTxBuffer buf = session.requireTx();
		final NettyDistForUpdatePeerLockAgent agent =
				new NettyDistForUpdatePeerLockAgent(transportA, NODE_B);
		assertTrue(agent.lock(buf.txId(), TABLE, KEY, false));
		buf.rememberPeerLock(new DistForUpdatePeerLockLease(agent, buf.txId(), TABLE, KEY));

		assertDoesNotThrow(() -> DistForUpdatePrepareVotes.prepareOrThrow(session, buf, transportA));
		assertDoesNotThrow(() -> DistForUpdatePrepareVotes.finish(session, buf, transportA, true));
		agent.unlock(buf.txId(), TABLE, KEY);
	}

	@Test
	void prepareAbortReleasesPeerHeldKeys() throws Exception {
		final SqlSession session = openTx();
		final SqlTxBuffer buf = session.requireTx();
		final NettyDistForUpdatePeerLockAgent agent =
				new NettyDistForUpdatePeerLockAgent(transportA, NODE_B);
		assertTrue(agent.lock(buf.txId(), TABLE, KEY, false));
		buf.rememberPeerLock(new DistForUpdatePeerLockLease(agent, buf.txId(), TABLE, KEY));
		assertFalse(locksB.tryLock(TABLE, KEY));

		DistForUpdatePrepareVotes.finish(session, buf, transportA, false);
		awaitUnlocked(locksB, TABLE, KEY);
	}

	@Test
	void prepareNacksWhenPeerMissingRequestedKey() {
		final SqlSession session = openTx();
		final SqlTxBuffer buf = session.requireTx();
		final NettyDistForUpdatePeerLockAgent agent =
				new NettyDistForUpdatePeerLockAgent(transportA, NODE_B);
		// Remember lease for KEY without actually locking it on peer → prepare must NACK.
		buf.rememberPeerLock(new DistForUpdatePeerLockLease(agent, buf.txId(), TABLE, KEY));
		final IllegalStateException ex = assertThrows(
				IllegalStateException.class,
				() -> DistForUpdatePrepareVotes.prepareOrThrow(session, buf, transportA));
		assertTrue(ex.getMessage().contains("FOR UPDATE prepare"));
	}

	@Test
	void peerChannelDownFailsClosedOnLock() {
		transportB.close();
		transportB = null;
		final NettyDistForUpdatePeerLockAgent agent =
				new NettyDistForUpdatePeerLockAgent(transportA, NODE_B);
		assertThrows(LockWaitTimeoutException.class, () -> agent.lock(99L, TABLE, KEY, false));
	}

	private SqlSession openTx() {
		final SqlSession session = engineA.newSession();
		session.setEnvelopeCoordinator(envelope);
		engineA.execute(session, "BEGIN");
		return session;
	}

	private static void bindMinimal(NettyReplicationTransport transport) {
		transport.bindHandlers(
				() -> null,
				domain -> null,
				null,
				null,
				null,
				null,
				null,
				peer -> {
				},
				peerId -> {
				},
				() -> 0L,
				() -> (byte) 0);
	}

	private static void awaitPeerChannel(NettyReplicationTransport transport, String peerId)
			throws InterruptedException {
		final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(AWAIT_CHANNEL_MS);
		while (System.nanoTime() < deadline) {
			try {
				if (transport.sendForUpdateLockReq(peerId, 1L, true, TABLE, new byte[]{0})) {
					transport.sendForUpdateLockRelease(peerId, 1L, TABLE, new byte[]{0});
					return;
				}
				// skipLocked miss or not yet connected — retry
			} catch (RuntimeException ignored) {
				// channel race
			}
			Thread.sleep(POLL_MS);
		}
		throw new IllegalStateException("peer channel not ready peerId=" + peerId);
	}

	private static void awaitUnlocked(SqlRecordLockManager locks, String table, byte[] key)
			throws InterruptedException {
		final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(AWAIT_CHANNEL_MS);
		while (System.nanoTime() < deadline) {
			if (locks.tryLock(table, key)) {
				locks.unlock(table, key);
				return;
			}
			Thread.sleep(POLL_MS);
		}
		throw new IllegalStateException("peer lock not released");
	}

	private static int freePort() throws Exception {
		try (ServerSocket socket = new ServerSocket()) {
			socket.bind(new InetSocketAddress("127.0.0.1", 0));
			return socket.getLocalPort();
		}
	}
}
