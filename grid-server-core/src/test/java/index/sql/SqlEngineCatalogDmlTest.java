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
import org.genfork.grid.sql.client.RemoteConnectionFactory;
import org.genfork.grid.sql.client.Result;
import org.genfork.grid.sql.netty.SqlServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Catalog + DML + remote loopback smoke.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public class SqlEngineCatalogDmlTest {
	private SqlEngine engine;
	private SqlServer server;
	private RemoteConnectionFactory remoteFactory;

	@BeforeEach
	void setUp() throws Exception {
		final TableCatalog catalog = new TableCatalog(Files.createTempDirectory("grid-catalog"));
		engine = new SqlEngine(catalog, null, 4);
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
	void updateMissingPkSeedsRow() {
		engine.execute("CREATE TABLE a (id INT PRIMARY KEY, number VARCHAR, status VARCHAR)");
		engine.execute("UPDATE a SET number = number || ' ' || 't0' WHERE id = 2");
		SqlResult sel = engine.execute("SELECT number FROM a WHERE id = 2");
		assertEquals(1, sel.rows().size());
		assertEquals(" t0", sel.rows().getFirst()[0]);
	}
	@Test
	void createInsertSelectUpdateDelete() {
		engine.execute("CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR, n INT)");
		SqlResult ins = engine.execute("INSERT INTO t (id, name, n) VALUES (1, 'a', 10)");
		assertEquals(1L, ins.rowsAffected());
		SqlResult sel = engine.execute("SELECT id, name, n FROM t WHERE id = 1");
		assertEquals(SqlResult.Kind.RESULT_SET, sel.kind());
		assertEquals(1, sel.rows().size());
		assertEquals(1, sel.rows().getFirst()[0]);
		assertEquals("a", sel.rows().getFirst()[1]);

		engine.execute("UPDATE t SET name = name || 'x' WHERE id = 1");
		sel = engine.execute("SELECT name FROM t WHERE id = 1");
		assertEquals("ax", sel.rows().getFirst()[0]);

		engine.execute("UPDATE t SET n = 99 WHERE id = 1");
		sel = engine.execute("SELECT n FROM t WHERE id = 1");
		assertEquals(99, ((Number) sel.rows().getFirst()[0]).intValue());

		engine.execute("DELETE FROM t WHERE id = 1");
		sel = engine.execute("SELECT id FROM t WHERE id = 1");
		assertTrue(sel.rows().isEmpty());
	}

	@Test
	void updateMixedRmwAndLiteral() {
		engine.execute("CREATE TABLE mix (id INT PRIMARY KEY, n INT, v VARCHAR)");
		engine.execute("INSERT INTO mix (id, n, v) VALUES (1, 10, 'a')");
		final SqlResult upd = engine.execute(
				"UPDATE mix SET n = n + 10, v = 'updated' WHERE id = 1");
		assertEquals(1L, upd.rowsAffected());
		final SqlResult sel = engine.execute("SELECT n, v FROM mix WHERE id = 1");
		assertEquals(20, ((Number) sel.rows().getFirst()[0]).intValue());
		assertEquals("updated", sel.rows().getFirst()[1]);
	}

	@Test
	void updateMixedRmwRequiresPkEquality() {
		engine.execute("CREATE TABLE mix2 (id INT PRIMARY KEY, bucket INT, n INT, v VARCHAR)");
		engine.execute("INSERT INTO mix2 (id, bucket, n, v) VALUES (1, 1, 0, 'a')");
		final IllegalArgumentException ex = assertThrows(
				IllegalArgumentException.class,
				() -> engine.execute("UPDATE mix2 SET n = n + 1, v = 'x' WHERE bucket = 1"));
		assertTrue(
				ex.getMessage().contains("PK") || ex.getMessage().contains("pk"),
				() -> "expected PK constraint message, got: " + ex.getMessage());
	}

	@Test
	void updateFromRejectsRmw() {
		engine.execute("CREATE TABLE tgt (id INT PRIMARY KEY, v VARCHAR)");
		engine.execute("CREATE TABLE src (id INT PRIMARY KEY, v VARCHAR)");
		engine.execute("INSERT INTO tgt (id, v) VALUES (1, 'a')");
		engine.execute("INSERT INTO src (id, v) VALUES (1, 'b')");
		final IllegalArgumentException ex = assertThrows(
				IllegalArgumentException.class,
				() -> engine.execute(
						"UPDATE tgt SET v = v || 'x' FROM src WHERE id = id"));
		assertTrue(ex.getMessage().contains("literal SET only"));
	}

	@Test
	void updateMixedInTransaction() {
		engine.execute("CREATE TABLE mixtx (id INT PRIMARY KEY, n INT, v VARCHAR)");
		engine.execute("INSERT INTO mixtx (id, n, v) VALUES (1, 5, 'old')");
		final org.genfork.grid.sql.SqlSession s = engine.newSession();
		engine.execute(s, "BEGIN");
		engine.execute(s, "UPDATE mixtx SET n = n + 5, v = 'tx' WHERE id = 1");
		assertEquals("old", engine.execute("SELECT v FROM mixtx WHERE id = 1").rows().getFirst()[0]);
		engine.execute(s, "COMMIT");
		final SqlResult sel = engine.execute("SELECT n, v FROM mixtx WHERE id = 1");
		assertEquals(10, ((Number) sel.rows().getFirst()[0]).intValue());
		assertEquals("tx", sel.rows().getFirst()[1]);
	}

	@Test
	void remoteLoopbackAuthExec() throws Exception {
		engine.execute("CREATE TABLE r (id INT PRIMARY KEY, v VARCHAR)");
		server = new SqlServer("127.0.0.1", 25432, engine, "u", "p");
		server.start();
		TimeUnit.MILLISECONDS.sleep(200);
		remoteFactory = new RemoteConnectionFactory("127.0.0.1", 25432, "u", "p");
		remoteFactory.obtain()
				.flatMapMany(conn -> conn.createStatement("INSERT INTO r (id, v) VALUES (2, 'remote')").execute()
						.flatMap(Result::getRowsUpdated))
				.blockLast();
		// reuse same channel
		remoteFactory.obtain()
				.flatMapMany(conn -> conn.createStatement("SELECT v FROM r WHERE id = 2").execute()
						.flatMap(r -> r.map((row, meta) -> String.valueOf(row.get(0)))))
				.blockFirst();
		final SqlResult sel = engine.execute("SELECT v FROM r WHERE id = 2");
		assertNotNull(sel.rows());
		assertEquals(1, sel.rows().size());
		assertEquals("remote", sel.rows().getFirst()[0]);
	}

	@Test
	void createIndexBackfillAndQuery() {
		engine.execute("CREATE TABLE ix (id INT PRIMARY KEY, bucket INT, v VARCHAR)");
		engine.execute("INSERT INTO ix (id, bucket, v) VALUES (1, 7, 'a')");
		engine.execute("INSERT INTO ix (id, bucket, v) VALUES (2, 7, 'b')");
		engine.execute("INSERT INTO ix (id, bucket, v) VALUES (3, 8, 'c')");
		engine.execute("CREATE INDEX idx_bucket ON ix (bucket)");
		SqlResult sel = engine.execute("SELECT id FROM ix WHERE bucket = 7");
		assertEquals(2, sel.rows().size());
		engine.execute("DROP INDEX idx_bucket ON ix");
	}

	@Test
	void bindInsertAndSelect() {
		engine.execute("CREATE TABLE b (id INT PRIMARY KEY, v VARCHAR)");
		engine.execute(engine.newSession(), "INSERT INTO b (id, v) VALUES (?, ?)", new Object[]{1, "ok"});
		SqlResult sel = engine.execute(engine.newSession(), "SELECT v FROM b WHERE id = ?", new Object[]{1});
		assertEquals("ok", sel.rows().getFirst()[0]);
	}

	@Test
	void filterUpdateDelete() {
		engine.execute("CREATE TABLE f (id INT PRIMARY KEY, bucket INT, v VARCHAR)");
		engine.execute("INSERT INTO f (id, bucket, v) VALUES (1, 1, 'a')");
		engine.execute("INSERT INTO f (id, bucket, v) VALUES (2, 1, 'b')");
		engine.execute("INSERT INTO f (id, bucket, v) VALUES (3, 2, 'c')");
		engine.execute("CREATE INDEX idx_f_bucket ON f (bucket)");
		SqlResult upd = engine.execute("UPDATE f SET v = 'x' WHERE bucket = 1");
		assertEquals(2L, upd.rowsAffected());
		SqlResult sel = engine.execute("SELECT v FROM f WHERE id = 1");
		assertEquals("x", sel.rows().getFirst()[0]);
		SqlResult del = engine.execute("DELETE FROM f WHERE bucket = 1");
		assertEquals(2L, del.rowsAffected());
		assertEquals(1, engine.execute("SELECT id FROM f WHERE id = 3").rows().size());
	}

	@Test
	void remoteBindLoopback() throws Exception {
		engine.execute("CREATE TABLE rb (id INT PRIMARY KEY, v VARCHAR)");
		server = new SqlServer("127.0.0.1", 25433, engine, "u", "p");
		server.start();
		TimeUnit.MILLISECONDS.sleep(200);
		remoteFactory = new RemoteConnectionFactory("127.0.0.1", 25433, "u", "p");
		remoteFactory.obtain()
				.flatMapMany(conn -> conn.createStatement("INSERT INTO rb (id, v) VALUES (?, ?)")
						.bind(0, 9)
						.bind(1, "remote-bind")
						.execute()
						.flatMap(Result::getRowsUpdated))
				.blockLast();
		final SqlResult sel = engine.execute("SELECT v FROM rb WHERE id = 9");
		assertEquals("remote-bind", sel.rows().getFirst()[0]);
	}

}
