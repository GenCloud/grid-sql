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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Composite PRIMARY KEY left-prefix EQ without ORDER BY (no scalar getByPk HY000).
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class CompositePkPrefixEqIT {
	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(), null, 4);
	}

	@Test
	void leadingPkEqReturnsMatchingRowsWithoutHy000() {
		engine.execute(
				"CREATE TABLE bitset_t (server_id INT NOT NULL, biset_type VARCHAR NOT NULL, v INT, "
						+ "PRIMARY KEY (server_id, biset_type))");
		engine.execute("INSERT INTO bitset_t VALUES (3, 'ITEMS', 1)");
		engine.execute("INSERT INTO bitset_t VALUES (3, 'OTHER', 2)");
		engine.execute("INSERT INTO bitset_t VALUES (4, 'X', 3)");

		final SqlResult prefix = engine.execute("SELECT server_id, biset_type, v FROM bitset_t WHERE server_id = 3");
		assertEquals(2, prefix.rows().size());

		final SqlResult all = engine.execute("SELECT server_id, biset_type, v FROM bitset_t");
		assertEquals(3, all.rows().size(), "SELECT * must use composite PK searchAll");

		final SqlResult full = engine.execute(
				"SELECT v FROM bitset_t WHERE server_id = 3 AND biset_type = 'ITEMS'");
		assertEquals(1, full.rows().size());
		assertEquals(1, ((Number) full.rows().getFirst()[0]).intValue());
	}

	@Test
	void singleColumnPkPointLookupUnchanged() {
		engine.execute("CREATE TABLE single_pk (id INT PRIMARY KEY, v VARCHAR)");
		engine.execute("INSERT INTO single_pk VALUES (1, 'ok')");
		final SqlResult r = engine.execute("SELECT v FROM single_pk WHERE id = 1");
		assertEquals(1, r.rows().size());
		assertEquals("ok", r.rows().getFirst()[0]);
		final SqlResult all = engine.execute("SELECT v FROM single_pk");
		assertEquals(1, all.rows().size());
	}

	@Test
	void explainLeadingPkEqIsNotScalarPkPoint() {
		engine.execute(
				"CREATE TABLE expl_t (a INT NOT NULL, b VARCHAR NOT NULL, PRIMARY KEY (a, b))");
		engine.execute("INSERT INTO expl_t VALUES (3, 'ITEMS')");
		final SqlResult expl = engine.execute("EXPLAIN SELECT * FROM expl_t WHERE a = 3");
		assertFalse(expl.rows().isEmpty());
		final String detail = String.valueOf(expl.rows().getFirst()[2]);
		assertFalse(detail.startsWith("pk="), "must not use scalar pk point path: " + detail);
	}
}