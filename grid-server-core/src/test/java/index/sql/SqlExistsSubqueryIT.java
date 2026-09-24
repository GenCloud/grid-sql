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
import org.genfork.grid.sql.SqlStatementParser;
import org.genfork.grid.sql.ast.SelectAst.SelectSql;
import org.genfork.grid.sql.ast.SelectAst.WhereSubquery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * K3: EXISTS / NOT EXISTS as semi-join existence probe (LIMIT 1, no IN-list decode).
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class SqlExistsSubqueryIT {
	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(), null, 4);
		engine.execute("CREATE TABLE outer_t (id INT PRIMARY KEY, flag INT)");
		engine.execute("CREATE TABLE inner_t (id INT PRIMARY KEY, ref INT)");
		engine.execute("INSERT INTO outer_t VALUES (1, 1)");
		engine.execute("INSERT INTO outer_t VALUES (2, 0)");
		engine.execute("INSERT INTO inner_t VALUES (10, 1)");
	}

	@Test
	void existsParsesAsProbe() {
		final SelectSql s = (SelectSql) SqlStatementParser.parse(
				"SELECT id FROM outer_t WHERE EXISTS (SELECT id FROM inner_t WHERE ref = 1)");
		assertTrue(s.hasWhereSubqueries());
		final WhereSubquery wq = s.whereSubqueries().getFirst();
		assertTrue(wq.existsProbe());
		assertEquals("EXISTS", wq.operator());
	}

	@Test
	void existsTrueReturnsRows() {
		final SqlResult r = engine.execute(
				"SELECT id FROM outer_t WHERE EXISTS (SELECT id FROM inner_t WHERE ref = 1)");
		assertEquals(2, r.rows().size());
	}

	@Test
	void existsFalseReturnsEmpty() {
		final SqlResult r = engine.execute(
				"SELECT id FROM outer_t WHERE EXISTS (SELECT id FROM inner_t WHERE ref = 99)");
		assertEquals(0, r.rows().size());
	}

	@Test
	void notExistsFilters() {
		final SqlResult r = engine.execute(
				"SELECT id FROM outer_t WHERE NOT EXISTS (SELECT id FROM inner_t WHERE ref = 99)");
		assertEquals(2, r.rows().size());
		final SqlResult empty = engine.execute(
				"SELECT id FROM outer_t WHERE NOT EXISTS (SELECT id FROM inner_t WHERE ref = 1)");
		assertEquals(0, empty.rows().size());
	}
}
