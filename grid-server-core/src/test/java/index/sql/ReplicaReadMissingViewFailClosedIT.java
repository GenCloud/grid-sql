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

import index.unit.replication.ReplTestSupport;
import org.genfork.grid.catalog.CatalogDdlCompactor;
import org.genfork.grid.catalog.CatalogFingerprintUtil;
import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.context.config.GridConfigurationProperties;
import org.genfork.grid.replication.OrchidNotSyncedException;
import org.genfork.grid.replication.ReplicaAccessGate;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.replication.netty.codec.CatalogDdlSnapshotRpcCodec.CatalogDdlSnapshot;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.SqlSession;
import org.genfork.grid.sql.client.SessionRole;
import org.genfork.grid.store.TableStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * READ_REPLICA fail-closed on catalog fingerprint hole; reconcile restores select.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
public class ReplicaReadMissingViewFailClosedIT {
	private static final String BASE = "rrmv_base";
	private static final String VIEW = "rrmv_view";
	private static final long SYNC_WAIT_MS = 20_000L;
	private static final long APPLY_WAIT_MS = 20_000L;
	private static final long POLL_MS = 25L;
	private static final long MAX_STALE_LAG_MS = 10_000L;

	@TempDir
	Path tempDir;

	@Test
	void replicaReadDeniesOnFingerprintHoleThenOkAfterReconcile() throws Exception {
		final int portA = freePort();
		final int portB = freePort();
		final GridConfigurationProperties propsA = haProps(
				"rrmv-a", portA, tempDir.resolve("a"),
				List.of(ReplTestSupport.peer("rrmv-b", "dc-a", portB)));
		final GridConfigurationProperties propsB = haProps(
				"rrmv-b", portB, tempDir.resolve("b"),
				List.of(ReplTestSupport.peer("rrmv-a", "dc-a", portA)));

		final ReplicationCoordinator coordA = new ReplicationCoordinator(propsA);
		final ReplicationCoordinator coordB = new ReplicationCoordinator(propsB);
		coordA.start();
		coordB.start();
		try {
			awaitSynced(coordA, coordB);
			final SqlEngine engineA = new SqlEngine(
					new TableCatalog(tempDir.resolve("a-cat")), coordA, 4);
			final SqlEngine engineB = new SqlEngine(
					new TableCatalog(tempDir.resolve("b-cat")), coordB, 4);

			final boolean aWriter = coordA.isWriterEligible();
			final SqlEngine writer = aWriter ? engineA : engineB;
			final SqlEngine peer = aWriter ? engineB : engineA;
			final ReplicationCoordinator peerCoord = aWriter ? coordB : coordA;
			final ReplicationCoordinator writerCoord = aWriter ? coordA : coordB;

			writer.execute("CREATE TABLE " + BASE + " (id INT PRIMARY KEY, v INT)");
			writer.execute("CREATE VIEW " + VIEW + " AS SELECT id, v FROM " + BASE);
			awaitView(peer, VIEW);
			writer.execute("INSERT INTO " + BASE + " (id, v) VALUES (1, 42)");
			awaitRow(peer);

			peer.catalog().dropView(VIEW, false);
			assertTrue(CatalogFingerprintUtil.fingerprint(writer.catalog())
					!= CatalogFingerprintUtil.fingerprint(peer.catalog()));

			peerCoord.observePeerCatalogFence(
					writerCoord.getNodeState().getNodeId(),
					writerCoord.catalogAppliedEpoch(),
					CatalogFingerprintUtil.fingerprint(writer.catalog()));

			final OrchidNotSyncedException denied = assertThrows(OrchidNotSyncedException.class,
					() -> ReplicaAccessGate.ensureReplicaRead(peerCoord));
			assertTrue(denied.getMessage().toLowerCase().contains("catalog"),
					"expected catalog incomplete fence, got: " + denied.getMessage());

			peer.applyCatalogJournalSnapshot(new CatalogDdlSnapshot(
					writerCoord.getNodeState().getNodeId(),
					CatalogFingerprintUtil.fingerprint(writer.catalog()),
					peer.catalog().maxAppliedDdlEpoch(),
					CatalogDdlCompactor.compactLines(writer.catalog())));
			assertNotNull(peer.catalog().getView(VIEW));

			peerCoord.observePeerCatalogFence(
					writerCoord.getNodeState().getNodeId(),
					writerCoord.catalogAppliedEpoch(),
					CatalogFingerprintUtil.fingerprint(peer.catalog()));
			ReplicaAccessGate.ensureReplicaRead(peerCoord);

			final SqlSession replica = peer.newSession();
			replica.setSessionRole(SessionRole.READ_REPLICA);
			final SqlResult rows = peer.execute(replica, "SELECT v FROM " + VIEW + " WHERE id = 1");
			assertEquals(1, rows.rows().size());
			assertEquals(42, ((Number) rows.rows().get(0)[0]).intValue());
		} finally {
			coordA.stop();
			coordB.stop();
		}
	}

	private static GridConfigurationProperties haProps(
			String nodeId,
			int bindPort,
			Path dataDir,
			List<GridConfigurationProperties.ReplicationPeerProps> peers
	) {
		final GridConfigurationProperties props = ReplTestSupport.props(
				nodeId, "rrmv", "dc-a", bindPort, dataDir, peers);
		props.getReplication().getHa().setReplicaReadsEnabled(true);
		props.getReplication().getHa().setMaxStaleLag(MAX_STALE_LAG_MS);
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
		assertTrue(a.isWriterEligible() || b.isWriterEligible());
	}

	private static void awaitView(SqlEngine engine, String view) throws InterruptedException {
		final long deadline = System.currentTimeMillis() + APPLY_WAIT_MS;
		while (System.currentTimeMillis() < deadline && engine.catalog().getView(view) == null) {
			Thread.sleep(POLL_MS);
		}
		assertNotNull(engine.catalog().getView(view));
	}

	private static void awaitRow(SqlEngine peer) throws InterruptedException {
		final long deadline = System.currentTimeMillis() + APPLY_WAIT_MS;
		TableStore store = null;
		byte[] found = null;
		while (System.currentTimeMillis() < deadline) {
			store = peer.catalog().getStore(BASE);
			if (store != null) {
				found = store.getCommittedBytes(store.keyBytesForPk(1));
				if (found != null) {
					return;
				}
			}
			Thread.sleep(POLL_MS);
		}
		assertNotNull(store, "peer store missing for " + BASE);
		assertNotNull(found, "row not applied on peer");
	}

	private static int freePort() throws Exception {
		try (ServerSocket socket = new ServerSocket(0)) {
			socket.setReuseAddress(true);
			return socket.getLocalPort();
		}
	}
}
