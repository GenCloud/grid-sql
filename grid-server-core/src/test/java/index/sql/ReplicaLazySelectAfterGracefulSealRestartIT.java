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
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import index.unit.replication.ReplTestSupport;
import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.context.config.GridConfigurationProperties;
import org.genfork.grid.fs.GridFs;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.replication.util.SealedShardInventoryEntry;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.SqlSession;
import org.genfork.grid.sql.client.SessionRole;
import org.genfork.grid.store.TableStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lab 1dc-n3 repro: after graceful sealed stop + long idle + LAZY restart, replica
 * {@code SELECT} by PK returns empty while writer still sees the row (disk sealed
 * identical). App then {@code INSERT} on writer → strict PK {@code not unique}.
 * <p>
 * Contract: peer {@link SessionRole#READ_REPLICA} PK and {@code SELECT *} after reopen
 * must see the committed row (or fail-closed incomplete) — never a silent empty success.
 * HELLO sealed inventory must include the sealed {@code domain#shard} after catalog bootstrap.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
public class ReplicaLazySelectAfterGracefulSealRestartIT {
	private static final String TABLE = "rlazy_castle";
	private static final String EXPECTED = "alive";
	private static final int PK_VALUE = 2;
	private static final int SHARDS = 4;
	private static final long SYNC_WAIT_MS = 30_000L;
	private static final long APPLY_WAIT_MS = 30_000L;
	private static final long POLL_MS = 25L;
	private static final long MAX_STALE_LAG_MS = 10_000L;
	private static final String HYDRATE_LAZY = "LAZY";
	private static final String CLUSTER_ID = "rlazy-ha";
	private static final String SEALED_DIR = "sealed";
	private static final String GMAP_GLOB = "*.gmap";

	@TempDir(cleanup = CleanupMode.NEVER)
	Path tempDir;

	@Test
	void peerPkSelectSeesRowAfterGracefulSealAndLazyReopen() throws Exception {
		final Path dataA = tempDir.resolve("a");
		final Path dataB = tempDir.resolve("b");
		final Path catA = tempDir.resolve("a-cat");
		final Path catB = tempDir.resolve("b-cat");

		runEpoch(dataA, dataB, catA, catB, true);
		runEpoch(dataA, dataB, catA, catB, false);
	}

	private void runEpoch(
			Path dataA,
			Path dataB,
			Path catA,
			Path catB,
			boolean seed
	) throws Exception {
		final HeldPorts ports = HeldPorts.openPair();
		final GridConfigurationProperties propsA = haLazyProps(
				"rlazy-a", ports.portA(), dataA,
				List.of(ReplTestSupport.peer("rlazy-b", "dc-a", ports.portB())));
		final GridConfigurationProperties propsB = haLazyProps(
				"rlazy-b", ports.portB(), dataB,
				List.of(ReplTestSupport.peer("rlazy-a", "dc-a", ports.portA())));
		ports.release();

		final ReplicationCoordinator coordA = new ReplicationCoordinator(propsA);
		final ReplicationCoordinator coordB = new ReplicationCoordinator(propsB);
		coordA.start();
		coordB.start();
		try {
			awaitSynced(coordA, coordB);
			final SqlEngine engineA = bootEngine(catA, coordA);
			final SqlEngine engineB = bootEngine(catB, coordB);

			final boolean aWriter = coordA.isWriterEligible();
			final SqlEngine writer = aWriter ? engineA : engineB;
			final SqlEngine peer = aWriter ? engineB : engineA;
			final ReplicationCoordinator writerCoord = aWriter ? coordA : coordB;
			final ReplicationCoordinator peerCoord = aWriter ? coordB : coordA;
			final Path peerData = aWriter ? dataB : dataA;

			if (seed) {
				writer.execute("CREATE TABLE " + TABLE + " (id INT PRIMARY KEY, v VARCHAR)");
				awaitTable(peer, TABLE);
				writer.execute(
						"INSERT INTO " + TABLE + " (id, v) VALUES (" + PK_VALUE + ", '" + EXPECTED + "')");
				awaitCommittedRow(peer, TABLE, PK_VALUE);
				assertTrue(writerCoord.dumpDomainSnapshot(TABLE) >= 1, "writer seal dump");
				assertTrue(peerCoord.dumpDomainSnapshot(TABLE) >= 1, "peer seal dump");
				assertTrue(countGmapFiles(dataA, "rlazy-a") > 0, "writer sealed .gmap after dump");
				assertTrue(countGmapFiles(dataB, "rlazy-b") > 0, "peer sealed .gmap after dump");
			} else {
				awaitTable(writer, TABLE);
				awaitTable(peer, TABLE);

				final String peerNodeId = aWriter ? "rlazy-b" : "rlazy-a";
				assertTrue(countGmapFiles(peerData, peerNodeId) > 0,
						"peer .gmap must exist after graceful seal+LAZY reopen");

				final SqlResult writerRows = writer.execute(
						"SELECT v FROM " + TABLE + " WHERE id = " + PK_VALUE);
				assertFalse(writerRows.rows().isEmpty(), "writer PK empty after LAZY reopen");
				assertEquals(EXPECTED, String.valueOf(writerRows.rows().get(0)[0]));

				final TableStore peerStore = peer.catalog().getStore(TABLE);
				assertNotNull(peerStore, "peer store missing after reopen");
				final byte[] key = peerStore.keyBytesForPk(PK_VALUE);
				final byte[] committed = peerStore.getCommittedBytes(key);
				assertNotNull(committed,
						"peer getCommittedBytes null after graceful seal+LAZY reopen "
								+ "(lab: silent empty SELECT on replica)");

				final SqlSession replicaSession = peer.newSession();
				replicaSession.setSessionRole(SessionRole.READ_REPLICA);
				final SqlResult peerRows = peer.execute(
						replicaSession, "SELECT v FROM " + TABLE + " WHERE id = " + PK_VALUE);
				assertEquals(1, peerRows.rows().size(),
						"READ_REPLICA PK SELECT empty after graceful seal+LAZY reopen "
								+ "(lab 1dc-n3: getOne==null then INSERT not unique on writer)");
				assertEquals(EXPECTED, String.valueOf(peerRows.rows().get(0)[0]));

				final SqlResult peerStar = peer.execute(
						replicaSession, "SELECT id, v FROM " + TABLE);
				assertEquals(1, peerStar.rows().size(),
						"READ_REPLICA SELECT * empty after graceful seal+LAZY reopen");
				assertEquals(PK_VALUE, ((Number) peerStar.rows().get(0)[0]).intValue());
				assertEquals(EXPECTED, String.valueOf(peerStar.rows().get(0)[1]));

				assertTrue(inventoryHasDomain(peerCoord, TABLE),
						"HELLO sealed inventory missing domain after catalog bootstrap");
				assertTrue(inventoryHasDomain(writerCoord, TABLE),
						"writer HELLO sealed inventory missing domain after catalog bootstrap");
			}
		} finally {
			coordA.stop();
			coordB.stop();
		}
	}

	/**
	 * Product boot: catalog recover + HELLO inventory rebuild before advertise.
	 */
	private static SqlEngine bootEngine(Path catalogDir, ReplicationCoordinator coord) {
		final SqlEngine engine = new SqlEngine(new TableCatalog(catalogDir), coord, SHARDS);
		engine.recoverPersistedCatalog();
		engine.seedCatalogDdlEpochFloor();
		coord.markCatalogBootstrapComplete();
		return engine;
	}

	private static boolean inventoryHasDomain(ReplicationCoordinator coord, String domain) {
		final List<SealedShardInventoryEntry> inventory = coord.sealedShardInventory();
		if (inventory == null || inventory.isEmpty()) {
			return false;
		}
		for (SealedShardInventoryEntry entry : inventory) {
			if (entry != null && domain.equals(entry.domain()) && entry.identityFingerprint() != 0L) {
				return true;
			}
		}
		return false;
	}

	private static int countGmapFiles(Path dataDir, String nodeId) throws Exception {
		final Path sealed = dataDir.resolve(CLUSTER_ID).resolve(nodeId).resolve(SEALED_DIR);
		if (!GridFs.isDirectory(sealed)) {
			return 0;
		}
		int count = 0;
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(sealed, GMAP_GLOB)) {
			for (Path ignored : stream) {
				count++;
			}
		}
		return count;
	}

	private static GridConfigurationProperties haLazyProps(
			String nodeId,
			int bindPort,
			Path dataDir,
			List<GridConfigurationProperties.ReplicationPeerProps> peers
	) {
		final GridConfigurationProperties props = ReplTestSupport.props(
				nodeId, "rlazy-ha", "dc-a", bindPort, dataDir, peers);
		props.getDurability().setEnabled(true);
		props.getDurability().setHydrateMode(HYDRATE_LAZY);
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
		assertTrue(a.getOrchidNode().livePeerCount() >= 1, "a has no live peer");
		assertTrue(b.getOrchidNode().livePeerCount() >= 1, "b has no live peer");
		assertTrue(a.isWriterEligible() || b.isWriterEligible(), "no writer-eligible node");
	}

	private static void awaitTable(SqlEngine engine, String table) throws InterruptedException {
		final long deadline = System.currentTimeMillis() + APPLY_WAIT_MS;
		while (System.currentTimeMillis() < deadline && !engine.catalog().exists(table)) {
			Thread.sleep(POLL_MS);
		}
		assertTrue(engine.catalog().exists(table), "catalog missing " + table);
	}

	private static void awaitCommittedRow(SqlEngine peer, String table, int pk)
			throws InterruptedException {
		final long deadline = System.currentTimeMillis() + APPLY_WAIT_MS;
		TableStore store = null;
		byte[] found = null;
		while (System.currentTimeMillis() < deadline) {
			store = peer.catalog().getStore(table);
			if (store != null) {
				found = store.getCommittedBytes(store.keyBytesForPk(pk));
				if (found != null) {
					break;
				}
			}
			Thread.sleep(POLL_MS);
		}
		assertNotNull(store, "peer store missing for " + table);
		assertNotNull(found, "peer missing committed row id=" + pk);
	}

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
