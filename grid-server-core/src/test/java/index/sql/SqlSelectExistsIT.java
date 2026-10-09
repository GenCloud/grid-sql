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
import org.genfork.grid.sql.ast.SelectAst.ExistsSelectItem;
import org.genfork.grid.sql.ast.SelectAst.SelectSql;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scalar {@code SELECT EXISTS} / {@code NOT EXISTS} (JOOQ {@code fetchExists}).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.1
 */
class SqlSelectExistsIT {
	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(), null, 4);
		engine.execute("CREATE TABLE flags (id INT PRIMARY KEY, name VARCHAR)");
		engine.execute("INSERT INTO flags VALUES (1, 'a')");
	}

	@Test
	void parsesExistsSelectItem() {
		final SelectSql s = (SelectSql) SqlStatementParser.parse(
				"SELECT EXISTS (SELECT 1 AS one FROM flags WHERE id = 1)");
		assertTrue(s.isExprOnly());
		assertEquals(1, s.selectItems().size());
		final ExistsSelectItem item = assertInstanceOf(ExistsSelectItem.class, s.selectItems().getFirst());
		assertFalse(item.negated());
		assertEquals("exists", item.label());
	}

	@Test
	void existsTrue() {
		final SqlResult r = engine.execute(
				"SELECT EXISTS (SELECT 1 AS one FROM flags WHERE id = 1)");
		assertEquals(1, r.rows().size());
		assertEquals(Boolean.TRUE, r.rows().getFirst()[0]);
	}

	@Test
	void existsFalse() {
		final SqlResult r = engine.execute(
				"SELECT EXISTS (SELECT 1 AS one FROM flags WHERE id = 99)");
		assertEquals(Boolean.FALSE, r.rows().getFirst()[0]);
	}

	@Test
	void notExists() {
		final SqlResult empty = engine.execute(
				"SELECT NOT EXISTS (SELECT 1 AS one FROM flags WHERE id = 99)");
		assertEquals(Boolean.TRUE, empty.rows().getFirst()[0]);
		final SqlResult present = engine.execute(
				"SELECT NOT EXISTS (SELECT 1 AS one FROM flags WHERE id = 1)");
		assertEquals(Boolean.FALSE, present.rows().getFirst()[0]);
	}

	@Test
	void existsWithBind() {
		final SqlSession session = engine.newSession();
		final SqlResult r = engine.execute(
				session,
				"SELECT EXISTS (SELECT 1 AS one FROM flags WHERE id = ?)",
				new Object[]{1});
		assertEquals(Boolean.TRUE, r.rows().getFirst()[0]);
	}

	@Test
	void existsSeesOpenTxDirtyInsert() {
		final SqlSession session = engine.newSession();
		engine.execute(session, "BEGIN");
		engine.execute(session, "INSERT INTO flags VALUES (99, 'tx')");
		final SqlResult r = engine.execute(
				session,
				"SELECT EXISTS (SELECT 1 AS one FROM flags WHERE id = 99)");
		assertEquals(Boolean.TRUE, r.rows().getFirst()[0]);
		engine.execute(session, "ROLLBACK");
		final SqlResult after = engine.execute(
				"SELECT EXISTS (SELECT 1 AS one FROM flags WHERE id = 99)");
		assertEquals(Boolean.FALSE, after.rows().getFirst()[0]);
	}
}
