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

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1.2: {@code UNION [ALL]} and simple WHERE scalar / {@code IN} subqueries.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class SqlUnionSubqueryIT {
	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(), null, 4);
	}

	@Test
	void unionAllConcatenatesArms() {
		engine.execute("CREATE TABLE a (id INT PRIMARY KEY, v INT)");
		engine.execute("CREATE TABLE b (id INT PRIMARY KEY, v INT)");
		engine.execute("INSERT INTO a VALUES (1, 10)");
		engine.execute("INSERT INTO a VALUES (2, 20)");
		engine.execute("INSERT INTO b VALUES (2, 20)");
		engine.execute("INSERT INTO b VALUES (3, 30)");

		final SqlResult r = engine.execute(
				"SELECT id FROM a UNION ALL SELECT id FROM b");
		assertEquals(4, r.rows().size());
	}

	@Test
	void unionDedupesAcrossArms() {
		engine.execute("CREATE TABLE a (id INT PRIMARY KEY, v INT)");
		engine.execute("CREATE TABLE b (id INT PRIMARY KEY, v INT)");
		engine.execute("INSERT INTO a VALUES (1, 10)");
		engine.execute("INSERT INTO a VALUES (2, 20)");
		engine.execute("INSERT INTO b VALUES (2, 20)");
		engine.execute("INSERT INTO b VALUES (3, 30)");

		final SqlResult r = engine.execute(
				"SELECT id FROM a UNION SELECT id FROM b");
		assertEquals(3, r.rows().size());
		final Set<Integer> ids = new HashSet<>();
		for (Object[] row : r.rows()) {
			ids.add(((Number) row[0]).intValue());
		}
		assertTrue(ids.contains(1));
		assertTrue(ids.contains(2));
		assertTrue(ids.contains(3));
	}

	@Test
	void inSubqueryFiltersOuter() {
		engine.execute("CREATE TABLE outer_t (id INT PRIMARY KEY, bucket INT)");
		engine.execute("CREATE TABLE inner_t (id INT PRIMARY KEY, bucket INT)");
		engine.execute("INSERT INTO outer_t VALUES (1, 1)");
		engine.execute("INSERT INTO outer_t VALUES (2, 2)");
		engine.execute("INSERT INTO outer_t VALUES (3, 1)");
		engine.execute("INSERT INTO inner_t VALUES (10, 1)");

		final SqlResult r = engine.execute(
				"SELECT id FROM outer_t WHERE bucket IN (SELECT bucket FROM inner_t WHERE id = 10)");
		assertEquals(2, r.rows().size());
		final Set<Integer> ids = new HashSet<>();
		for (Object[] row : r.rows()) {
			ids.add(((Number) row[0]).intValue());
		}
		assertTrue(ids.contains(1));
		assertTrue(ids.contains(3));
	}

	@Test
	void scalarEqSubquery() {
		engine.execute("CREATE TABLE outer_t (id INT PRIMARY KEY, v INT)");
		engine.execute("CREATE TABLE inner_t (id INT PRIMARY KEY, v INT)");
		engine.execute("INSERT INTO outer_t VALUES (1, 7)");
		engine.execute("INSERT INTO outer_t VALUES (2, 8)");
		engine.execute("INSERT INTO inner_t VALUES (1, 7)");

		final SqlResult r = engine.execute(
				"SELECT id FROM outer_t WHERE v = (SELECT v FROM inner_t WHERE id = 1)");
		assertEquals(1, r.rows().size());
		assertEquals(1, ((Number) r.rows().getFirst()[0]).intValue());
	}
}
