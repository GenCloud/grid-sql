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
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.replication.apply.ReplicaApplier;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlSession;
import org.genfork.grid.store.TableStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQL TX + replication: OpLog domain = table#shard; mid-flush ABORT leaves map clean; hydrate discards open TX.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public class SqlTxReplicationMidCommitIT {

	@TempDir
	Path tempDir;

	@Test
	void commitWritesOpLogUnderTableShard() throws Exception {
		final Path dataDir = tempDir.resolve("sql-oplog");
		final int port = freePort();
		final ReplicationCoordinator coord = new ReplicationCoordinator(ReplTestSupport.props(
				"sql-tx-1", "sql-tx-cluster", "dc-a", port, dataDir, List.of()));
		coord.start();
		awaitWriter(coord);
		try {
			final SqlEngine engine = new SqlEngine(
					new TableCatalog(tempDir.resolve("cat1")), coord, 4);
			engine.execute("CREATE TABLE kv (id INT PRIMARY KEY, v VARCHAR)");
			final SqlSession s = engine.newSession();
			engine.execute(s, "BEGIN");
			engine.execute(s, "INSERT INTO kv (id, v) VALUES (1, 'x')");
			engine.execute(s, "COMMIT");
			assertEquals("x", engine.execute("SELECT v FROM kv WHERE id = 1").rows().getFirst()[0]);
			final TableStore store = engine.catalog().getStore("kv");
			final int shard = store.shardOf(store.keyBytesForPk(1));
			assertTrue(coord.getOpLog().lastSeq("kv", shard) >= 1L,
					"OpLog must record under table name domain");
			assertTrue(coord.getOpLog().streamKeys().stream().anyMatch(k -> k.equals("kv#" + shard)
					|| k.startsWith("kv#")));
		} finally {
			coord.stop();
		}
	}

	@Test
	void midFlushAbortLeavesMapClean() throws Exception {
		final Path dataDir = tempDir.resolve("sql-mid");
		final int port = freePort();
		final ReplicationCoordinator coord = new ReplicationCoordinator(ReplTestSupport.props(
				"sql-tx-2", "sql-tx-mid", "dc-a", port, dataDir, List.of()));
		coord.start();
		awaitWriter(coord);
		try {
			final SqlEngine engine = new SqlEngine(
					new TableCatalog(tempDir.resolve("cat2")), coord, 4);
			engine.execute("CREATE TABLE kv (id INT PRIMARY KEY, v VARCHAR)");
			engine.execute("INSERT INTO kv (id, v) VALUES (1, 'keep')");
			final TableStore store = engine.catalog().getStore("kv");
			final SqlSession s = engine.newSession();
			engine.execute(s, "BEGIN");
			engine.execute(s, "UPDATE kv SET v = 'dirty' WHERE id = 1");
			engine.execute(s, "INSERT INTO kv (id, v) VALUES (2, 'new')");
			store.armTxFlushFailAfter(1);
			assertThrows(IllegalStateException.class, () -> engine.execute(s, "COMMIT"));
			assertEquals("keep", engine.execute("SELECT v FROM kv WHERE id = 1").rows().getFirst()[0]);
			assertTrue(engine.execute("SELECT id FROM kv WHERE id = 2").rows().isEmpty());
			final ReplicaApplier applier = coord.applier("kv");
			if (applier != null) {
				assertTrue(!applier.hasOpenTx("kv", store.shardOf(store.keyBytesForPk(1))));
			}
		} finally {
			coord.stop();
		}
	}

	@Test
	void hydrateDiscardsIncompleteUnit() throws Exception {
		final Path dataDir = tempDir.resolve("sql-hydrate");
		final int port1 = freePort();
		final ReplicationCoordinator first = new ReplicationCoordinator(ReplTestSupport.props(
				"sql-tx-h", "sql-tx-hyd", "dc-a", port1, dataDir, List.of()));
		first.start();
		awaitWriter(first);
		final byte[] keyBytes;
		final int shard;
		try {
			final SqlEngine engine = new SqlEngine(
					new TableCatalog(tempDir.resolve("cat-h1")), first, 4);
			engine.execute("CREATE TABLE kv (id INT PRIMARY KEY, v VARCHAR)");
			final TableStore store = engine.catalog().getStore("kv");
			keyBytes = store.keyBytesForPk(9);
			shard = store.shardOf(keyBytes);
			final SqlSession s = engine.newSession();
			engine.execute(s, "BEGIN");
			engine.execute(s, "INSERT INTO kv (id, v) VALUES (9, 'ghost')");
			store.armTxFlushFailAfter(0);
			assertThrows(IllegalStateException.class, () -> engine.execute(s, "COMMIT"));
			assertTrue(engine.execute("SELECT id FROM kv WHERE id = 9").rows().isEmpty());
		} finally {
			first.stop();
		}

		final ReplicationCoordinator second = new ReplicationCoordinator(ReplTestSupport.props(
				"sql-tx-h", "sql-tx-hyd", "dc-a", freePort(), dataDir, List.of()));
		second.start();
		awaitWriter(second);
		try {
			final SqlEngine engine2 = new SqlEngine(
					new TableCatalog(tempDir.resolve("cat-h2")), second, 4);
			assertTrue(engine2.catalog().exists("kv"), "schema must hydrate from _catalog OpLog");
			assertTrue(engine2.execute("SELECT id FROM kv WHERE id = 9").rows().isEmpty(),
					"incomplete TX must not hydrate into map");
			final TableStore hydrated = engine2.catalog().getStore("kv");
			assertNull(hydrated.getCommittedBytes(keyBytes));
			final ReplicaApplier applier = second.applier("kv");
			if (applier != null) {
				assertTrue(!applier.hasOpenTx("kv", shard)
						|| applier.openTxStagedCount("kv", shard) == 0);
			}
		} finally {
			second.stop();
		}
	}

	private static void awaitWriter(ReplicationCoordinator coord) throws InterruptedException {
		final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline) {
			if (coord.isWriterEligible()) {
				return;
			}
			Thread.sleep(20);
		}
		assertTrue(coord.isWriterEligible(), "writer eligible");
	}

	private static int freePort() throws Exception {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}
}
