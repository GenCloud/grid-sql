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
import org.genfork.grid.sql.SqlIdentParseUtil;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.SqlStatementParser;
import org.genfork.grid.sql.ast.DmlAst.UpdateSql;
import org.genfork.grid.sql.ast.Stmt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cutover dialect: keyword-as-id, table alias, multi-eq JOIN, VIEW AS WITH,
 * named OVER (name), optional UPDATE WHERE, signed literals.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
class SqlDialectCutoverIT {
	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(), null, 4);
	}

	@Test
	void keywordColumnNamesAndAliases() {
		engine.execute("CREATE TABLE kw (id INT PRIMARY KEY, locked BOOLEAN, rank INT)");
		engine.execute("INSERT INTO kw VALUES (1, TRUE, 10)");
		final SqlResult r = engine.execute("SELECT locked, rank FROM kw WHERE id = 1");
		assertEquals(1, r.rows().size());
		assertEquals(Boolean.TRUE, r.rows().getFirst()[0]);
		assertEquals(10, ((Number) r.rows().getFirst()[1]).intValue());
		final SqlResult aliased = engine.execute(
				"SELECT locked AS class FROM kw WHERE id = 1");
		assertEquals(Boolean.TRUE, aliased.rows().getFirst()[0]);
		assertEquals("class", aliased.columns().getFirst().name());
	}

	@Test
	void tableAliasAndMultiEqJoin() {
		engine.execute("CREATE TABLE left_t (id INT PRIMARY KEY, sid INT, v INT)");
		engine.execute("CREATE TABLE right_t (rid INT PRIMARY KEY, id INT, sid INT, w INT)");
		engine.execute("CREATE INDEX right_sid ON right_t (id, sid)");
		engine.execute("INSERT INTO left_t VALUES (1, 7, 100)");
		engine.execute("INSERT INTO right_t VALUES (10, 1, 7, 200)");
		engine.execute("INSERT INTO right_t VALUES (11, 1, 8, 999)");
		final SqlResult r = engine.execute(
				"SELECT l.v, r.w FROM left_t l JOIN right_t r ON l.id = r.id AND l.sid = r.sid");
		assertEquals(1, r.rows().size());
		assertEquals(100, ((Number) r.rows().getFirst()[0]).intValue());
		assertEquals(200, ((Number) r.rows().getFirst()[1]).intValue());
	}

	@Test
	void createViewAsWith() {
		engine.execute("CREATE TABLE base (id INT PRIMARY KEY, score INT)");
		engine.execute("INSERT INTO base VALUES (1, 10)");
		engine.execute("INSERT INTO base VALUES (2, 20)");
		engine.execute(
				"CREATE VIEW v AS WITH cte AS (SELECT id, score FROM base WHERE score > 10) SELECT id FROM cte");
		final SqlResult r = engine.execute("SELECT id FROM v");
		assertEquals(1, r.rows().size());
		assertEquals(2, ((Number) r.rows().getFirst()[0]).intValue());
	}

	@Test
	void namedWindowOverParenName() {
		engine.execute("CREATE TABLE scores (id INT PRIMARY KEY, pts INT)");
		engine.execute("INSERT INTO scores VALUES (1, 30)");
		engine.execute("INSERT INTO scores VALUES (2, 10)");
		engine.execute("INSERT INTO scores VALUES (3, 20)");
		final SqlResult r = engine.execute(
				"SELECT id, RANK() OVER (w) AS rnk FROM scores WINDOW w AS (ORDER BY pts DESC)");
		assertEquals(3, r.rows().size());
	}

	@Test
	void updateWithoutWhereIsFullTable() {
		final Stmt parsed = SqlStatementParser.parse("UPDATE u SET v = 1");
		assertInstanceOf(UpdateSql.class, parsed);
		assertEquals(SqlIdentParseUtil.fullTableWhereSql(), ((UpdateSql) parsed).whereSql());

		engine.execute("CREATE TABLE u (id INT PRIMARY KEY, v INT)");
		engine.execute("INSERT INTO u VALUES (1, 0)");
		engine.execute("INSERT INTO u VALUES (2, 0)");
		engine.execute("UPDATE u SET v = 5");
		final SqlResult r = engine.execute("SELECT v FROM u WHERE id = 1");
		assertEquals(5, ((Number) r.rows().getFirst()[0]).intValue());
		final SqlResult r2 = engine.execute("SELECT v FROM u WHERE id = 2");
		assertEquals(5, ((Number) r2.rows().getFirst()[0]).intValue());
	}

	@Test
	void signedLiteralsInInsertAndUpdate() {
		engine.execute("CREATE TABLE s (id INT PRIMARY KEY, v INT)");
		engine.execute("INSERT INTO s VALUES (1, -1)");
		engine.execute("UPDATE s SET v = -42 WHERE id = 1");
		final SqlResult r = engine.execute("SELECT v FROM s WHERE id = 1");
		assertEquals(-42, ((Number) r.rows().getFirst()[0]).intValue());
		assertTrue(((Number) r.rows().getFirst()[0]).intValue() < 0);
	}
}