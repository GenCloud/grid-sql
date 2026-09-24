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

import index.unit.replication.ReplTestSupport;
import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.context.config.GridConfigurationProperties;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.serial.LogicalFieldCursor;
import org.genfork.grid.serial.PrimaryKeyCodec;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.store.TableStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQL UPDATE concat/add — concurrent and replicated, no lost-update (SqlEngine path).
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
public class SqlUpdateModifyIT {

	@TempDir
	Path tempDir;

	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(tempDir.resolve("cat")), null, 4);
		engine.execute("CREATE TABLE append_domain (id INT PRIMARY KEY, number VARCHAR, score INT)");
	}

	@AfterEach
	void tearDown() {
		engine = null;
	}

	private static Object cell(SqlResult r, int row, int col) {
		assertEquals(1, r.rows().size());
		return r.rows().get(row)[col];
	}

	@Test
	void sqlMultiAssignConcatAndAdd() {
		org.genfork.grid.codec.duplex.DuplexCodecSupport.configure(
				true, org.genfork.grid.codec.duplex.DuplexRepairMode.REBUILD_DATA_FROM_PARITY,
				true, true, 1L, true, true);
		try {
			engine.execute("INSERT INTO append_domain (id, number, score) VALUES (8, '', 0)");
			engine.execute(
					"UPDATE append_domain SET number = number || ' x', score = score + 2 WHERE id = 8");
			engine.execute(
					"UPDATE append_domain SET number = number || ' y', score = score + 3 WHERE id = 8");
			final SqlResult row = engine.execute("SELECT number, score FROM append_domain WHERE id = 8");
			final String number = String.valueOf(cell(row, 0, 0));
			assertTrue(number.contains("x") && number.contains("y"));
			assertEquals(5, ((Number) cell(row, 0, 1)).intValue());
		} finally {
			org.genfork.grid.codec.duplex.DuplexCodecSupport.configure(
					false, org.genfork.grid.codec.duplex.DuplexRepairMode.REBUILD_DATA_FROM_PARITY,
					true, true, 0L, true, true);
		}
	}

	@Test
	void sqlConcatWithDuplexMapValues() {
		org.genfork.grid.codec.duplex.DuplexCodecSupport.configure(
				true, org.genfork.grid.codec.duplex.DuplexRepairMode.REBUILD_DATA_FROM_PARITY,
				true, true, 1L, true, true);
		try {
			engine.execute("INSERT INTO append_domain (id, number, score) VALUES (5, '', 0)");
			engine.execute("UPDATE append_domain SET number = number || ' ' || 'a' WHERE id = 5");
			engine.execute("UPDATE append_domain SET number = number || ' ' || 'b' WHERE id = 5");
			final SqlResult row = engine.execute("SELECT number FROM append_domain WHERE id = 5");
			final String number = String.valueOf(cell(row, 0, 0));
			assertTrue(number.contains("a"));
			assertTrue(number.contains("b"));
		} finally {
			org.genfork.grid.codec.duplex.DuplexCodecSupport.configure(
					false, org.genfork.grid.codec.duplex.DuplexRepairMode.REBUILD_DATA_FROM_PARITY,
					true, true, 0L, true, true);
		}
	}

	@Test
	void concurrentSqlConcatPreservesAllTokens() throws Exception {
		engine.execute("INSERT INTO append_domain (id, number, score) VALUES (1, '', 0)");

		final int n = 20;
		final ExecutorService pool = Executors.newFixedThreadPool(8);
		final CountDownLatch start = new CountDownLatch(1);
		final List<Future<?>> futures = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			final String token = "t" + i;
			futures.add(pool.submit(() -> {
				start.await(5, TimeUnit.SECONDS);
				engine.execute(
						"UPDATE append_domain SET number = number || ' ' || '" + token + "' WHERE id = 1");
				return null;
			}));
		}
		start.countDown();
		for (Future<?> f : futures) {
			f.get(30, TimeUnit.SECONDS);
		}
		pool.shutdown();

		final SqlResult row = engine.execute("SELECT number FROM append_domain WHERE id = 1");
		final String number = String.valueOf(cell(row, 0, 0));
		final List<String> parts = Arrays.asList(number.trim().split("\\s+"));
		assertEquals(n, parts.size(), "lost-update under concurrent SQL concat: " + number);
		for (int i = 0; i < n; i++) {
			assertTrue(parts.contains("t" + i), "missing t" + i);
		}
	}

	@Test
	void sqlIntAdd() {
		engine.execute("INSERT INTO append_domain (id, number, score) VALUES (3, '', 0)");
		engine.execute("UPDATE append_domain SET score = score + 5 WHERE id = 3");
		engine.execute("UPDATE append_domain SET score = score + 7 WHERE id = 3");
		final SqlResult row = engine.execute("SELECT score FROM append_domain WHERE id = 3");
		assertEquals(12, ((Number) cell(row, 0, 0)).intValue());
	}

	@Test
	void twoNodeSqlConcatVisibleOnPeer() throws Exception {
		final int portA = freePort();
		final int portB = freePort();
		final GridConfigurationProperties propsA = ReplTestSupport.props(
				"app-a", "app-cluster", "dc-a", portA, tempDir.resolve("a"),
				List.of(ReplTestSupport.peer("app-b", "dc-a", portB)));
		final GridConfigurationProperties propsB = ReplTestSupport.props(
				"app-b", "app-cluster", "dc-a", portB, tempDir.resolve("b"),
				List.of(ReplTestSupport.peer("app-a", "dc-a", portA)));
		final ReplicationCoordinator a = new ReplicationCoordinator(propsA);
		final ReplicationCoordinator b = new ReplicationCoordinator(propsB);

		final SqlEngine engineA = new SqlEngine(
				new TableCatalog(tempDir.resolve("a-cat")), a, 4);
		final SqlEngine engineB = new SqlEngine(
				new TableCatalog(tempDir.resolve("b-cat")), b, 4);

		a.start();
		b.start();
		try {
			final long deadline = System.currentTimeMillis() + 15_000;
			while (System.currentTimeMillis() < deadline
					&& (!a.getOrchidNode().isSynced() || !b.getOrchidNode().isSynced()
					|| (!a.isWriterEligible() && !b.isWriterEligible()))) {
				Thread.sleep(50);
			}
			assertTrue(a.isWriterEligible() || b.isWriterEligible());
			final SqlEngine writer = a.isWriterEligible() ? engineA : engineB;
			final SqlEngine peer = a.isWriterEligible() ? engineB : engineA;

			writer.execute("CREATE TABLE append_domain (id INT PRIMARY KEY, number VARCHAR, score INT)");
			final long ddlDeadline = System.currentTimeMillis() + 10_000;
			while (System.currentTimeMillis() < ddlDeadline && !peer.catalog().exists("append_domain")) {
				Thread.sleep(50);
			}
			assertTrue(peer.catalog().exists("append_domain"));

			writer.execute("INSERT INTO append_domain (id, number, score) VALUES (7, '', 0)");
			writer.execute("UPDATE append_domain SET number = number || ' ' || 'alpha' WHERE id = 7");
			writer.execute("UPDATE append_domain SET number = number || ' ' || 'beta' WHERE id = 7");

			// Peer SQL SELECT is gate-closed (proposer-only). Assert apply via committed wire bytes.
			final TableStore peerStore = peer.catalog().getStore("append_domain");
			final TableSchema peerSchema = peerStore.schema();
			final byte[] pk = PrimaryKeyCodec.encodeArgument(peerSchema, 7);
			final long waitUntil = System.currentTimeMillis() + 10_000;
			String onPeer = null;
			while (System.currentTimeMillis() < waitUntil) {
				final byte[] blob = peerStore.getCommittedBytes(pk);
				if (blob != null) {
					final LogicalFieldCursor cursor = LogicalFieldCursor.open(peerSchema, blob);
					final Object number = cursor.project(new int[]{peerSchema.column("number").ordinal()})[0];
					if (number != null) {
						onPeer = String.valueOf(number);
						if (onPeer.contains("beta")) {
							break;
						}
					}
				}
				Thread.sleep(50);
			}
			assertTrue(onPeer != null);
			assertTrue(onPeer.contains("alpha"));
			assertTrue(onPeer.contains("beta"));
		} finally {
			try {
				a.stop();
			} catch (Exception ignored) {
			}
			try {
				b.stop();
			} catch (Exception ignored) {
			}
		}
	}

	private static int freePort() throws Exception {
		try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
			return s.getLocalPort();
		}
	}
}
