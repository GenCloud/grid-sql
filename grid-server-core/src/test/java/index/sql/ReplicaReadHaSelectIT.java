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

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;

import index.unit.replication.ReplTestSupport;
import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.context.config.GridConfigurationProperties;
import org.genfork.grid.replication.OrchidNotSyncedException;
import org.genfork.grid.replication.ReplicaAccessGate;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.SqlSession;
import org.genfork.grid.sql.client.SessionRole;
import org.genfork.grid.store.TableStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two-node ORCHID: row SELECT on peer after apply; DML deny on READ_REPLICA;
 * Witness / lag admission via {@link ReplicaAccessGate} (fail-closed).
 * <p>
 * Writer is whichever node is eligible (not hard-pinned to node A). Swarm off so
 * apply path stays a plain two-node ship under parallel surefire.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public class ReplicaReadHaSelectIT {
	private static final String TABLE = "rr_ha";
	private static final String EXPECTED = "ok";
	private static final int PK_VALUE = 1;
	private static final long SYNC_WAIT_MS = 20_000L;
	private static final long APPLY_WAIT_MS = 20_000L;
	private static final long POLL_MS = 25L;
	private static final int SHARDS = 4;
	private static final long MAX_STALE_LAG_MS = 10_000L;

	@TempDir
	Path tempDir;

	@Test
	void selectOnPeerAfterPrimaryInsert() throws Exception {
		final HeldPorts ports = HeldPorts.openPair();
		final GridConfigurationProperties propsA = haProps(
				"rr-a", ports.portA(), tempDir.resolve("a"),
				List.of(ReplTestSupport.peer("rr-b", "dc-a", ports.portB())));
		final GridConfigurationProperties propsB = haProps(
				"rr-b", ports.portB(), tempDir.resolve("b"),
				List.of(ReplTestSupport.peer("rr-a", "dc-a", ports.portA())));
		ports.release();

		final ReplicationCoordinator coordA = new ReplicationCoordinator(propsA);
		final ReplicationCoordinator coordB = new ReplicationCoordinator(propsB);
		coordA.start();
		coordB.start();
		try {
			awaitSynced(coordA, coordB);
			final SqlEngine engineA = new SqlEngine(
					new TableCatalog(tempDir.resolve("a-cat")), coordA, SHARDS);
			final SqlEngine engineB = new SqlEngine(
					new TableCatalog(tempDir.resolve("b-cat")), coordB, SHARDS);

			final boolean aWriter = coordA.isWriterEligible();
			final SqlEngine writer = aWriter ? engineA : engineB;
			final SqlEngine peer = aWriter ? engineB : engineA;

			writer.execute("CREATE TABLE " + TABLE + " (id INT PRIMARY KEY, v VARCHAR)");
			awaitTable(peer, TABLE);
			writer.execute("INSERT INTO " + TABLE + " (id, v) VALUES (" + PK_VALUE + ", '" + EXPECTED + "')");
			awaitCommittedRow(peer, TABLE, PK_VALUE);

			final SqlSession replicaSession = peer.newSession();
			replicaSession.setSessionRole(SessionRole.READ_REPLICA);
			final SqlResult peerRows = peer.execute(
					replicaSession, "SELECT v FROM " + TABLE + " WHERE id = " + PK_VALUE);
			assertEquals(1, peerRows.rows().size(), "READ_REPLICA miss after committed apply");
			assertEquals(EXPECTED, String.valueOf(peerRows.rows().get(0)[0]));
		} finally {
			coordA.stop();
			coordB.stop();
		}
	}

	@Test
	void readReplicaSessionDeniesDml() throws Exception {
		final SqlEngine engine = new SqlEngine(new TableCatalog(tempDir.resolve("solo")), null, SHARDS);
		engine.execute("CREATE TABLE " + TABLE + " (id INT PRIMARY KEY, v VARCHAR)");
		engine.execute("INSERT INTO " + TABLE + " (id, v) VALUES (1, 'x')");
		final SqlSession replica = engine.newSession();
		replica.setSessionRole(SessionRole.READ_REPLICA);
		assertThrows(Exception.class, () ->
				engine.execute(replica, "INSERT INTO " + TABLE + " (id, v) VALUES (2, 'y')"));
		assertThrows(Exception.class, () ->
				engine.execute(replica, "BEGIN"));
		final SqlResult ok = engine.execute(replica, "SELECT v FROM " + TABLE + " WHERE id = 1");
		assertEquals(1, ok.rows().size());
	}

	@Test
	void witnessAndLagFailClosedAtGate() {
		assertThrows(OrchidNotSyncedException.class,
				() -> ReplicaAccessGate.ensureReplicaReadState(true, false, true, false));
		assertThrows(OrchidNotSyncedException.class,
				() -> ReplicaAccessGate.ensureReplicaReadState(true, true, true, true));
		assertDoesNotThrow(() ->
				ReplicaAccessGate.ensureReplicaReadState(true, false, true, true));
	}

	private static GridConfigurationProperties haProps(
			String nodeId,
			int bindPort,
			Path dataDir,
			List<GridConfigurationProperties.ReplicationPeerProps> peers
	) {
		final GridConfigurationProperties props = ReplTestSupport.props(
				nodeId, "rr-ha", "dc-a", bindPort, dataDir, peers);
		props.getReplication().getHa().setReplicaReadsEnabled(true);
		props.getReplication().getHa().setMaxStaleLag(MAX_STALE_LAG_MS);
		// Keep the IT on a plain two-node ship path under parallel surefire.
		props.getReplication().getSwarm().setEnabled(false);
		props.getReplication().getPlacementOptimizer().setEnabled(false);
		return props;
	}

	private static void awaitSynced(ReplicationCoordinator a, ReplicationCoordinator b)
			throws InterruptedException {
		final long deadline = System.currentTimeMillis() + SYNC_WAIT_MS;
		while (System.currentTimeMillis() < deadline
				&& (a.getOrchidNode().livePeerCount() < 1
				|| b.getOrchidNode().livePeerCount() < 1
				|| (!a.isWriterEligible() && !b.isWriterEligible()))) {
			Thread.sleep(POLL_MS);
		}
		assertTrue(a.getOrchidNode().livePeerCount() >= 1, "a has no live peer");
		assertTrue(b.getOrchidNode().livePeerCount() >= 1, "b has no live peer");
		assertTrue(a.isWriterEligible() || b.isWriterEligible(), "no writer-eligible node");
	}

	private static void awaitTable(SqlEngine engine, String table) throws InterruptedException {
		final long deadline = System.currentTimeMillis() + APPLY_WAIT_MS;
		while (System.currentTimeMillis() < deadline && !engine.catalog().exists(table)) {
			Thread.sleep(POLL_MS);
		}
		assertTrue(engine.catalog().exists(table), "peer catalog missing " + table);
	}

	/**
	 * Wait for the UPSERT to land in the peer store (bypass READ_REPLICA admission timing).
	 */
	private static void awaitCommittedRow(SqlEngine peer, String table, int pk)
			throws InterruptedException {
		final long deadline = System.currentTimeMillis() + APPLY_WAIT_MS;
		TableStore store = null;
		byte[] found = null;
		while (System.currentTimeMillis() < deadline) {
			store = peer.catalog().getStore(table);
			if (store != null) {
				final byte[] key = store.keyBytesForPk(pk);
				found = store.getCommittedBytes(key);
				if (found != null) {
					break;
				}
			}
			Thread.sleep(POLL_MS);
		}
		assertNotNull(store, "peer store missing for " + table);
		assertNotNull(found, "peer missing committed row id=" + pk);
	}

	/**
	 * Hold ephemeral ports until props are built so parallel surefire cannot steal them
	 * between {@code freePort()} and Netty bind.
	 */
	private static final class HeldPorts {
		private final ServerSocket socketA;
		private final ServerSocket socketB;

		private HeldPorts(ServerSocket socketA, ServerSocket socketB) {
			this.socketA = socketA;
			this.socketB = socketB;
		}

		static HeldPorts openPair() throws Exception {
			final ServerSocket a = new ServerSocket();
			a.setReuseAddress(true);
			a.bind(new InetSocketAddress("127.0.0.1", 0));
			final ServerSocket b = new ServerSocket();
			b.setReuseAddress(true);
			b.bind(new InetSocketAddress("127.0.0.1", 0));
			return new HeldPorts(a, b);
		}

		int portA() {
			return socketA.getLocalPort();
		}

		int portB() {
			return socketB.getLocalPort();
		}

		void release() throws Exception {
			socketA.close();
			socketB.close();
		}
	}
}
