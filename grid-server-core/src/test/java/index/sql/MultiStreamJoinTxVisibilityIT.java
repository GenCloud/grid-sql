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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import index.unit.replication.ReplTestSupport;
import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.context.config.GridConfigurationProperties;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.SqlSession;

/**
 * Multi-table TX (parent+child) must remain visible after COMMIT for JOIN reads.
 * <p>
 * Characterization for Elle G2 on Jepsen join-shards: envelope flush must install
 * each stream via its owning domain applier (not the last TX_COMMIT stream).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
public class MultiStreamJoinTxVisibilityIT {

	private static final int PARENT_ID_OFFSET = 100;
	private static final int KEY_FROM = 2;
	private static final int KEY_TO = 32;
	private static final int SHARDS = 8;

	@TempDir
	Path tempDir;

	@Test
	void parentChildTxConcatVisibleOnJoinRead() throws Exception {
		final int portA = freePort();
		final int portB = freePort();
		final GridConfigurationProperties propsA = ReplTestSupport.props(
				"join-vis-a", "join-vis", "dc-a", portA, tempDir.resolve("a"),
				List.of(ReplTestSupport.peer("join-vis-b", "dc-a", portB)));
		final GridConfigurationProperties propsB = ReplTestSupport.props(
				"join-vis-b", "join-vis", "dc-a", portB, tempDir.resolve("b"),
				List.of(ReplTestSupport.peer("join-vis-a", "dc-a", portA)));

		final ReplicationCoordinator coordA = new ReplicationCoordinator(propsA);
		final ReplicationCoordinator coordB = new ReplicationCoordinator(propsB);
		coordA.start();
		coordB.start();
		try {
			waitSynced(coordA, coordB);
			final SqlEngine engineA = new SqlEngine(
					new TableCatalog(tempDir.resolve("a-cat")), coordA, SHARDS);
			final SqlEngine engineB = new SqlEngine(
					new TableCatalog(tempDir.resolve("b-cat")), coordB, SHARDS);
			final SqlEngine writer = coordA.isWriterEligible() ? engineA : engineB;
			final ReplicationCoordinator writerCoord = coordA.isWriterEligible() ? coordA : coordB;

			writer.execute("""
					CREATE TABLE IF NOT EXISTS jepsen_parent (
					  id INT PRIMARY KEY,
					  name VARCHAR
					)
					""");
			writer.execute("""
					CREATE TABLE IF NOT EXISTS jepsen_child (
					  id INT PRIMARY KEY,
					  parent_id INT,
					  number VARCHAR,
					  status VARCHAR
					)
					""");
			for (int id = KEY_FROM; id <= KEY_TO; id++) {
				final int parentId = id + PARENT_ID_OFFSET;
				writer.execute("INSERT INTO jepsen_parent (id, name) VALUES ("
						+ parentId + ", 'p" + id + "')");
				writer.execute("INSERT INTO jepsen_child (id, parent_id, number, status) VALUES ("
						+ id + ", " + parentId + ", '', 'seed')");
			}

			for (int id = KEY_FROM; id <= KEY_TO; id++) {
				final String token = "t" + id;
				final int parentId = id + PARENT_ID_OFFSET;
				final SqlSession session = writer.newSession();
				writer.execute(session, "BEGIN");
				writer.execute(session, "UPDATE jepsen_parent SET name = 'p" + id
						+ "' WHERE id = " + parentId);
				writer.execute(session, "UPDATE jepsen_child SET number = number || ' ' || '"
						+ token + "' WHERE id = " + id);
				writer.execute(session, "COMMIT");

				assertTrue(writerCoord.isWriterEligible(), "writer must stay eligible");
				final SqlResult read = writer.execute(
						"SELECT c.number, c.status, p.id FROM jepsen_child c "
								+ "LEFT OUTER JOIN jepsen_parent p ON c.parent_id = p.id "
								+ "WHERE c.id = " + id);
				assertEquals(1, read.rows().size(), "missing join row for id=" + id);
				final Object[] row = read.rows().getFirst();
				assertNotNull(row[2], "parent missing for id=" + id);
				final String number = row[0] == null ? "" : String.valueOf(row[0]).trim();
				assertTrue(number.contains(token),
						"lost multi-stream TX concat id=" + id + " number=" + number);
			}
		} finally {
			coordA.stop();
			coordB.stop();
		}
	}

	private static void waitSynced(ReplicationCoordinator a, ReplicationCoordinator b)
			throws InterruptedException {
		final long deadline = System.currentTimeMillis() + 15_000L;
		while (System.currentTimeMillis() < deadline
				&& (a.getOrchidNode().livePeerCount() < 1
				|| b.getOrchidNode().livePeerCount() < 1
				|| (!a.isWriterEligible() && !b.isWriterEligible()))) {
			Thread.sleep(50L);
		}
		assertTrue(a.isWriterEligible() || b.isWriterEligible());
	}

	private static int freePort() throws Exception {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}
}