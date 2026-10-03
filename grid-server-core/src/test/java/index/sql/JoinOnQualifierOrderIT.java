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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;

/**
 * JOIN ON column order must follow table qualifiers, not textual left/right.
 * <p>
 * Jepsen join-shards uses child c JOIN parent p ON ...; both ON orders must resolve.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
class JoinOnQualifierOrderIT {
	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(), null, 8);
		engine.execute("CREATE TABLE jepsen_parent (id INT PRIMARY KEY, name VARCHAR)");
		engine.execute(
				"CREATE TABLE jepsen_child (id INT PRIMARY KEY, parent_id INT, number VARCHAR, status VARCHAR)");
		engine.execute("INSERT INTO jepsen_parent (id, name) VALUES (102, 'p2')");
		engine.execute(
				"INSERT INTO jepsen_child (id, parent_id, number, status) VALUES (2, 102, 't1 t2', 'seed')");
	}

	@Test
	void joinOnLeftThenRightAliasOrder() {
		final SqlResult r = assertDoesNotThrow(() -> engine.execute(
				"SELECT c.number, c.status FROM jepsen_child c INNER JOIN jepsen_parent p "
						+ "ON c.parent_id = p.id WHERE c.id = 2"));
		assertEquals(1, r.rows().size());
		assertEquals("t1 t2", String.valueOf(r.rows().getFirst()[0]));
	}

	@Test
	void joinOnRightThenLeftAliasOrder() {
		final SqlResult r = assertDoesNotThrow(() -> engine.execute(
				"SELECT c.number, c.status FROM jepsen_child c INNER JOIN jepsen_parent p "
						+ "ON p.id = c.parent_id WHERE c.id = 2"));
		assertEquals(1, r.rows().size());
		assertEquals("t1 t2", String.valueOf(r.rows().getFirst()[0]));
	}

	@Test
	void joinOnUnqualifiedTableNamesReverseTextualOrder() {
		final SqlResult r = assertDoesNotThrow(() -> engine.execute(
				"SELECT jepsen_child.number FROM jepsen_child INNER JOIN jepsen_parent "
						+ "ON jepsen_parent.id = jepsen_child.parent_id WHERE jepsen_child.id = 2"));
		assertEquals(1, r.rows().size());
		assertEquals("t1 t2", String.valueOf(r.rows().getFirst()[0]));
	}

	@Test
	void jepsenClientLeftOuterJoinExactSql() {
		final SqlResult r = assertDoesNotThrow(() -> engine.execute(
				"SELECT c.number, c.status, p.id FROM jepsen_child c LEFT OUTER JOIN jepsen_parent p "
						+ "ON c.parent_id = p.id WHERE c.id = 2"));
		assertEquals(1, r.rows().size());
		assertEquals("t1 t2", String.valueOf(r.rows().getFirst()[0]));
		assertEquals(102, ((Number) r.rows().getFirst()[2]).intValue());
	}

	@Test
	void jepsenClientLeftOuterJoinReverseOnOrder() {
		final SqlResult r = assertDoesNotThrow(() -> engine.execute(
				"SELECT c.number, c.status, p.id FROM jepsen_child c LEFT OUTER JOIN jepsen_parent p "
						+ "ON p.id = c.parent_id WHERE c.id = 2"));
		assertEquals(1, r.rows().size());
		assertEquals("t1 t2", String.valueOf(r.rows().getFirst()[0]));
		assertEquals(102, ((Number) r.rows().getFirst()[2]).intValue());
	}
}