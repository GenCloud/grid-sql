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
import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.context.config.GridConfigurationProperties;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.store.TableStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A2: CREATE TABLE on writer appears in peer catalog/store via {@code ReplicationOpType.DDL}.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public class SqlCatalogDdlReplicationIT {

	@TempDir
	Path tempDir;

	@Test
	void createTableOnWriterAppearsOnPeer() throws Exception {
		final int portA = freePort();
		final int portB = freePort();
		final GridConfigurationProperties propsA = ReplTestSupport.props(
				"ddl-a", "ddl-catalog", "dc-a", portA, tempDir.resolve("a"),
				List.of(ReplTestSupport.peer("ddl-b", "dc-a", portB))
		);
		final GridConfigurationProperties propsB = ReplTestSupport.props(
				"ddl-b", "ddl-catalog", "dc-a", portB, tempDir.resolve("b"),
				List.of(ReplTestSupport.peer("ddl-a", "dc-a", portA))
		);

		final ReplicationCoordinator coordA = new ReplicationCoordinator(propsA);
		final ReplicationCoordinator coordB = new ReplicationCoordinator(propsB);
		coordA.start();
		coordB.start();

		final long liveDeadline = System.currentTimeMillis() + 8_000L;
		while (System.currentTimeMillis() < liveDeadline
				&& (coordA.getOrchidNode().livePeerCount() < 1 || coordB.getOrchidNode().livePeerCount() < 1)) {
			Thread.sleep(50L);
		}
		assertTrue(coordA.getOrchidNode().livePeerCount() >= 1);
		assertTrue(coordB.getOrchidNode().livePeerCount() >= 1);

		try {
			final SqlEngine engineA = new SqlEngine(
					new TableCatalog(tempDir.resolve("a-cat")),
					coordA,
					4
			);
			final SqlEngine engineB = new SqlEngine(
					new TableCatalog(tempDir.resolve("b-cat")),
					coordB,
					4
			);

			engineA.execute("CREATE TABLE ddl_peer (id INT PRIMARY KEY, v VARCHAR)");

			final long applyDeadline = System.currentTimeMillis() + 10_000L;
			while (System.currentTimeMillis() < applyDeadline
					&& engineB.catalog().getStore("ddl_peer") == null) {
				Thread.sleep(50L);
			}
			assertTrue(engineB.catalog().exists("ddl_peer"), "peer catalog missing table after DDL sync");
			final TableStore peerStore = engineB.catalog().getStore("ddl_peer");
			assertNotNull(peerStore, "peer TableStore not opened after CREATE TABLE sync");
			assertNotNull(peerStore.schema().pkColumn());
		} finally {
			coordA.stop();
			coordB.stop();
		}
	}

	@Test
	void createTableReachesThirdNode() throws Exception {
		final int portA = freePort();
		final int portB = freePort();
		final int portC = freePort();
		final GridConfigurationProperties propsA = ReplTestSupport.props(
				"ddl3-a", "ddl-catalog-3", "dc-a", portA, tempDir.resolve("3a"),
				List.of(
						ReplTestSupport.peer("ddl3-b", "dc-a", portB),
						ReplTestSupport.peer("ddl3-c", "dc-a", portC)
				)
		);
		final GridConfigurationProperties propsB = ReplTestSupport.props(
				"ddl3-b", "ddl-catalog-3", "dc-a", portB, tempDir.resolve("3b"),
				List.of(
						ReplTestSupport.peer("ddl3-a", "dc-a", portA),
						ReplTestSupport.peer("ddl3-c", "dc-a", portC)
				)
		);
		final GridConfigurationProperties propsC = ReplTestSupport.props(
				"ddl3-c", "ddl-catalog-3", "dc-a", portC, tempDir.resolve("3c"),
				List.of(
						ReplTestSupport.peer("ddl3-a", "dc-a", portA),
						ReplTestSupport.peer("ddl3-b", "dc-a", portB)
				)
		);

		final ReplicationCoordinator a = new ReplicationCoordinator(propsA);
		final ReplicationCoordinator b = new ReplicationCoordinator(propsB);
		final ReplicationCoordinator c = new ReplicationCoordinator(propsC);
		a.start();
		b.start();
		c.start();

		final long liveDeadline = System.currentTimeMillis() + 10_000L;
		while (System.currentTimeMillis() < liveDeadline
				&& (a.getOrchidNode().livePeerCount() < 2
				|| b.getOrchidNode().livePeerCount() < 2
				|| c.getOrchidNode().livePeerCount() < 2)) {
			Thread.sleep(50L);
		}
		assertTrue(a.getOrchidNode().livePeerCount() >= 2);

		try {
			final SqlEngine engineA = new SqlEngine(new TableCatalog(), a, 4);
			final SqlEngine engineB = new SqlEngine(new TableCatalog(), b, 4);
			final SqlEngine engineC = new SqlEngine(new TableCatalog(), c, 4);

			engineA.execute("CREATE TABLE t3 (id INT PRIMARY KEY, n INT)");

			final long applyDeadline = System.currentTimeMillis() + 12_000L;
			while (System.currentTimeMillis() < applyDeadline
					&& (engineB.catalog().getStore("t3") == null || engineC.catalog().getStore("t3") == null)) {
				Thread.sleep(50L);
			}
			assertTrue(engineB.catalog().exists("t3"));
			assertTrue(engineC.catalog().exists("t3"));
			assertNotNull(engineB.catalog().getStore("t3"));
			assertNotNull(engineC.catalog().getStore("t3"));
		} finally {
			a.stop();
			b.stop();
			c.stop();
		}
	}

	@Test
	void alterTableReplicatesToPeer() throws Exception {
		final int portA = freePort();
		final int portB = freePort();
		final GridConfigurationProperties propsA = ReplTestSupport.props(
				"ddl-alt-a", "ddl-alter", "dc-a", portA, tempDir.resolve("alt-a"),
				List.of(ReplTestSupport.peer("ddl-alt-b", "dc-a", portB))
		);
		final GridConfigurationProperties propsB = ReplTestSupport.props(
				"ddl-alt-b", "ddl-alter", "dc-a", portB, tempDir.resolve("alt-b"),
				List.of(ReplTestSupport.peer("ddl-alt-a", "dc-a", portA))
		);

		final ReplicationCoordinator coordA = new ReplicationCoordinator(propsA);
		final ReplicationCoordinator coordB = new ReplicationCoordinator(propsB);
		coordA.start();
		coordB.start();

		final long liveDeadline = System.currentTimeMillis() + 8_000L;
		while (System.currentTimeMillis() < liveDeadline
				&& (coordA.getOrchidNode().livePeerCount() < 1 || coordB.getOrchidNode().livePeerCount() < 1)) {
			Thread.sleep(50L);
		}
		assertTrue(coordA.getOrchidNode().livePeerCount() >= 1);

		try {
			final SqlEngine engineA = new SqlEngine(
					new TableCatalog(tempDir.resolve("alt-a-cat")),
					coordA,
					4
			);
			final SqlEngine engineB = new SqlEngine(
					new TableCatalog(tempDir.resolve("alt-b-cat")),
					coordB,
					4
			);

			engineA.execute("CREATE TABLE alter_peer (id INT PRIMARY KEY, v VARCHAR)");
			final long createDeadline = System.currentTimeMillis() + 10_000L;
			while (System.currentTimeMillis() < createDeadline && !engineB.catalog().exists("alter_peer")) {
				Thread.sleep(50L);
			}
			assertTrue(engineB.catalog().exists("alter_peer"));

			engineA.execute("ALTER TABLE alter_peer ADD COLUMN note VARCHAR");
			final long alterDeadline = System.currentTimeMillis() + 10_000L;
			while (System.currentTimeMillis() < alterDeadline
					&& engineB.catalog().requireSchema("alter_peer").columnCount() < 3) {
				Thread.sleep(50L);
			}
			assertEquals(3, engineB.catalog().requireSchema("alter_peer").columnCount());
			assertTrue(engineB.catalog().maxAppliedDdlEpoch()
					>= engineA.catalog().maxAppliedDdlEpoch() - 1L);
		} finally {
			coordA.stop();
			coordB.stop();
		}
	}

	private static int freePort() throws Exception {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}
}
