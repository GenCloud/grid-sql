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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * N2a: JOIN WHERE/ORDER/LIMIT, multi INNER, LEFT/RIGHT/FULL OUTER; N2b aggregates; N3 schema recovery/DROP;
 * N3b NOT NULL / UNIQUE. Plan F outer-join depth.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class SqlJoinAggSchemaConstraintTest {
	private SqlEngine engine;

	@BeforeEach
	void setUp() {
engine = new SqlEngine(new TableCatalog(), null, 4);
	}

	@Test
	void joinWhereOrderLimit() {
		engine.execute("CREATE TABLE a (id INT PRIMARY KEY, k INT, score INT)");
		engine.execute("CREATE TABLE b (id INT PRIMARY KEY, k INT)");
		engine.execute("INSERT INTO a VALUES (1, 7, 30)");
		engine.execute("INSERT INTO a VALUES (2, 7, 10)");
		engine.execute("INSERT INTO a VALUES (3, 9, 20)");
		engine.execute("INSERT INTO b VALUES (10, 7)");
		engine.execute("INSERT INTO b VALUES (11, 9)");
		final SqlResult r = engine.execute(
				"SELECT score FROM a JOIN b ON k = k WHERE score > 15 ORDER BY score ASC LIMIT 1");
		assertEquals(1, r.rows().size());
		assertEquals(20, r.rows().getFirst()[0]);
	}

	@Test
	void multiInnerJoinChain() {
		engine.execute("CREATE TABLE t1 (id INT PRIMARY KEY, x INT)");
		engine.execute("CREATE TABLE t2 (id INT PRIMARY KEY, x INT, y INT)");
		engine.execute("CREATE TABLE t3 (id INT PRIMARY KEY, y INT)");
		engine.execute("INSERT INTO t1 VALUES (1, 5)");
		engine.execute("INSERT INTO t2 VALUES (2, 5, 8)");
		engine.execute("INSERT INTO t3 VALUES (3, 8)");
		final SqlResult r = engine.execute(
				"SELECT * FROM t1 JOIN t2 ON x = x JOIN t3 ON y = y");
		assertEquals(1, r.rows().size());
	}

	@Test
	void leftOuterJoinNullPadsRight() {
		engine.execute("CREATE TABLE l (id INT PRIMARY KEY, k INT)");
		engine.execute("CREATE TABLE r (id INT PRIMARY KEY, k INT)");
		engine.execute("INSERT INTO l VALUES (1, 1)");
		engine.execute("INSERT INTO l VALUES (2, 2)");
		engine.execute("INSERT INTO r VALUES (10, 1)");
		final SqlResult res = engine.execute("SELECT * FROM l LEFT OUTER JOIN r ON k = k");
		assertEquals(2, res.rows().size());
		boolean sawNullPad = false;
		for (Object[] row : res.rows()) {
			if (row[0].equals(2)) {
				assertNull(row[2]);
				assertNull(row[3]);
				sawNullPad = true;
			}
		}
		assertTrue(sawNullPad);
	}

	@Test
	void rightOuterJoinNullPadsLeft() {
		engine.execute("CREATE TABLE rl (id INT PRIMARY KEY, k INT)");
		engine.execute("CREATE TABLE rr (id INT PRIMARY KEY, k INT)");
		engine.execute("INSERT INTO rl VALUES (1, 1)");
		engine.execute("INSERT INTO rr VALUES (10, 1)");
		engine.execute("INSERT INTO rr VALUES (20, 2)");
		final SqlResult res = engine.execute("SELECT * FROM rl RIGHT OUTER JOIN rr ON k = k");
		assertEquals(2, res.rows().size());
		boolean sawNullPad = false;
		for (Object[] row : res.rows()) {
			if (row[3].equals(2)) {
				assertNull(row[0]);
				assertNull(row[1]);
				sawNullPad = true;
			}
		}
		assertTrue(sawNullPad);
	}

	@Test
	void fullOuterJoinPadsBothSides() {
		engine.execute("CREATE TABLE fl (id INT PRIMARY KEY, k INT)");
		engine.execute("CREATE TABLE fr (id INT PRIMARY KEY, k INT)");
		engine.execute("INSERT INTO fl VALUES (1, 1)");
		engine.execute("INSERT INTO fl VALUES (2, 2)");
		engine.execute("INSERT INTO fr VALUES (10, 1)");
		engine.execute("INSERT INTO fr VALUES (30, 3)");
		final SqlResult res = engine.execute("SELECT * FROM fl FULL OUTER JOIN fr ON k = k");
		assertEquals(3, res.rows().size());
		boolean leftOnly = false;
		boolean rightOnly = false;
		boolean matched = false;
		for (Object[] row : res.rows()) {
			if (row[0] != null && row[0].equals(2)) {
				assertNull(row[2]);
				leftOnly = true;
			} else if (row[2] != null && row[2].equals(30)) {
				assertNull(row[0]);
				rightOnly = true;
			} else if (row[0] != null && row[0].equals(1)) {
				assertEquals(10, row[2]);
				matched = true;
			}
		}
		assertTrue(matched);
		assertTrue(leftOnly);
		assertTrue(rightOnly);
	}

	@Test
	void multiJoinChainWithOuter() {
		engine.execute("CREATE TABLE m1 (id INT PRIMARY KEY, x INT)");
		engine.execute("CREATE TABLE m2 (id INT PRIMARY KEY, x INT, y INT)");
		engine.execute("CREATE TABLE m3 (id INT PRIMARY KEY, y INT)");
		engine.execute("INSERT INTO m1 VALUES (1, 5)");
		engine.execute("INSERT INTO m1 VALUES (2, 9)");
		engine.execute("INSERT INTO m2 VALUES (10, 5, 8)");
		engine.execute("INSERT INTO m3 VALUES (100, 8)");
		final SqlResult res = engine.execute(
				"SELECT * FROM m1 LEFT OUTER JOIN m2 ON x = x JOIN m3 ON y = y");
		assertEquals(1, res.rows().size());
		assertEquals(1, res.rows().getFirst()[0]);
	}

	@Test
	void aggregateCountGroupBy() {
		engine.execute("CREATE TABLE g (id INT PRIMARY KEY, bucket INT, score INT)");
		engine.execute("INSERT INTO g VALUES (1, 1, 10)");
		engine.execute("INSERT INTO g VALUES (2, 1, 20)");
		engine.execute("INSERT INTO g VALUES (3, 2, 5)");
		final SqlResult counts = engine.execute("SELECT COUNT(*) FROM g GROUP BY bucket");
		assertEquals(2, counts.rows().size());
		final SqlResult sum = engine.execute("SELECT SUM(score) FROM g GROUP BY bucket");
		assertEquals(2, sum.rows().size());
		final SqlResult avg = engine.execute("SELECT AVG(score) FROM g GROUP BY bucket");
		assertEquals(2, avg.rows().size());
		final SqlResult total = engine.execute("SELECT COUNT(*) FROM g");
		assertEquals(1, total.rows().size());
		assertEquals(3.0d, ((Number) total.rows().getFirst()[0]).doubleValue(), 0.001);
	}

	@Test
	void dropSchemaRestrict() {
		final SqlSession session = engine.newSession();
		engine.execute(session, "CREATE SCHEMA app");
		engine.execute(session, "SET SCHEMA app");
		engine.execute(session, "CREATE TABLE t (id INT PRIMARY KEY)");
		assertThrows(IllegalStateException.class,
				() -> engine.execute("DROP SCHEMA app RESTRICT"));
		engine.execute(session, "DROP TABLE t");
		engine.execute("DROP SCHEMA app RESTRICT");
		assertThrows(IllegalArgumentException.class,
				() -> engine.execute("SET SCHEMA app"));
	}

	@Test
	void dropSchemaWithoutRestrictKeyword() {
		engine.execute("CREATE SCHEMA tooling");
		engine.execute("DROP SCHEMA tooling");
		assertThrows(IllegalArgumentException.class,
				() -> engine.execute("SET SCHEMA tooling"));
	}

	@Test
	void createSchemaAuthorizationIgnored() {
		engine.execute("CREATE SCHEMA analytics AUTHORIZATION alice");
		assertTrue(engine.catalog().schemaExists("analytics"));
		engine.execute("DROP SCHEMA analytics");
	}

	@Test
	void dropSchemaCascadeRejected() {
		engine.execute("CREATE SCHEMA doomed");
		assertThrows(IllegalArgumentException.class,
				() -> engine.execute("DROP SCHEMA doomed CASCADE"));
		engine.execute("DROP SCHEMA doomed RESTRICT");
	}

	@Test
	void dropSchemaRestrictRejectsView() {
		engine.execute("CREATE SCHEMA app");
		engine.execute("CREATE TABLE app.base (id INT PRIMARY KEY)");
		engine.execute("CREATE VIEW app.v AS SELECT id FROM app.base");
		assertThrows(IllegalStateException.class, () -> engine.execute("DROP SCHEMA app"));
		engine.execute("DROP VIEW app.v");
		engine.execute("DROP TABLE app.base");
		engine.execute("DROP SCHEMA app");
	}

	@Test
	void catalogRecoveryAfterDropSchema() throws Exception {
		final java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("grid-cat-drop");
		final SqlEngine e1 = new SqlEngine(new TableCatalog(dir), null, 4);
		e1.execute("CREATE SCHEMA app");
		e1.execute("CREATE TABLE app.t (id INT PRIMARY KEY, v INT)");
		e1.execute("DROP TABLE app.t");
		e1.execute("DROP SCHEMA app");

		final SqlEngine e2 = new SqlEngine(new TableCatalog(dir), null, 4);
		e2.recoverPersistedCatalog();
		assertTrue(!e2.catalog().schemaExists("app"));
		assertTrue(!e2.catalog().exists("app.t"));
	}

	@Test
	void catalogRecoverySurvivesPoisonedDropSchemaJournal() throws Exception {
		final java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("grid-cat-poison");
		final java.nio.file.Path catalog = dir.resolve("catalog");
		java.nio.file.Files.createDirectories(catalog);
		java.nio.file.Files.writeString(catalog.resolve("schemas.list"), "app\n");
		java.nio.file.Files.writeString(catalog.resolve("ddl.sql"),
				"""
						CREATE SCHEMA app
						CREATE TABLE IF NOT EXISTS app.t (id INT PRIMARY KEY)
						DROP SCHEMA app RESTRICT
						CREATE SCHEMA app
						CREATE TABLE IF NOT EXISTS app.t (id INT PRIMARY KEY, v INT)
						""");

		final SqlEngine e2 = new SqlEngine(new TableCatalog(dir), null, 4);
		e2.recoverPersistedCatalog();
		assertTrue(e2.catalog().schemaExists("app"));
		assertTrue(e2.catalog().exists("app.t"));
		assertEquals(2, e2.catalog().requireSchema("app.t").columnCount());
	}

	@Test
	void catalogRecoveryCreateDropCreateSameSchema() throws Exception {
		final java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("grid-cat-cdc");
		final SqlEngine e1 = new SqlEngine(new TableCatalog(dir), null, 4);
		e1.execute("CREATE SCHEMA app");
		e1.execute("CREATE TABLE app.t (id INT PRIMARY KEY)");
		e1.execute("DROP TABLE app.t");
		e1.execute("DROP SCHEMA app");
		e1.execute("CREATE SCHEMA app");
		e1.execute("CREATE TABLE app.t (id INT PRIMARY KEY, v INT)");

		final SqlEngine e2 = new SqlEngine(new TableCatalog(dir), null, 4);
		e2.recoverPersistedCatalog();
		assertTrue(e2.catalog().schemaExists("app"));
		assertTrue(e2.catalog().exists("app.t"));
		assertEquals(2, e2.catalog().requireSchema("app.t").columnCount());
	}

	@Test
	void dropTableIfExistsClearsOrphanMetaOnRecover() throws Exception {
		final java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("grid-cat-orphan");
		final SqlEngine e1 = new SqlEngine(new TableCatalog(dir), null, 4);
		e1.execute("CREATE SCHEMA app");
		e1.execute("CREATE TABLE app.t (id INT PRIMARY KEY)");
		e1.execute("DROP TABLE app.t");
		e1.execute("DROP TABLE IF EXISTS app.t");

		final SqlEngine e2 = new SqlEngine(new TableCatalog(dir), null, 4);
		e2.recoverPersistedCatalog();
		assertTrue(!e2.catalog().exists("app.t"));
	}

	@Test
	void catalogRecoveryReplaysSchemaAndTable() throws Exception {
		final java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("grid-cat-rec");
final SqlEngine e1 = new SqlEngine(new TableCatalog(dir), null, 4);
		e1.execute("CREATE SCHEMA app");
		final SqlSession s = e1.newSession();
		e1.execute(s, "SET SCHEMA app");
		e1.execute(s, "CREATE TABLE t (id INT PRIMARY KEY, v VARCHAR)");
		e1.execute(s, "ALTER TABLE t ADD COLUMN note VARCHAR");

final SqlEngine e2 = new SqlEngine(new TableCatalog(dir), null, 4);
		e2.recoverPersistedCatalog();
		assertTrue(e2.catalog().schemaExists("app"));
		assertTrue(e2.catalog().exists("app.t"));
		assertEquals(3, e2.catalog().requireSchema("app.t").columnCount());
	}

	@Test
	void notNullAndUniqueReject() {
		engine.execute("CREATE TABLE u (id INT PRIMARY KEY, email VARCHAR NOT NULL)");
		engine.execute("CREATE UNIQUE INDEX ux_email ON u (email)");
		engine.execute("INSERT INTO u VALUES (1, 'a@x')");
		assertThrows(RuntimeException.class,
				() -> engine.execute("INSERT INTO u VALUES (2, NULL)"));
		assertThrows(RuntimeException.class,
				() -> engine.execute("INSERT INTO u VALUES (3, 'a@x')"));
		engine.execute("UPDATE u SET email = 'a@x' WHERE id = 1");
		engine.execute("INSERT INTO u VALUES (4, 'b@x')");
		assertThrows(RuntimeException.class,
				() -> engine.execute("UPDATE u SET email = 'a@x' WHERE id = 4"));
	}
}