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

import java.util.HashSet;
import java.util.Set;

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1.3: {@code INTERSECT} / {@code EXCEPT} [ALL] and mixed set-op chains.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class SqlIntersectExceptIT {
	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(), null, 4);
	}

	@Test
	void intersectKeepsCommonDistinct() {
		seedAb();
		final SqlResult r = engine.execute(
				"SELECT id FROM a INTERSECT SELECT id FROM b");
		assertEquals(1, r.rows().size());
		assertEquals(2, ((Number) r.rows().getFirst()[0]).intValue());
	}

	@Test
	void intersectAllKeepsMinMultiplicity() {
		engine.execute("CREATE TABLE a (id INT PRIMARY KEY, v INT)");
		engine.execute("CREATE TABLE b (id INT PRIMARY KEY, v INT)");
		engine.execute("INSERT INTO a VALUES (1, 10)");
		engine.execute("INSERT INTO a VALUES (2, 10)");
		engine.execute("INSERT INTO a VALUES (3, 10)");
		engine.execute("INSERT INTO b VALUES (10, 10)");
		engine.execute("INSERT INTO b VALUES (11, 10)");

		final SqlResult r = engine.execute(
				"SELECT v FROM a INTERSECT ALL SELECT v FROM b");
		assertEquals(2, r.rows().size());
		for (Object[] row : r.rows()) {
			assertEquals(10, ((Number) row[0]).intValue());
		}
	}

	@Test
	void exceptRemovesRightArm() {
		seedAb();
		final SqlResult r = engine.execute(
				"SELECT id FROM a EXCEPT SELECT id FROM b");
		assertEquals(1, r.rows().size());
		assertEquals(1, ((Number) r.rows().getFirst()[0]).intValue());
	}

	@Test
	void exceptAllSubtractsMultiplicity() {
		engine.execute("CREATE TABLE a (id INT PRIMARY KEY, v INT)");
		engine.execute("CREATE TABLE b (id INT PRIMARY KEY, v INT)");
		engine.execute("INSERT INTO a VALUES (1, 7)");
		engine.execute("INSERT INTO a VALUES (2, 7)");
		engine.execute("INSERT INTO a VALUES (3, 7)");
		engine.execute("INSERT INTO b VALUES (10, 7)");

		final SqlResult r = engine.execute(
				"SELECT v FROM a EXCEPT ALL SELECT v FROM b");
		assertEquals(2, r.rows().size());
	}

	@Test
	void mixedUnionThenExcept() {
		seedAb();
		engine.execute("CREATE TABLE c (id INT PRIMARY KEY, v INT)");
		engine.execute("INSERT INTO c VALUES (3, 30)");

		final SqlResult r = engine.execute(
				"SELECT id FROM a UNION SELECT id FROM b EXCEPT SELECT id FROM c");
		final Set<Integer> ids = new HashSet<>();
		for (Object[] row : r.rows()) {
			ids.add(((Number) row[0]).intValue());
		}
		assertEquals(2, ids.size());
		assertTrue(ids.contains(1));
		assertTrue(ids.contains(2));
	}

	@Test
	void mixedUnionThenIntersect() {
		engine.execute("CREATE TABLE a (id INT PRIMARY KEY, v INT)");
		engine.execute("CREATE TABLE b (id INT PRIMARY KEY, v INT)");
		engine.execute("CREATE TABLE c (id INT PRIMARY KEY, v INT)");
		engine.execute("INSERT INTO a VALUES (1, 1)");
		engine.execute("INSERT INTO a VALUES (2, 2)");
		engine.execute("INSERT INTO b VALUES (2, 2)");
		engine.execute("INSERT INTO b VALUES (3, 3)");
		engine.execute("INSERT INTO c VALUES (2, 2)");
		engine.execute("INSERT INTO c VALUES (4, 4)");

		final SqlResult r = engine.execute(
				"SELECT id FROM a UNION SELECT id FROM b INTERSECT SELECT id FROM c");
		assertEquals(1, r.rows().size());
		assertEquals(2, ((Number) r.rows().getFirst()[0]).intValue());
	}

	@Test
	void explainLabelsIntersectExcept() {
		seedAb();
		final SqlResult r = engine.execute(
				"EXPLAIN SELECT id FROM a INTERSECT SELECT id FROM b EXCEPT ALL SELECT id FROM a WHERE id = 1");
		assertEquals(1, r.rows().size());
		assertEquals("SET_OP", r.rows().getFirst()[0]);
		assertEquals("arms=3", r.rows().getFirst()[1]);
		assertEquals("INTERSECT,EXCEPT_ALL", r.rows().getFirst()[2]);
	}

	private void seedAb() {
		engine.execute("CREATE TABLE a (id INT PRIMARY KEY, v INT)");
		engine.execute("CREATE TABLE b (id INT PRIMARY KEY, v INT)");
		engine.execute("INSERT INTO a VALUES (1, 10)");
		engine.execute("INSERT INTO a VALUES (2, 20)");
		engine.execute("INSERT INTO b VALUES (2, 20)");
		engine.execute("INSERT INTO b VALUES (3, 30)");
	}
}
