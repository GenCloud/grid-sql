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
package index.unit.replication;

import index.sql.SqlBenchHelper;
import org.genfork.grid.context.config.GridConfigurationProperties;
import org.genfork.grid.context.config.GridConfigurationProperties.ReplicationPeerProps;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.replication.crossdc.CrossDcMode;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.SqlSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ASYNC_SHIP Active writer: ack'd concat append must be visible on the same node.
 * <p>
 * Characterization for Jepsen multidc-async-chaos Elle G-single (append :ok then read nil)
 * before nemesis — same sticky writer, no concurrent TX on the key.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
class AsyncShipListAppendVisibilityIT {
	private static final String TABLE = "async_append";
	private static final int KEY_ID = 15;
	private static final long SYNC_MS = 8_000L;

	@TempDir
	Path tempDir;

	private ReplicationCoordinator a1;
	private ReplicationCoordinator a2;
	private ReplicationCoordinator b1;
	private SqlEngine engine;

	@AfterEach
	void tearDown() {
		if (engine != null) {
			SqlBenchHelper.closeAllStores(engine);
		}
		if (a1 != null) {
			a1.stop();
		}
		if (a2 != null) {
			a2.stop();
		}
		if (b1 != null) {
			b1.stop();
		}
	}

	@Test
	void appendThenReadOnActiveWriter_seesToken() throws Exception {
		final int p1 = freePort();
		final int p2 = freePort();
		final int p3 = freePort();
		final GridConfigurationProperties propsA1 = asyncProps("a1", "dc-a", p1, tempDir.resolve("a1"),
				List.of(ReplTestSupport.peer("a2", "dc-a", p2), ReplTestSupport.peer("b1", "dc-b", p3)));
		propsA1.getReplication().getCrossDc().setLearners(List.of("b1"));
		propsA1.getReplication().getCrossDc().setVoters(List.of());
		final GridConfigurationProperties propsA2 = asyncProps("a2", "dc-a", p2, tempDir.resolve("a2"),
				List.of(ReplTestSupport.peer("a1", "dc-a", p1), ReplTestSupport.peer("b1", "dc-b", p3)));
		propsA2.getReplication().getCrossDc().setLearners(List.of("b1"));
		final GridConfigurationProperties propsB1 = asyncProps("b1", "dc-b", p3, tempDir.resolve("b1"),
				List.of(ReplTestSupport.peer("a1", "dc-a", p1), ReplTestSupport.peer("a2", "dc-a", p2)));
		propsB1.getReplication().getCrossDc().setLearners(List.of("b1"));

		a1 = new ReplicationCoordinator(propsA1);
		a2 = new ReplicationCoordinator(propsA2);
		b1 = new ReplicationCoordinator(propsB1);
		a1.start();
		a2.start();
		b1.start();
		waitSynced(a1, SYNC_MS);
		assertTrue(a1.isWriterEligible() || a2.isWriterEligible(), "dc-a must have a writer");

		final ReplicationCoordinator writer = a1.isWriterEligible() ? a1 : a2;
		engine = SqlBenchHelper.createEngine(8, writer);
		engine.execute("CREATE TABLE " + TABLE
				+ " (id INT PRIMARY KEY, number VARCHAR, status VARCHAR)");
		engine.execute("INSERT INTO " + TABLE + " (id, number, status) VALUES ("
				+ KEY_ID + ", '', 'seed')");

		final SqlSession session = engine.newSession();
		engine.execute(session, "BEGIN");
		engine.execute(session, "UPDATE " + TABLE + " SET number = number || ' ' || 't22' WHERE id = "
				+ KEY_ID);
		engine.execute(session, "COMMIT");

		final SqlResult row = engine.execute(
				"SELECT number, status FROM " + TABLE + " WHERE id = " + KEY_ID);
		assertEquals(1, row.rows().size());
		final String number = String.valueOf(row.rows().get(0)[0]);
		assertTrue(number.contains("t22"),
				() -> "ack'd append missing on writer body=" + number
						+ " status=" + row.rows().get(0)[1]
						+ " writer=" + writer.getNodeState().getNodeId());
	}

	private static GridConfigurationProperties asyncProps(
			String nodeId, String dc, int port, Path dataDir,
			List<ReplicationPeerProps> peers
	) {
		final GridConfigurationProperties props =
				ReplTestSupport.props(nodeId, "async-vis", dc, port, dataDir, peers);
		props.getReplication().getCrossDc().setMode(CrossDcMode.ASYNC_SHIP.name());
		props.getReplication().getCrossDc().setPhaseCoupling(false);
		props.getReplication().getOrchid().setMaxProposeInFlight(16);
		props.getReplication().getRepair().setHomologousEnabled(true);
		props.getReplication().getRepair().setReconcileIntervalMs(2_000);
		props.getReplication().getHa().setMaxStaleLag(0);
		props.getReplication().getSwarm().setEnabled(false);
		return props;
	}

	private static void waitSynced(ReplicationCoordinator c, long timeoutMs) throws Exception {
		final long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline && !c.getOrchidNode().isSynced()) {
			Thread.sleep(20L);
		}
		assertTrue(c.getOrchidNode().isSynced(), "orchid synced");
	}

	private static int freePort() throws Exception {
		try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
			return s.getLocalPort();
		}
	}
}