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
import io.netty.channel.embedded.EmbeddedChannel;
import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.context.config.GridConfigurationProperties;
import org.genfork.grid.metrics.SqlTxMetrics;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlSession;
import org.genfork.grid.sql.netty.SqlExecHandler;
import org.genfork.grid.sql.netty.SqlFrame;
import org.genfork.grid.sql.netty.SqlOpcode;
import org.genfork.grid.sql.netty.SqlServer;
import org.genfork.grid.sql.netty.SqlWire;
import org.genfork.grid.sql.netty.SqlWireErrorCodes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Privilege DDL rides {@code _catalog} OpLog; peer AUTH uses local catalog only.
 * <p>
 * Catch-up is observable via {@link ReplicationCoordinator#catalogApplyLag()} and
 * {@link SqlTxMetrics#authFailures()} — not via readiness DOWN / {@code applyLagStale}.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
class PrivilegeCatalogCatchUpIT {
	private static final long APPLY_WAIT_MS = 20_000L;
	private static final long POLL_MS = 25L;
	private static final int SHARDS = 4;
	private static final int AUTH_REQUEST_ID = 1;
	private static final String APP_USER = "catchup_app";
	private static final String APP_PASSWORD = "catchup-secret";
	private static final String ADMIN_PASSWORD = "admin-secret";

	@TempDir
	Path tempDir;

	@Test
	void peerAuthSucceedsAfterCatalogDdlApply() throws Exception {
		final HeldPorts ports = HeldPorts.openPair();
		final GridConfigurationProperties propsA = haProps(
				"priv-a", ports.portA(), tempDir.resolve("a"),
				List.of(ReplTestSupport.peer("priv-b", "dc-a", ports.portB())));
		final GridConfigurationProperties propsB = haProps(
				"priv-b", ports.portB(), tempDir.resolve("b"),
				List.of(ReplTestSupport.peer("priv-a", "dc-a", ports.portA())));
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

			final boolean aWriter = coordA.isWriterEligible();
			final SqlEngine writer = aWriter ? engineA : engineB;
			final SqlEngine peer = aWriter ? engineB : engineA;
			final ReplicationCoordinator peerCoord = aWriter ? coordB : coordA;

			writer.execute("CREATE USER admin PASSWORD '" + ADMIN_PASSWORD + "'");
			final SqlSession admin = writer.newSession("admin", true);
			writer.execute(admin, "CREATE USER " + APP_USER + " PASSWORD '" + APP_PASSWORD + "'");

			assertTrue(await(() -> peer.privileges().authenticate(APP_USER, APP_PASSWORD)
							&& peerCoord.catalogApplyLag() == 0L),
					"peer must apply privilege DDL; lag=" + peerCoord.catalogApplyLag());
			assertTrue(peer.privileges().userNames().contains(APP_USER));
		} finally {
			coordA.stop();
			coordB.stop();
		}
	}

	@Test
	void authFailureIncrementsMetric() {
		final SqlEngine engine = new SqlEngine(new TableCatalog(tempDir.resolve("auth-fail")), null, SHARDS);
		engine.execute("CREATE USER admin PASSWORD '" + ADMIN_PASSWORD + "'");
		final long before = SqlTxMetrics.authFailures();

		final SqlExecHandler handler = new SqlExecHandler(
				engine, "", "", SqlServer.DEFAULT_MAX_TX_CONTEXTS);
		final EmbeddedChannel channel = new EmbeddedChannel(handler);
		try {
			channel.writeInbound(new SqlFrame(
					SqlOpcode.AUTH, AUTH_REQUEST_ID, SqlWire.auth("nope", "wrong")));
			final SqlFrame error = (SqlFrame) channel.readOutbound();
			assertEquals(SqlOpcode.ERROR, error.opcode());
			assertEquals(SqlWireErrorCodes.AUTH_FAILED, SqlWire.readError(error.payload()).code());
			assertTrue(SqlTxMetrics.authFailures() > before);
		} finally {
			channel.finishAndReleaseAll();
		}
	}

	private static GridConfigurationProperties haProps(
			String nodeId,
			int bindPort,
			Path dataDir,
			List<GridConfigurationProperties.ReplicationPeerProps> peers
	) {
		final GridConfigurationProperties props = ReplTestSupport.props(
				nodeId, "priv-ha", "dc-a", bindPort, dataDir, peers);
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
