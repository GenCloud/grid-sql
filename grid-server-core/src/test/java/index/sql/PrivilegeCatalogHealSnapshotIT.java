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
import java.util.function.BooleanSupplier;

import index.unit.replication.ReplTestSupport;
import org.genfork.grid.catalog.PrivilegeCatalogFingerprintUtil;
import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.context.config.GridConfigurationProperties;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Privilege meta hole with intact schema heals via catalog DDL snapshot (HELLO privilegeFp).
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
class PrivilegeCatalogHealSnapshotIT {
	private static final long APPLY_WAIT_MS = 30_000L;
	private static final long POLL_MS = 25L;
	private static final int SHARDS = 4;
	private static final String APP_USER = "heal_app";
	private static final String APP_PASSWORD = "heal-secret";
	private static final String ADMIN_PASSWORD = "admin-secret";

	@TempDir
	Path tempDir;

	@Test
	void privilegeHoleHealsViaSnapshotWithoutSchemaChange() throws Exception {
		final HeldPorts ports = HeldPorts.openPair();
		final GridConfigurationProperties propsA = haProps(
				"priv-heal-a", ports.portA(), tempDir.resolve("a"),
				List.of(ReplTestSupport.peer("priv-heal-b", "dc-a", ports.portB())));
		final GridConfigurationProperties propsB = haProps(
				"priv-heal-b", ports.portB(), tempDir.resolve("b"),
				List.of(ReplTestSupport.peer("priv-heal-a", "dc-a", ports.portA())));
		ports.release();

		final ReplicationCoordinator coordA = new ReplicationCoordinator(propsA);
		final ReplicationCoordinator coordB = new ReplicationCoordinator(propsB);
		coordA.start();
		coordB.start();
		try {
			assertTrue(await(() -> coordA.getOrchidNode().livePeerCount() >= 1
							&& coordB.getOrchidNode().livePeerCount() >= 1
							&& (coordA.isWriterEligible() || coordB.isWriterEligible())),
					"HA pair did not sync");

			final SqlEngine engineA = new SqlEngine(
					new TableCatalog(tempDir.resolve("a-cat")), coordA, SHARDS);
			final SqlEngine engineB = new SqlEngine(
					new TableCatalog(tempDir.resolve("b-cat")), coordB, SHARDS);
			coordA.markCatalogBootstrapComplete();
			coordB.markCatalogBootstrapComplete();

			final boolean aWriter = coordA.isWriterEligible();
			final SqlEngine writer = aWriter ? engineA : engineB;
			final SqlEngine peer = aWriter ? engineB : engineA;
			final ReplicationCoordinator writerCoord = aWriter ? coordA : coordB;
			final ReplicationCoordinator peerCoord = aWriter ? coordB : coordA;

			writer.execute("CREATE USER admin PASSWORD '" + ADMIN_PASSWORD + "'");
			final SqlSession admin = writer.newSession("admin", true);
			writer.execute(admin, "CREATE USER " + APP_USER + " PASSWORD '" + APP_PASSWORD + "'");

			assertTrue(await(() -> peer.privileges().authenticate(APP_USER, APP_PASSWORD)
							&& peerCoord.catalogApplyLag() == 0L),
					"peer must apply privilege DDL before hole; lag=" + peerCoord.catalogApplyLag());

			final long writerPrivFp = PrivilegeCatalogFingerprintUtil.fingerprint(writer.privileges());
			assertTrue(writerPrivFp != 0L, "writer privilege fingerprint must be non-zero");

			peer.privileges().dropUser(APP_USER);
			assertFalse(peer.privileges().authenticate(APP_USER, APP_PASSWORD));
			final long holePrivFp = PrivilegeCatalogFingerprintUtil.fingerprint(peer.privileges());
			assertTrue(holePrivFp != 0L && holePrivFp != writerPrivFp, "hole must diverge privilege Fp");

			writerCoord.getNettyTransport().broadcastHello();
			peerCoord.getNettyTransport().broadcastHello();

			assertTrue(await(() -> peer.privileges().authenticate(APP_USER, APP_PASSWORD)
							&& PrivilegeCatalogFingerprintUtil.fingerprint(peer.privileges()) == writerPrivFp),
					"privilege meta heal must restore app user; peerFp="
							+ PrivilegeCatalogFingerprintUtil.fingerprint(peer.privileges())
							+ " writerFp=" + writerPrivFp);
			assertEquals(writerPrivFp, peerCoord.privilegeFingerprint());
			assertFalse(writerCoord.isPrivilegeFingerprintStale());
			assertFalse(peerCoord.isPrivilegeFingerprintStale());
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
				nodeId, "priv-heal-ha", "dc-a", bindPort, dataDir, peers);
		props.getReplication().getSwarm().setEnabled(false);
		props.getReplication().getPlacementOptimizer().setEnabled(false);
		return props;
	}

	private static boolean await(BooleanSupplier condition) throws InterruptedException {
		final long deadline = System.currentTimeMillis() + APPLY_WAIT_MS;
		while (System.currentTimeMillis() < deadline) {
			if (condition.getAsBoolean()) {
				return true;
			}
			Thread.sleep(POLL_MS);
		}
		return condition.getAsBoolean();
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
