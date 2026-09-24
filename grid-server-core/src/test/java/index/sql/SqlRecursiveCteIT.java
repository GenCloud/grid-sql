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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2.2: {@code WITH RECURSIVE} worklist + depth cap + PK-byte cycle detect.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class SqlRecursiveCteIT {
	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(), null, 4);
		engine.setRecursiveCteMaxDepth(32);
	}

	@Test
	void recursiveOrgTreeWalk() {
		engine.execute("CREATE TABLE emp (id INT PRIMARY KEY, parent_id INT)");
		engine.execute("INSERT INTO emp VALUES (1, 0)");
		engine.execute("INSERT INTO emp VALUES (2, 1)");
		engine.execute("INSERT INTO emp VALUES (3, 1)");
		engine.execute("INSERT INTO emp VALUES (4, 2)");
		engine.execute("INSERT INTO emp VALUES (5, 99)");

		final SqlResult r = engine.execute(
				"WITH RECURSIVE walk AS ("
						+ " SELECT id, parent_id FROM emp WHERE id = 1"
						+ " UNION ALL"
						+ " SELECT id, parent_id FROM emp JOIN walk ON parent_id = id"
						+ ") SELECT id FROM walk");

		assertEquals(4, r.rows().size());
		final Set<Integer> ids = new HashSet<>();
		for (Object[] row : r.rows()) {
			ids.add(((Number) row[0]).intValue());
		}
		assertTrue(ids.contains(1));
		assertTrue(ids.contains(2));
		assertTrue(ids.contains(3));
		assertTrue(ids.contains(4));
	}

	@Test
	void cycleDetectStopsOnPkBytes() {
		engine.execute("CREATE TABLE nodes (id INT PRIMARY KEY, parent_id INT)");
		engine.execute("INSERT INTO nodes VALUES (1, 2)");
		engine.execute("INSERT INTO nodes VALUES (2, 1)");

		final SqlResult r = engine.execute(
				"WITH RECURSIVE walk AS ("
						+ " SELECT id, parent_id FROM nodes WHERE id = 1"
						+ " UNION ALL"
						+ " SELECT id, parent_id FROM nodes JOIN walk ON parent_id = id"
						+ ") SELECT id FROM walk");

		assertEquals(2, r.rows().size());
	}

	@Test
	void depthCapRejectsDeepRecursion() {
		engine.setRecursiveCteMaxDepth(2);
		engine.execute("CREATE TABLE chain (id INT PRIMARY KEY, parent_id INT)");
		engine.execute("INSERT INTO chain VALUES (1, 0)");
		engine.execute("INSERT INTO chain VALUES (2, 1)");
		engine.execute("INSERT INTO chain VALUES (3, 2)");
		engine.execute("INSERT INTO chain VALUES (4, 3)");

		assertThrows(
				IllegalStateException.class,
				() -> engine.execute(
						"WITH RECURSIVE walk AS ("
								+ " SELECT id, parent_id FROM chain WHERE id = 1"
								+ " UNION ALL"
								+ " SELECT id, parent_id FROM chain JOIN walk ON parent_id = id"
								+ ") SELECT id FROM walk")
		);
	}
}
