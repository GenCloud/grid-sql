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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WITH / VIEW / MATERIALIZED VIEW / DISTINCT / HAVING / MIN-MAX / CAST / CASE / window wiring.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class SqlWithViewWindowTest {
	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(), null, 4);
	}

	@Test
	void withCteSelect() {
		engine.execute("CREATE TABLE t (id INT PRIMARY KEY, v INT)");
		engine.execute("INSERT INTO t VALUES (1, 10)");
		engine.execute("INSERT INTO t VALUES (2, 20)");
		final SqlResult r = engine.execute(
				"WITH cte AS (SELECT id, v FROM t WHERE v > 10) SELECT id FROM cte");
		assertEquals(1, r.rows().size());
		assertEquals(2, r.rows().getFirst()[0]);
	}

	@Test
	void createViewSelectDrop() {
		engine.execute("CREATE TABLE base (id INT PRIMARY KEY, name VARCHAR)");
		engine.execute("INSERT INTO base VALUES (1, 'a')");
		engine.execute("INSERT INTO base VALUES (2, 'b')");
		engine.execute("CREATE VIEW v AS SELECT id, name FROM base WHERE id = 1");
		final SqlResult fromView = engine.execute("SELECT name FROM v");
		assertEquals(1, fromView.rows().size());
		assertEquals("a", fromView.rows().getFirst()[0]);
		engine.execute("DROP VIEW v");
		engine.execute("CREATE VIEW v AS SELECT id, name FROM base WHERE id = 2");
		final SqlResult again = engine.execute("SELECT name FROM v");
		assertEquals("b", again.rows().getFirst()[0]);
		engine.execute("DROP VIEW IF EXISTS v");
	}

	@Test
	void minMaxHavingDistinct() {
		engine.execute("CREATE TABLE g (id INT PRIMARY KEY, bucket INT, score INT)");
		engine.execute("INSERT INTO g VALUES (1, 1, 10)");
		engine.execute("INSERT INTO g VALUES (2, 1, 30)");
		engine.execute("INSERT INTO g VALUES (3, 2, 5)");
		engine.execute("INSERT INTO g VALUES (4, 2, 5)");

		final SqlResult min = engine.execute("SELECT MIN(score) FROM g GROUP BY bucket");
		assertEquals(2, min.rows().size());

		final SqlResult max = engine.execute("SELECT MAX(score) FROM g");
		assertEquals(1, max.rows().size());
		assertEquals(30.0d, ((Number) max.rows().getFirst()[0]).doubleValue(), 0.001);

		final SqlResult having = engine.execute(
				"SELECT MIN(score) FROM g GROUP BY bucket HAVING bucket = 1");
		assertEquals(1, having.rows().size());
		assertEquals(10.0d, ((Number) having.rows().getFirst()[1]).doubleValue(), 0.001);

		final SqlResult distinct = engine.execute("SELECT DISTINCT bucket FROM g");
		assertEquals(2, distinct.rows().size());
	}

	@Test
	void castInWhere() {
		engine.execute("CREATE TABLE c (id INT PRIMARY KEY, v INT)");
		engine.execute("INSERT INTO c VALUES (1, 7)");
		engine.execute("INSERT INTO c VALUES (2, 8)");
		final SqlResult r = engine.execute("SELECT id FROM c WHERE v = CAST(7 AS INT)");
		assertEquals(1, r.rows().size());
		assertEquals(1, r.rows().getFirst()[0]);
	}

	@Test
	void caseConstantInInsert() {
		engine.execute("CREATE TABLE k (id INT PRIMARY KEY, flag INT)");
		engine.execute("INSERT INTO k VALUES (1, CASE WHEN TRUE THEN 9 ELSE 0 END)");
		final SqlResult r = engine.execute("SELECT flag FROM k WHERE id = 1");
		assertEquals(9, r.rows().getFirst()[0]);
	}

	@Test
	void rowNumberOver() {
		engine.execute("CREATE TABLE w (id INT PRIMARY KEY, bucket INT)");
		engine.execute("INSERT INTO w VALUES (1, 1)");
		engine.execute("INSERT INTO w VALUES (2, 1)");
		engine.execute("INSERT INTO w VALUES (3, 2)");
		final SqlResult r = engine.execute(
				"SELECT ROW_NUMBER() OVER (PARTITION BY bucket ORDER BY id) FROM w");
		assertEquals(3, r.rows().size());
		boolean sawOne = false;
		boolean sawTwo = false;
		for (Object[] row : r.rows()) {
			final double n = ((Number) row[0]).doubleValue();
			if (n == 1.0d) {
				sawOne = true;
			}
			if (n == 2.0d) {
				sawTwo = true;
			}
		}
		assertTrue(sawOne);
		assertTrue(sawTwo);
	}

	@Test
	void mixedSelectWithRowNumber() {
		engine.execute("CREATE TABLE mw (id INT PRIMARY KEY, v INT)");
		engine.execute("INSERT INTO mw VALUES (1, 30)");
		engine.execute("INSERT INTO mw VALUES (2, 10)");
		engine.execute("INSERT INTO mw VALUES (3, 20)");
		final SqlResult r = engine.execute(
				"SELECT id, ROW_NUMBER() OVER (ORDER BY v) FROM mw");
		assertEquals(3, r.rows().size());
		assertEquals(2, r.columns().size());
		Integer idForRank1 = null;
		for (Object[] row : r.rows()) {
			if (((Number) row[1]).doubleValue() == 1.0d) {
				idForRank1 = (Integer) row[0];
			}
		}
		assertEquals(2, idForRank1);
	}

	@Test
	void denseRankLagLead() {
		engine.execute("CREATE TABLE dr (id INT PRIMARY KEY, score INT)");
		engine.execute("INSERT INTO dr VALUES (1, 10)");
		engine.execute("INSERT INTO dr VALUES (2, 10)");
		engine.execute("INSERT INTO dr VALUES (3, 20)");
		final SqlResult dense = engine.execute(
				"SELECT id, DENSE_RANK() OVER (ORDER BY score) FROM dr");
		assertEquals(3, dense.rows().size());
		double maxRank = 0d;
		for (Object[] row : dense.rows()) {
			maxRank = Math.max(maxRank, ((Number) row[1]).doubleValue());
		}
		assertEquals(2.0d, maxRank, 0.001);

		final SqlResult lag = engine.execute(
				"SELECT id, LAG(score) OVER (ORDER BY id) FROM dr");
		Object lagOf2 = null;
		for (Object[] row : lag.rows()) {
			if (Objects.equals(row[0], 2)) {
				lagOf2 = row[1];
			}
		}
		assertEquals(10, ((Number) lagOf2).intValue());
	}

	@Test
	void havingSumThreshold() {
		engine.execute("CREATE TABLE hs (id INT PRIMARY KEY, bucket INT, score INT)");
		engine.execute("INSERT INTO hs VALUES (1, 1, 10)");
		engine.execute("INSERT INTO hs VALUES (2, 1, 30)");
		engine.execute("INSERT INTO hs VALUES (3, 2, 5)");
		final SqlResult r = engine.execute(
				"SELECT SUM(score) FROM hs GROUP BY bucket HAVING SUM(score) > 20");
		assertEquals(1, r.rows().size());
		assertEquals(40.0d, ((Number) r.rows().getFirst()[1]).doubleValue(), 0.001);
	}

	@Test
	void materializedViewRefresh() {
		engine.execute("CREATE TABLE src (id INT PRIMARY KEY, v INT)");
		engine.execute("INSERT INTO src VALUES (1, 10)");
		engine.execute("INSERT INTO src VALUES (2, 20)");
		engine.execute("CREATE MATERIALIZED VIEW mv AS SELECT id, v FROM src WHERE v > 10");
		SqlResult r = engine.execute("SELECT id FROM mv");
		assertEquals(1, r.rows().size());
		assertEquals(2, r.rows().getFirst()[0]);

		final TableCatalog.ViewDef before = engine.catalog().getView("mv");
		assertTrue(before.lastRefreshEpoch() > 0L);

		engine.execute("INSERT INTO src VALUES (3, 30)");
		engine.execute("REFRESH MATERIALIZED VIEW mv");
		r = engine.execute("SELECT id FROM mv");
		assertEquals(2, r.rows().size());

		final TableCatalog.ViewDef after = engine.catalog().getView("mv");
		assertTrue(after.lastRefreshEpoch() > before.lastRefreshEpoch());
	}

	@Test
	void joinAndWindowSeeDirtyInOpenTx() {
		engine.execute("CREATE TABLE j1 (id INT PRIMARY KEY, k INT)");
		engine.execute("CREATE TABLE j2 (id INT PRIMARY KEY, k INT)");
		engine.execute("INSERT INTO j1 VALUES (1, 1)");
		engine.execute("INSERT INTO j2 VALUES (10, 1)");
		final org.genfork.grid.sql.SqlSession session = engine.newSession();
		engine.execute(session, "BEGIN");
		engine.execute(session, "INSERT INTO j1 VALUES (2, 2)");
		engine.execute(session, "INSERT INTO j2 VALUES (20, 2)");
		final SqlResult join = engine.execute(session,
				"SELECT id FROM j1 JOIN j2 ON k = k");
		assertEquals(2, join.rows().size());

		final SqlResult win = engine.execute(session,
				"SELECT id, ROW_NUMBER() OVER (ORDER BY id) FROM j1");
		assertEquals(2, win.rows().size());
		engine.execute(session, "ROLLBACK");

		final SqlResult after = engine.execute("SELECT id FROM j1");
		assertEquals(1, after.rows().size());
		assertEquals(1, after.rows().getFirst()[0]);
	}

	@Test
	void withCteBodyJoin() {
		engine.execute("CREATE TABLE a (id INT PRIMARY KEY, k INT)");
		engine.execute("CREATE TABLE b (id INT PRIMARY KEY, k INT)");
		engine.execute("INSERT INTO a VALUES (1, 7)");
		engine.execute("INSERT INTO b VALUES (10, 7)");
		final SqlResult r = engine.execute(
				"WITH j AS (SELECT a.id AS aid, b.id AS bid FROM a JOIN b ON k = k) SELECT aid, bid FROM j");
		assertEquals(1, r.rows().size());
		assertEquals(1, r.rows().getFirst()[0]);
		assertEquals(10, r.rows().getFirst()[1]);
	}

	@Test
	void aggregateWithJoin() {
		engine.execute("CREATE TABLE o (id INT PRIMARY KEY, cid INT)");
		engine.execute("CREATE TABLE c (id INT PRIMARY KEY, n INT)");
		engine.execute("INSERT INTO o VALUES (1, 1)");
		engine.execute("INSERT INTO o VALUES (2, 1)");
		engine.execute("INSERT INTO c VALUES (1, 100)");
		final SqlResult r = engine.execute("SELECT COUNT(*) FROM o JOIN c ON cid = id");
		assertEquals(1, r.rows().size());
		assertEquals(2.0d, ((Number) r.rows().getFirst()[0]).doubleValue(), 0.001);
	}

	@Test
	void windowWithJoin() {
		engine.execute("CREATE TABLE x (id INT PRIMARY KEY, k INT)");
		engine.execute("CREATE TABLE y (id INT PRIMARY KEY, k INT)");
		engine.execute("INSERT INTO x VALUES (1, 1)");
		engine.execute("INSERT INTO x VALUES (2, 1)");
		engine.execute("INSERT INTO y VALUES (9, 1)");
		final SqlResult r = engine.execute(
				"SELECT x.id, ROW_NUMBER() OVER (ORDER BY x.id) FROM x JOIN y ON k = k");
		assertEquals(2, r.rows().size());
	}
}