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
package index.sql;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import index.unit.replication.ReplTestSupport;
import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.context.config.GridConfigurationProperties;
import org.genfork.grid.metrics.SqlLockMetrics;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.client.RemoteConnection;
import org.genfork.grid.sql.client.RemoteConnectionFactory;
import org.genfork.grid.sql.client.TxContext;
import org.genfork.grid.sql.netty.SqlServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hibernate/sleep→wake without OS suspend: abrupt death of SQL + replication channels
 * mid-TX, then reconnect and assert record locks are released.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
class SqlSuspendWakeLockRecoveryIT {
	private static final String TABLE = "suspend_wake_t";
	private static final String CLUSTER = "suspend-wake";
	private static final long LOCK_WAIT_MS = 2_000L;
	private static final Duration SECOND_UPDATE_BUDGET = Duration.ofMillis(2_000L);
	private static final long WAKE_SETTLE_MS = 400L;
	private static final long SERVER_READY_MS = 150L;
	private static final long JOIN_DEADLINE_MS = 8_000L;
	private static final long POLL_MS = 50L;
	private static final long RECONNECT_DEADLINE_MS = 10_000L;

	@TempDir
	Path tempDir;

	@Test
	void suspendSimAbruptDeathReleasesLockAndPeersRejoin() throws Exception {
		final int replA = freePort();
		final int replB = freePort();
		final int sqlA = freePort();

		final GridConfigurationProperties propsA = ReplTestSupport.props(
				"sw-a", CLUSTER, "dc-a", replA, tempDir.resolve("a"),
				new ArrayList<>(List.of(ReplTestSupport.peer("sw-b", "dc-a", replB))));
		propsA.getReplication().getOrchid().setOrderThreshold(0.5);
		final GridConfigurationProperties propsB = ReplTestSupport.props(
				"sw-b", CLUSTER, "dc-a", replB, tempDir.resolve("b"),
				new ArrayList<>(List.of(ReplTestSupport.peer("sw-a", "dc-a", replA))));
		propsB.getReplication().getOrchid().setOrderThreshold(0.5);

		final ReplicationCoordinator coordA = new ReplicationCoordinator(propsA);
		final ReplicationCoordinator coordB = new ReplicationCoordinator(propsB);
		coordA.start();
		coordB.start();
		SqlServer sqlServer = null;
		RemoteConnectionFactory factory = null;
		try {
			awaitLiveAtLeast(coordA, 1, JOIN_DEADLINE_MS);

			final SqlEngine engineA = new SqlEngine(
					new TableCatalog(Files.createTempDirectory(tempDir, "cat-a")), coordA, 4);
			engineA.lockManager().setLockWaitTimeoutMs(LOCK_WAIT_MS);
			// Peer engine keeps coordB catalog binding alive; lock leak is on writer engineA.
			new SqlEngine(new TableCatalog(Files.createTempDirectory(tempDir, "cat-b")), coordB, 4);

			engineA.execute("CREATE TABLE " + TABLE + " (id INT PRIMARY KEY, v INT)");
			engineA.execute("INSERT INTO " + TABLE + " VALUES (1, 0)");

			sqlServer = new SqlServer("127.0.0.1", sqlA, engineA, "u", "p", 8);
			sqlServer.start();
			TimeUnit.MILLISECONDS.sleep(SERVER_READY_MS);

			final RemoteConnectionFactory remoteFactory =
					new RemoteConnectionFactory("127.0.0.1", sqlA, "u", "p", 8, 2, "public", false);
			factory = remoteFactory;
			final RemoteConnection holder = (RemoteConnection) remoteFactory.obtain().block(Duration.ofSeconds(5L));
			final TxContext tx = holder.begin().block(Duration.ofSeconds(5L));
			tx.createStatement("UPDATE " + TABLE + " SET v = 1 WHERE id = 1")
					.execute()
					.blockLast(Duration.ofSeconds(5L));

			final long timeoutsBefore = SqlLockMetrics.waitTimeouts();

			// Suspend-sim: sockets die while host "sleeps" — no SESSION_CLOSE / graceful leave.
			holder.channel().close().syncUninterruptibly();
			coordA.isolatePeer("sw-b");
			coordB.isolatePeer("sw-a");

			TimeUnit.MILLISECONDS.sleep(WAKE_SETTLE_MS);

			// Wake-sim: restore membership; reconnectMissing / HELLO revive peers.
			coordA.reconnectPeer("sw-b");
			coordB.reconnectPeer("sw-a");
			awaitLiveAtLeast(coordA, 1, RECONNECT_DEADLINE_MS);

			assertTimeoutPreemptively(SECOND_UPDATE_BUDGET, () -> {
				final RemoteConnection next = (RemoteConnection) remoteFactory.obtain().block(Duration.ofSeconds(5L));
				assertTrue(next.isOpen());
				final TxContext tx2 = next.begin().block(Duration.ofSeconds(5L));
				tx2.createStatement("UPDATE " + TABLE + " SET v = 3 WHERE id = 1")
						.execute()
						.blockLast(Duration.ofSeconds(5L));
				tx2.commit().block(Duration.ofSeconds(5L));
				next.close().block(Duration.ofSeconds(5L));
			});

			assertEquals(3, ((Number) engineA.execute("SELECT v FROM " + TABLE + " WHERE id = 1")
					.rows().getFirst()[0]).intValue());
			assertEquals(timeoutsBefore, SqlLockMetrics.waitTimeouts(),
					"suspend-sim recover must not accumulate lockWaitTimeouts");
		} finally {
			if (factory != null) {
				factory.dispose();
			}
			if (sqlServer != null) {
				sqlServer.close();
			}
			coordA.stop();
			coordB.stop();
		}
	}

	private static void awaitLiveAtLeast(ReplicationCoordinator coord, int minLive, long deadlineMs)
			throws InterruptedException {
		final long deadline = System.currentTimeMillis() + deadlineMs;
		while (System.currentTimeMillis() < deadline
				&& coord.getOrchidNode().livePeerCount() < minLive) {
			TimeUnit.MILLISECONDS.sleep(POLL_MS);
		}
		assertTrue(coord.getOrchidNode().livePeerCount() >= minLive,
				"expected livePeerCount>=" + minLive
						+ " but was " + coord.getOrchidNode().livePeerCount());
	}

	private static int freePort() throws Exception {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}
}
