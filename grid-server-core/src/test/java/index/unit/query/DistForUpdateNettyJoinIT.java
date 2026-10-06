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
import java.util.concurrent.atomic.AtomicReference;

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.replication.netty.NettyReplicationTransport;
import org.genfork.grid.replication.transport.ReplicationPeer;
import org.genfork.grid.replication.tx.TxEnvelopeCoordinator;
import org.genfork.grid.serial.SqlWireUtil;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.SqlSession;
import org.genfork.grid.sql.tx.LockWaitTimeoutException;
import org.genfork.grid.sql.tx.NettyDistForUpdatePeerLockAgent;
import org.genfork.grid.sql.tx.SqlRecordLockManager;
import org.genfork.grid.threading.ThreadService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Netty Dist FOR UPDATE on INNER JOIN locks both tables' keys on the peer.
 *
 * @author: GenCloud
 * @date: 2026/01
 * @since: 1.0
 */
public class DistForUpdateNettyJoinIT {
	private static final String CLUSTER = "fu-netty-join";
	private static final String DC = "dc0";
	private static final String NODE_A = "n-a";
	private static final String NODE_B = "n-b";
	private static final long CONNECT_TIMEOUT_MS = 3_000L;
	private static final int MAX_FRAME = 1 << 20;
	private static final long AWAIT_CHANNEL_MS = 5_000L;
	private static final long POLL_MS = 50L;
	private static final long JOIN_TIMEOUT_MS = 1_500L;
	private static final int SHARDS = 4;

	private NettyReplicationTransport transportA;
	private NettyReplicationTransport transportB;
	private SqlRecordLockManager locksB;
	private SqlEngine primary;
	private TxEnvelopeCoordinator envelope;

	@BeforeEach
	void setUp() throws Exception {
		ThreadService.ensureRunning();
		final int portA = freePort();
		final int portB = freePort();
		final SqlRecordLockManager locksA = new SqlRecordLockManager();
		locksB = new SqlRecordLockManager();
		locksA.setLockWaitTimeoutMs(500L);
		locksB.setLockWaitTimeoutMs(500L);

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

		primary = new SqlEngine(new TableCatalog(), null, SHARDS);
		primary.setDistForUpdatePeerLockAgents(List.of(
				new NettyDistForUpdatePeerLockAgent(transportA, NODE_B)));
		envelope = new TxEnvelopeCoordinator();

		primary.execute("CREATE TABLE fu_left (id INT PRIMARY KEY, rid INT)");
		primary.execute("CREATE TABLE fu_right (id INT PRIMARY KEY, state VARCHAR)");
		primary.execute("INSERT INTO fu_left VALUES (1, 10)");
		primary.execute("INSERT INTO fu_right VALUES (10, 'new')");
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
	void joinForUpdateLocksRightKeyOnPeerViaNetty() throws Exception {
		final SqlSession writer = primary.newSession();
		writer.setEnvelopeCoordinator(envelope);
		primary.execute(writer, "BEGIN");
		final SqlResult locked = primary.execute(writer,
				"SELECT fu_left.id FROM fu_left INNER JOIN fu_right ON fu_left.rid = fu_right.id FOR UPDATE");
		assertEquals(1, locked.rows().size());

		final AtomicReference<Throwable> error = new AtomicReference<>();
		final Thread waiter = Thread.ofVirtual().start(() -> {
			try {
				locksB.lock("fu_right", intKey(10));
				locksB.unlock("fu_right", intKey(10));
			} catch (Throwable t) {
				error.set(t);
			}
		});
		waiter.join(JOIN_TIMEOUT_MS);
		assertInstanceOf(LockWaitTimeoutException.class, error.get());
		primary.execute(writer, "ROLLBACK");
	}

	private static byte[] intKey(int id) {
		return SqlWireUtil.toGenericArray(id);
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
				if (transport.sendForUpdateLockReq(peerId, 1L, true, "warmup", new byte[]{0})) {
					transport.sendForUpdateLockRelease(peerId, 1L, "warmup", new byte[]{0});
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
