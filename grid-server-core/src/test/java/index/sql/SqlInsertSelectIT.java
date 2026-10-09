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
import org.genfork.grid.sql.SqlStatementParser;
import org.genfork.grid.sql.ast.DmlAst.InsertSql;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code INSERT … SELECT} / {@code UPSERT … SELECT} copy path.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.1
 */
class SqlInsertSelectIT {
	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(), null, 4);
		engine.execute("CREATE TABLE src (id INT PRIMARY KEY, val VARCHAR)");
		engine.execute("CREATE TABLE dst (id INT PRIMARY KEY, val VARCHAR)");
		engine.execute("INSERT INTO src VALUES (1, 'a')");
		engine.execute("INSERT INTO src VALUES (2, 'b')");
	}

	@Test
	void parsesInsertSelect() {
		final InsertSql stmt = (InsertSql) SqlStatementParser.parse(
				"INSERT INTO dst (id, val) SELECT id, val FROM src");
		assertTrue(stmt.isInsertSelect());
		assertNotNull(stmt.selectSourceOrNull());
		assertTrue(stmt.rows().isEmpty());
	}

	@Test
	void copiesRows() {
		final SqlResult r = engine.execute("INSERT INTO dst (id, val) SELECT id, val FROM src");
		assertEquals(2L, r.rowsAffected());
		assertEquals(2, engine.execute("SELECT id FROM dst").rows().size());
		assertEquals("a", engine.execute("SELECT val FROM dst WHERE id = 1").rows().getFirst()[0]);
	}

	@Test
	void emptySelectAffectsZero() {
		final SqlResult r = engine.execute(
				"INSERT INTO dst (id, val) SELECT id, val FROM src WHERE id = 99");
		assertEquals(0L, r.rowsAffected());
		assertEquals(0, engine.execute("SELECT id FROM dst").rows().size());
	}

	@Test
	void conflictDoNothing() {
		engine.execute("INSERT INTO dst VALUES (1, 'old')");
		engine.execute(
				"INSERT INTO dst (id, val) SELECT id, val FROM src ON CONFLICT DO NOTHING");
		assertEquals("old", engine.execute("SELECT val FROM dst WHERE id = 1").rows().getFirst()[0]);
		assertEquals(2, engine.execute("SELECT id FROM dst").rows().size());
	}

	@Test
	void openTxRollback() {
		final SqlSession session = engine.newSession();
		engine.execute(session, "BEGIN");
		engine.execute(session, "INSERT INTO dst (id, val) SELECT id, val FROM src");
		assertEquals(2, engine.execute(session, "SELECT id FROM dst").rows().size());
		engine.execute(session, "ROLLBACK");
		assertEquals(0, engine.execute("SELECT id FROM dst").rows().size());
	}

	@Test
	void duplicatePkRejectedWithoutConflict() {
		engine.execute("INSERT INTO dst VALUES (1, 'old')");
		assertThrows(Exception.class, () ->
				engine.execute("INSERT INTO dst (id, val) SELECT id, val FROM src WHERE id = 1"));
	}
}
