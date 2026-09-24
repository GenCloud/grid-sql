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

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.SqlSession;
import org.genfork.grid.sql.client.Connection;
import org.genfork.grid.sql.client.RemoteConnectionFactory;
import org.genfork.grid.sql.client.TxContext;
import org.genfork.grid.sql.netty.SqlServer;
import org.genfork.grid.replication.apply.ReplicaApplier;
import org.genfork.grid.store.TableStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TX SQL + PREPARE + multiplex TxContext on one TCP + shared locks / dirty WHERE / mid-flush restore.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public class SqlTxPrepareChannelTest {
	private SqlEngine engine;
	private SqlServer server;
	private RemoteConnectionFactory remoteFactory;

	@BeforeEach
	void setUp() throws Exception {
		engine = new SqlEngine(new TableCatalog(Files.createTempDirectory("grid-tx")), null, 4);
	}

	@AfterEach
	void tearDown() {
		if (remoteFactory != null) {
			remoteFactory.dispose();
			remoteFactory = null;
		}
		if (server != null) {
			server.close();
			server = null;
		}
	}

	@Test
	void beginInsertCommitVisible() {
		engine.execute("CREATE TABLE t (id INT PRIMARY KEY, v VARCHAR)");
		final SqlSession s = engine.newSession();
		engine.execute(s, "BEGIN");
		engine.execute(s, "INSERT INTO t (id, v) VALUES (1, 'a')");
		assertTrue(engine.execute(s, "SELECT v FROM t WHERE id = 1").rows().getFirst()[0].equals("a"));
		assertTrue(engine.execute(engine.newSession(), "SELECT v FROM t WHERE id = 1").rows().isEmpty());
		engine.execute(s, "COMMIT");
		assertEquals("a", engine.execute("SELECT v FROM t WHERE id = 1").rows().getFirst()[0]);
	}

	@Test
	void beginInsertRollbackInvisible() {
		engine.execute("CREATE TABLE t (id INT PRIMARY KEY, v VARCHAR)");
		final SqlSession s = engine.newSession();
		engine.execute(s, "BEGIN");
		engine.execute(s, "INSERT INTO t (id, v) VALUES (2, 'b')");
		engine.execute(s, "ROLLBACK");
		assertTrue(engine.execute("SELECT id FROM t WHERE id = 2").rows().isEmpty());
	}

	@Test
	void ddlRejectedInTx() {
		engine.execute("CREATE TABLE t (id INT PRIMARY KEY, v VARCHAR)");
		final SqlSession s = engine.newSession();
		engine.execute(s, "BEGIN");
		assertThrows(IllegalStateException.class,
				() -> engine.execute(s, "CREATE TABLE x (id INT PRIMARY KEY)"));
		engine.execute(s, "ROLLBACK");
	}

	@Test
	void prepareExecuteRoundtrip() {
		engine.execute("CREATE TABLE t (id INT PRIMARY KEY, v VARCHAR)");
		final SqlSession s = engine.newSession();
		engine.execute(s, "PREPARE ins AS INSERT INTO t (id, v) VALUES (?, ?)");
		engine.execute(s, "EXECUTE ins USING 3, 'prep'");
		assertEquals("prep", engine.execute("SELECT v FROM t WHERE id = 3").rows().getFirst()[0]);
		engine.execute(s, "DEALLOCATE ins");
	}

	@Test
	void remotePrepareExecuteRoundtrip() throws Exception {
		engine.execute("CREATE TABLE t (id INT PRIMARY KEY, v VARCHAR)");
		server = new SqlServer("127.0.0.1", 25453, engine, "u", "p");
		server.start();
		TimeUnit.MILLISECONDS.sleep(150);
		remoteFactory = new RemoteConnectionFactory("127.0.0.1", 25453, "u", "p", 8);
		final Connection c = remoteFactory.obtain().block();
		c.createStatement("PREPARE ins AS INSERT INTO t (id, v) VALUES (?, ?)").execute().blockLast();
		c.createStatement("EXECUTE ins USING 9, 'via-prep'").execute().blockLast();
		c.createStatement("PREPARE sel AS SELECT v FROM t WHERE id = ?").execute().blockLast();
		c.createStatement("EXECUTE sel USING 9").execute().blockLast();
		c.close().block();
		assertEquals("via-prep", engine.execute("SELECT v FROM t WHERE id = 9").rows().getFirst()[0]);
	}

	@Test
	void dirtyInsertVisibleUnderWhere() {
		engine.execute("CREATE TABLE t (id INT PRIMARY KEY, v VARCHAR)");
		final SqlSession s = engine.newSession();
		engine.execute(s, "BEGIN");
		engine.execute(s, "INSERT INTO t (id, v) VALUES (1, 'match')");
		engine.execute(s, "INSERT INTO t (id, v) VALUES (2, 'other')");
		final SqlResult r = engine.execute(s, "SELECT id FROM t WHERE v = 'match'");
		assertEquals(1, r.rows().size());
		assertEquals(1, r.rows().getFirst()[0]);
		engine.execute(s, "COMMIT");
		assertEquals(1, engine.execute("SELECT id FROM t WHERE v = 'match'").rows().size());
		assertEquals("other", engine.execute("SELECT v FROM t WHERE id = 2").rows().getFirst()[0]);
	}

	@Test
	void sharedLockBlocksCrossSessionUpdate() throws Exception {
		engine.execute("CREATE TABLE t (id INT PRIMARY KEY, v VARCHAR)");
		engine.execute("INSERT INTO t (id, v) VALUES (1, 'a')");
		final SqlSession s1 = engine.newSession();
		final SqlSession s2 = engine.newSession();
		engine.execute(s1, "BEGIN");
		final SqlResult u1 = engine.execute(s1, "UPDATE t SET v = 's1' WHERE id = 1");
		assertEquals(1L, u1.rowsAffected(), "s1 must stage+lock the PK row");
		final CountDownLatch entered = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		final AtomicReference<Exception> err = new AtomicReference<>();
		final Thread t = Thread.ofVirtual().name("lock-contender").start(() -> {
			try {
				engine.execute(s2, "BEGIN");
				entered.countDown();
				engine.execute(s2, "UPDATE t SET v = 's2' WHERE id = 1");
				engine.execute(s2, "COMMIT");
			} catch (Exception ex) {
				err.set(ex);
			} finally {
				release.countDown();
			}
		});
		assertTrue(entered.await(2, TimeUnit.SECONDS));
		boolean blocked = false;
		final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
		while (System.nanoTime() < deadline) {
			if (!t.isAlive()) {
				break;
			}
			final Thread.State st = t.getState();
			if (st == Thread.State.WAITING || st == Thread.State.TIMED_WAITING || st == Thread.State.RUNNABLE) {
				// parkNanos spin looks RUNNABLE; require still alive after a settle window
				TimeUnit.MILLISECONDS.sleep(50);
				if (t.isAlive()) {
					blocked = true;
					break;
				}
			}
			TimeUnit.MILLISECONDS.sleep(5);
		}
		assertTrue(blocked, "s2 must block on shared record lock");
		engine.execute(s1, "COMMIT");
		assertTrue(release.await(5, TimeUnit.SECONDS));
		if (err.get() != null) {
			throw err.get();
		}
		assertEquals("s2", engine.execute("SELECT v FROM t WHERE id = 1").rows().getFirst()[0]);
	}

	@Test
	void midFlushFailRestoresMultiShard() {
		engine.execute("CREATE TABLE t (id INT PRIMARY KEY, v VARCHAR)");
		final TableStore store = engine.catalog().getStore("t");
		final List<Integer> shardIds = new ArrayList<>();
		int a = -1;
		int b = -1;
		for (int id = 1; id < 200 && (a < 0 || b < 0); id++) {
			final byte[] key = store.keyBytesForPk(id);
			final int shard = store.shardOf(key);
			if (a < 0) {
				a = id;
				shardIds.add(shard);
			} else if (shard != shardIds.getFirst()) {
				b = id;
			}
		}
		assertTrue(a > 0 && b > 0, "need two keys on different shards");
		engine.execute("INSERT INTO t (id, v) VALUES (" + a + ", 'keep-a')");
		engine.execute("INSERT INTO t (id, v) VALUES (" + b + ", 'keep-b')");
		final SqlSession s = engine.newSession();
		engine.execute(s, "BEGIN");
		engine.execute(s, "UPDATE t SET v = 'new-a' WHERE id = " + a);
		engine.execute(s, "UPDATE t SET v = 'new-b' WHERE id = " + b);
		store.armTxFlushFailAfter(1);
		assertThrows(IllegalStateException.class, () -> engine.execute(s, "COMMIT"));
		assertEquals("keep-a", engine.execute("SELECT v FROM t WHERE id = " + a).rows().getFirst()[0]);
		assertEquals("keep-b", engine.execute("SELECT v FROM t WHERE id = " + b).rows().getFirst()[0]);
		assertTrue(!s.inTransaction());
	}

	@Test
	void remoteTxCommitViaTxContext() throws Exception {
		engine.execute("CREATE TABLE t (id INT PRIMARY KEY, v VARCHAR)");
		server = new SqlServer("127.0.0.1", 25450, engine, "u", "p");
		server.start();
		TimeUnit.MILLISECONDS.sleep(150);
		remoteFactory = new RemoteConnectionFactory("127.0.0.1", 25450, "u", "p", 8);
		final Connection c = remoteFactory.obtain().block();
		final TxContext tx = c.begin().block();
		tx.createStatement("INSERT INTO t (id, v) VALUES (4, 'remote')").execute().blockLast();
		tx.commit().block();
		c.close().block();
		assertEquals("remote", engine.execute("SELECT v FROM t WHERE id = 4").rows().getFirst()[0]);
	}

	@Test
	void oneTcpTwoConcurrentTxContexts() throws Exception {
		engine.execute("CREATE TABLE t (id INT PRIMARY KEY, v VARCHAR)");
		server = new SqlServer("127.0.0.1", 25451, engine, "u", "p");
		server.start();
		TimeUnit.MILLISECONDS.sleep(150);
		remoteFactory = new RemoteConnectionFactory("127.0.0.1", 25451, "u", "p", 8);
		final Connection c = remoteFactory.obtain().block();
		assertEquals(1, remoteFactory.activeChannels());
		final TxContext tx1 = c.begin().block();
		final TxContext tx2 = c.begin().block();
		assertEquals(1, remoteFactory.activeChannels());
		tx1.createStatement("INSERT INTO t (id, v) VALUES (10, 'a')").execute().blockLast();
		tx2.createStatement("INSERT INTO t (id, v) VALUES (11, 'b')").execute().blockLast();
		tx1.commit().block();
		tx2.commit().block();
		c.close().block();
		assertEquals("a", engine.execute("SELECT v FROM t WHERE id = 10").rows().getFirst()[0]);
		assertEquals("b", engine.execute("SELECT v FROM t WHERE id = 11").rows().getFirst()[0]);
	}

	@Test
	void nestedBeginOnSameTxContextRejected() throws Exception {
		engine.execute("CREATE TABLE t (id INT PRIMARY KEY, v VARCHAR)");
		server = new SqlServer("127.0.0.1", 25452, engine, "u", "p");
		server.start();
		TimeUnit.MILLISECONDS.sleep(150);
		remoteFactory = new RemoteConnectionFactory("127.0.0.1", 25452, "u", "p", 8);
		final Connection c = remoteFactory.obtain().block();
		final TxContext tx = c.begin().block();
		assertThrows(Exception.class,
				() -> tx.createStatement("BEGIN").execute().blockLast());
		tx.rollback().block();
		c.close().block();
	}

	@Test
	void discardOpenTxStagingSmoke() throws Exception {
		final org.genfork.grid.replication.ReplicationNodeState state =
				new org.genfork.grid.replication.ReplicationNodeState("sql-tx", "sql-tx", "dc-a", 1L);
		final java.nio.file.Path dir = Files.createTempDirectory("sql-tx-discard");
		try (org.genfork.grid.replication.log.OpLog opLog =
				     new org.genfork.grid.replication.log.OpLog(dir.resolve("oplog"), false)) {
			final org.genfork.grid.mem.GridScalableMap map = new org.genfork.grid.mem.GridScalableMap();
			final org.genfork.grid.mem.stage.GridEntriesProcessor processor =
					new org.genfork.grid.mem.stage.GridEntriesProcessor(0, map, null, null);
			final ReplicaApplier applier = new ReplicaApplier(state, opLog, shard -> processor, null, true);
			applier.apply(org.genfork.grid.replication.codec.OpLogCodec.withChecksum(
					new org.genfork.grid.replication.codec.ReplicationOp(
							"t", 0, 1L, org.genfork.grid.replication.codec.ReplicationOpType.TX_BEGIN,
							new byte[]{1}, null, 1L, 0L)), false);
			applier.apply(org.genfork.grid.replication.codec.OpLogCodec.withChecksum(
					new org.genfork.grid.replication.codec.ReplicationOp(
							"t", 0, 2L, org.genfork.grid.replication.codec.ReplicationOpType.UPSERT,
							new byte[]{10}, new byte[]{99}, 1L, 0L)), false);
			assertTrue(applier.hasOpenTx("t", 0));
			applier.discardOpenTxStaging();
			org.junit.jupiter.api.Assertions.assertNull(processor.get(new byte[]{10}));
		}
	}
}
