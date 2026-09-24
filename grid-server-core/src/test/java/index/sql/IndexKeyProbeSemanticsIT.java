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

import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.mem.index.GridCompositeIndex;
import org.genfork.grid.serial.SqlWireUtil;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.SqlSession;
import org.genfork.grid.store.TableStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Semantics coverage for allCommittedKeys removal: wire EQ probes, FOR UPDATE index gate,
 * MERGE/UPDATE FROM/ANALYZE streaming paths.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class IndexKeyProbeSemanticsIT {
	private static final int FK_CHILD_ROWS = 100;
	private static final int FK_CHILD_ROWS_SCALE = 100_000;
	private static final int MERGE_SOURCE_ROWS = 20;
	private static final int SNAPSHOT_ROWS = 50;
	private static final int BACKFILL_ROWS = 40;

	private SqlEngine engine;

	@BeforeEach
	void setUp() throws Exception {
		engine = new SqlEngine(new TableCatalog(Files.createTempDirectory("key-probe-it")), null, 4);
	}

	@Test
	void fkCascadeUsesChildEqIndex() {
		engine.execute("CREATE TABLE parent (id INT PRIMARY KEY)");
		engine.execute(
				"CREATE TABLE child (id INT PRIMARY KEY, parent_id INT, "
						+ "FOREIGN KEY (parent_id) REFERENCES parent (id) ON DELETE CASCADE)");
		engine.execute("INSERT INTO parent (id) VALUES (1)");
		for (int i = 0; i < FK_CHILD_ROWS; i++) {
			engine.execute("INSERT INTO child (id, parent_id) VALUES (" + i + ", 1)");
		}
		final TableStore child = engine.catalog().getStore("child");
		assertTrue(child.hasEqIndex(java.util.List.of("parent_id")));
		engine.execute("DELETE FROM parent WHERE id = 1");
		assertEquals(0, engine.execute("SELECT id FROM child").rows().size());
	}

	@Test
	void fkCascadeScaleIndexedChildEq() {
		engine.execute("CREATE TABLE parent_s (id INT PRIMARY KEY)");
		engine.execute(
				"CREATE TABLE child_s (id INT PRIMARY KEY, parent_id INT, "
						+ "FOREIGN KEY (parent_id) REFERENCES parent_s (id) ON DELETE CASCADE)");
		engine.execute("INSERT INTO parent_s (id) VALUES (1)");
		final TableStore child = engine.catalog().getStore("child_s");
		for (int i = 0; i < FK_CHILD_ROWS_SCALE; i++) {
			child.putIndexed(i, 1);
		}
		assertTrue(child.hasEqIndex(java.util.List.of("parent_id")));
		engine.execute("DELETE FROM parent_s WHERE id = 1");
		assertEquals(0, engine.execute("SELECT id FROM child_s").rows().size());
	}

	@Test
	void forUpdateRejectsUnindexedWhere() {
		engine.execute("CREATE TABLE box (id INT PRIMARY KEY, flag INT)");
		engine.execute("INSERT INTO box VALUES (1, 1), (2, 2)");
		final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> engine.execute("SELECT id FROM box WHERE flag = 1 FOR UPDATE"));
		assertTrue(ex.getMessage().contains(GridCompositeIndex.MSG_WHERE_REQUIRES_USABLE_INDEX),
				ex.getMessage());
	}

	@Test
	void forUpdateIndexedSkipLockedWithoutLimit() {
		engine.execute("CREATE TABLE outbox (id INT PRIMARY KEY, state VARCHAR)");
		engine.execute("CREATE INDEX outbox_state ON outbox (state)");
		engine.execute("INSERT INTO outbox VALUES (1, 'new'), (2, 'new'), (3, 'done')");
		final SqlSession first = engine.newSession();
		final SqlSession second = engine.newSession();
		engine.execute(first, "BEGIN");
		engine.execute(second, "BEGIN");
		try {
			final SqlResult locked = engine.execute(first,
					"SELECT id FROM outbox WHERE state = 'new' FOR UPDATE");
			assertEquals(2, locked.rows().size());
			final SqlResult skipped = engine.execute(second,
					"SELECT id FROM outbox WHERE state = 'new' FOR UPDATE SKIP LOCKED");
			assertEquals(0, skipped.rows().size());
		} finally {
			engine.execute(first, "ROLLBACK");
			engine.execute(second, "ROLLBACK");
		}
	}

	@Test
	void lookupEqKeysFindsStrictUnique() {
		engine.execute("CREATE TABLE kv (id INT PRIMARY KEY, email VARCHAR)");
		engine.execute("CREATE UNIQUE INDEX kv_email ON kv (email)");
		engine.execute("INSERT INTO kv VALUES (1, 'a@x'), (2, 'b@x')");
		final TableStore store = engine.catalog().getStore("kv");
		final byte[] wire = SqlWireUtil.toGenericArray("b@x");
		final java.util.List<byte[]> keys = store.lookupEqKeys(java.util.List.of("email"), new byte[][]{wire});
		assertEquals(1, keys.size());
		assertEquals(2, engine.execute("SELECT id FROM kv WHERE email = 'b@x'").rows().getFirst()[0]);
	}

	@Test
	void mergeOnConflictUniqueHitAndMiss() {
		engine.execute("CREATE TABLE tgt (id INT PRIMARY KEY, email VARCHAR, val VARCHAR)");
		engine.execute("CREATE UNIQUE INDEX tgt_email ON tgt (email)");
		engine.execute("INSERT INTO tgt VALUES (1, 'a@x', 'old')");
		engine.execute(
				"INSERT INTO tgt (id, email, val) VALUES (2, 'a@x', 'new') "
						+ "ON CONFLICT (email) DO UPDATE SET val = 'hit'");
		assertEquals("hit", engine.execute("SELECT val FROM tgt WHERE id = 1").rows().getFirst()[0]);
		engine.execute(
				"INSERT INTO tgt (id, email, val) VALUES (3, 'b@x', 'miss') "
						+ "ON CONFLICT (email) DO UPDATE SET val = 'x'");
		assertEquals("miss", engine.execute("SELECT val FROM tgt WHERE email = 'b@x'").rows().getFirst()[0]);
	}

	@Test
	void mergeUsingTableStreamsSource() {
		engine.execute("CREATE TABLE src (id INT PRIMARY KEY, val VARCHAR)");
		engine.execute("CREATE TABLE tgt (id INT PRIMARY KEY, val VARCHAR)");
		for (int i = 0; i < MERGE_SOURCE_ROWS; i++) {
			engine.execute("INSERT INTO src VALUES (" + i + ", 's" + i + "')");
		}
		engine.execute(
				"MERGE INTO tgt USING src ON id = id "
						+ "WHEN MATCHED THEN UPDATE SET val = 'merged' "
						+ "WHEN NOT MATCHED THEN INSERT (id, val) VALUES (0, 'x')");
		assertEquals(MERGE_SOURCE_ROWS, engine.execute("SELECT id FROM tgt").rows().size());
	}

	@Test
	void txSnapshotUsesIndexedFilterPath() {
		engine.execute("CREATE TABLE snap (id INT PRIMARY KEY, state VARCHAR)");
		engine.execute("CREATE INDEX snap_state ON snap (state)");
		for (int i = 0; i < SNAPSHOT_ROWS; i++) {
			final String st = i % 2 == 0 ? "new" : "done";
			engine.execute("INSERT INTO snap VALUES (" + i + ", '" + st + "')");
		}
		final SqlSession s = engine.newSession();
		engine.execute(s, "BEGIN");
		try {
			final SqlResult r = engine.execute(s, "SELECT id FROM snap WHERE state = 'new'");
			assertEquals(SNAPSHOT_ROWS / 2, r.rows().size());
		} finally {
			engine.execute(s, "ROLLBACK");
		}
	}

	@Test
	void createIndexBackfillRewritesPointers() {
		engine.execute("CREATE TABLE hist (id INT PRIMARY KEY, flag INT)");
		for (int i = 0; i < BACKFILL_ROWS; i++) {
			engine.execute("INSERT INTO hist VALUES (" + i + ", " + (i % 3) + ")");
		}
		engine.execute("CREATE INDEX hist_flag ON hist (flag)");
		final TableStore store = engine.catalog().getStore("hist");
		assertTrue(store.hasEqIndex(java.util.List.of("flag")));
		final byte[] wire = SqlWireUtil.toGenericArray(1);
		assertEquals(BACKFILL_ROWS / 3 + (BACKFILL_ROWS % 3 > 1 ? 1 : 0),
				store.lookupEqKeys(java.util.List.of("flag"), new byte[][]{wire}).size());
	}

	@Test
	void updateFromRequiresTargetJoinIndex() {
		engine.execute("CREATE TABLE src (id INT PRIMARY KEY, ref INT)");
		engine.execute("CREATE TABLE tgt (id INT PRIMARY KEY, ref INT, val VARCHAR)");
		engine.execute("INSERT INTO src VALUES (1, 10)");
		engine.execute("INSERT INTO tgt VALUES (100, 10, 'old')");
		final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> engine.execute(
						"UPDATE tgt SET val = 'new' FROM src WHERE tgt.ref = src.ref"));
		assertTrue(ex.getMessage().contains(GridCompositeIndex.MSG_EQ_REQUIRES_INDEX), ex.getMessage());
		engine.execute("CREATE INDEX tgt_ref ON tgt (ref)");
		engine.execute("UPDATE tgt SET val = 'new' FROM src WHERE tgt.ref = src.ref");
		assertEquals("new", engine.execute("SELECT val FROM tgt WHERE id = 100").rows().getFirst()[0]);
	}

	@Test
	void analyzeStreamsPrimaryKeys() {
		engine.execute("CREATE TABLE hist2 (id INT PRIMARY KEY, flag INT)");
		engine.execute("INSERT INTO hist2 VALUES (1, 7), (2, 7), (3, 9)");
		engine.execute("ANALYZE hist2");
		assertTrue(engine.catalog().getAnalyzeStats("hist2").rowCount() >= 3L);
	}

	@Test
	void forEachPrimaryKeyCountsRows() {
		engine.execute("CREATE TABLE t (id INT PRIMARY KEY)");
		engine.execute("INSERT INTO t VALUES (1), (2), (3)");
		final TableStore store = engine.catalog().getStore("t");
		final AtomicInteger n = new AtomicInteger();
		store.forEachPrimaryKey(key -> n.incrementAndGet());
		assertEquals(3, n.get());
		assertTrue(store.isSelectFullyIndexed("SELECT id FROM t"));
		assertTrue(store.isSelectFullyIndexed("SELECT id FROM t WHERE id = 1"));
	}
}