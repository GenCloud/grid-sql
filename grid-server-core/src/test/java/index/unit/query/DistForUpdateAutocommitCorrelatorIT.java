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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.genfork.grid.replication.netty.NettyReplicationTransport;
import org.genfork.grid.replication.transport.ReplicationPeer;
import org.genfork.grid.sql.tx.DistForUpdateCorrelatorUtil;
import org.genfork.grid.sql.tx.NettyDistForUpdatePeerLockAgent;
import org.genfork.grid.sql.tx.SqlRecordLockManager;
import org.genfork.grid.threading.ThreadService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrent autocommit Dist FOR UPDATE must not share correlator {@code 0}
 * ({@code peerId@txId} waiter collision).
 *
 * @author: GenCloud
 * @date: 2026/01
 * @since: 1.0
 */
public class DistForUpdateAutocommitCorrelatorIT {
	private static final String CLUSTER = "fu-auto-corr";
	private static final String DC = "dc0";
	private static final String NODE_A = "n-a";
	private static final String NODE_B = "n-b";
	private static final String TABLE = "fu_auto";
	private static final byte[] KEY_1 = new byte[]{1};
	private static final byte[] KEY_2 = new byte[]{2};
	private static final long CONNECT_TIMEOUT_MS = 3_000L;
	private static final int MAX_FRAME = 1 << 20;
	private static final long AWAIT_CHANNEL_MS = 5_000L;
	private static final long POLL_MS = 50L;
	private static final long JOIN_MS = 10_000L;

	private NettyReplicationTransport transportA;
	private NettyReplicationTransport transportB;

	@BeforeEach
	void setUp() throws Exception {
		ThreadService.ensureRunning();
		final int portA = freePort();
		final int portB = freePort();
		final SqlRecordLockManager locksA = new SqlRecordLockManager();
		final SqlRecordLockManager locksB = new SqlRecordLockManager();
		locksA.setLockWaitTimeoutMs(2_000L);
		locksB.setLockWaitTimeoutMs(2_000L);

		transportA = new NettyReplicationTransport(
				NODE_A, CLUSTER, DC, 1L, "127.0.0.1", portA, CONNECT_TIMEOUT_MS, MAX_FRAME,
				List.of(new ReplicationPeer(NODE_B, "127.0.0.1", portB, DC)));
		transportB = new NettyReplicationTransport(
				NODE_B, CLUSTER, DC, 1L, "127.0.0.1", portB, CONNECT_TIMEOUT_MS, MAX_FRAME,
				List.of(new ReplicationPeer(NODE_A, "127.0.0.1", portA, DC)));
		bindMinimal(transportA);
		bindMinimal(transportB);
		transportA.setForUpdateLockManager(locksA);
		transportB.setForUpdateLockManager(locksB);
		transportA.start();
		transportB.start();
		awaitPeerChannel(transportA, NODE_B);
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
	void statementScopedIdsAreUniqueAndNegative() {
		final long a = DistForUpdateCorrelatorUtil.nextStatementScopedId();
		final long b = DistForUpdateCorrelatorUtil.nextStatementScopedId();
		assertTrue(a < 0L);
		assertTrue(b < 0L);
		assertNotEquals(a, b);
	}

	@Test
	void concurrentAutocommitLocksDoNotCollideOnPeerWaiter() throws Exception {
		final NettyDistForUpdatePeerLockAgent agent1 =
				new NettyDistForUpdatePeerLockAgent(transportA, NODE_B);
		final NettyDistForUpdatePeerLockAgent agent2 =
				new NettyDistForUpdatePeerLockAgent(transportA, NODE_B);
		final long corr1 = DistForUpdateCorrelatorUtil.nextStatementScopedId();
		final long corr2 = DistForUpdateCorrelatorUtil.nextStatementScopedId();
		assertNotEquals(corr1, corr2);

		final CountDownLatch start = new CountDownLatch(1);
		final CountDownLatch done = new CountDownLatch(2);
		final AtomicReference<Throwable> err = new AtomicReference<>();

		final Thread t1 = Thread.ofVirtual().start(() -> {
			try {
				start.await(JOIN_MS, TimeUnit.MILLISECONDS);
				assertTrue(agent1.lock(corr1, TABLE, KEY_1, false));
				agent1.unlock(corr1, TABLE, KEY_1);
			} catch (Throwable t) {
				err.compareAndSet(null, t);
			} finally {
				done.countDown();
			}
		});
		final Thread t2 = Thread.ofVirtual().start(() -> {
			try {
				start.await(JOIN_MS, TimeUnit.MILLISECONDS);
				assertTrue(agent2.lock(corr2, TABLE, KEY_2, false));
				agent2.unlock(corr2, TABLE, KEY_2);
			} catch (Throwable t) {
				err.compareAndSet(null, t);
			} finally {
				done.countDown();
			}
		});
		start.countDown();
		assertTrue(done.await(JOIN_MS, TimeUnit.MILLISECONDS));
		t1.join(JOIN_MS);
		t2.join(JOIN_MS);
		assertTrue(err.get() == null, () -> "overlapping correlator failure: " + err.get());
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
				if (transport.sendForUpdateLockReq(peerId, -1L, true, TABLE, new byte[]{0})) {
					transport.sendForUpdateLockRelease(peerId, -1L, TABLE, new byte[]{0});
					return;
				}
			} catch (RuntimeException ignored) {
				// channel race
			}
			Thread.sleep(POLL_MS);
		}
		throw new IllegalStateException("peer channel not ready peerId=" + peerId);
	}

	private static int freePort() throws Exception {
		try (ServerSocket socket = new ServerSocket()) {
			socket.bind(new InetSocketAddress("127.0.0.1", 0));
			return socket.getLocalPort();
		}
	}
}
