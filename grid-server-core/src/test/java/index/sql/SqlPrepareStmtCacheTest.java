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
import org.genfork.grid.sql.SqlBindParam;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlSession;
import org.genfork.grid.sql.SqlStatementParser;
import org.genfork.grid.sql.ast.DmlAst.InsertSql;
import org.genfork.grid.sql.ast.SelectAst.SelectSql;
import org.genfork.grid.sql.ast.Stmt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PREPARE caches an ANTLR {@link Stmt}; EXECUTE rebinds without re-parsing the body.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class SqlPrepareStmtCacheTest {
	private SqlEngine engine;

	@BeforeEach
	void setUp() throws Exception {
		engine = new SqlEngine(
				new TableCatalog(Files.createTempDirectory("prepare-stmt-cache")),
				null,
				4,
				64
		);
	}

	@AfterEach
	void tearDown() {
	}

	@Test
	void prepareStoresParsedBodyStmtWithBindParams() {
		engine.execute("CREATE TABLE t (id INT PRIMARY KEY, v INT)");
		final SqlSession s = engine.newSession();
		engine.execute(s, "PREPARE q AS SELECT v FROM t WHERE id = ?");

		final Stmt body = s.preparedStmt("q");
		final SelectSql select = assertInstanceOf(SelectSql.class, body);
		assertTrue(select.pkValueOrNull() instanceof SqlBindParam);
		assertEquals(0, ((SqlBindParam) select.pkValueOrNull()).index());
		assertSame(body, s.preparedEntry("q").bodyStmt());
	}

	@Test
	void executeReusesCachedStmtAndAppliesBinds() {
		engine.execute("CREATE TABLE t (id INT PRIMARY KEY, v INT)");
		engine.execute("INSERT INTO t (id, v) VALUES (1, 10), (2, 20)");
		final SqlSession s = engine.newSession();
		engine.execute(s, "PREPARE q AS SELECT v FROM t WHERE id = ?");

		assertEquals(10, engine.execute(s, "EXECUTE q USING 1").rows().getFirst()[0]);
		assertEquals(20, engine.execute(s, "EXECUTE q USING 2").rows().getFirst()[0]);
		assertSame(s.preparedStmt("q"), s.preparedEntry("q").bodyStmt());
	}

	@Test
	void prepareInsertBodyCachesBindSlots() {
		engine.execute("CREATE TABLE t (id INT PRIMARY KEY, v INT)");
		final SqlSession s = engine.newSession();
		engine.execute(s, "PREPARE ins AS INSERT INTO t (id, v) VALUES (?, ?)");

		final InsertSql insert = assertInstanceOf(InsertSql.class, s.preparedStmt("ins"));
		assertTrue(insert.rows().getFirst().getFirst() instanceof SqlBindParam);
		assertTrue(insert.rows().getFirst().get(1) instanceof SqlBindParam);

		engine.execute(s, "EXECUTE ins USING 7, 70");
		assertEquals(70, engine.execute("SELECT v FROM t WHERE id = 7").rows().getFirst()[0]);
	}

	@Test
	void parsePreparedBodyCapturesPlaceholders() {
		final Stmt stmt = SqlStatementParser.parsePreparedBody(
				"SELECT v FROM t WHERE id = ?", null);
		final SelectSql select = assertInstanceOf(SelectSql.class, stmt);
		assertEquals(0, ((SqlBindParam) select.pkValueOrNull()).index());
	}
}
