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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EQ leading without ORDER BY prefers scalar secondary over composite prefix.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class QueryIndexChoiceSecondaryVsPrefixIT {
	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(), null, 4);
	}

	@Test
	void leadingEqWithoutOrderByUsesScalarWhenPresent() {
		engine.execute(
				"CREATE TABLE pick_t (a INT NOT NULL, b VARCHAR NOT NULL, c INT, "
						+ "PRIMARY KEY (a, b))");
		engine.execute("CREATE INDEX idx_a ON pick_t (a)");
		engine.execute("INSERT INTO pick_t VALUES (1, 'x', 10)");
		engine.execute("INSERT INTO pick_t VALUES (1, 'y', 20)");
		engine.execute("INSERT INTO pick_t VALUES (2, 'z', 30)");

		final SqlResult expl = engine.execute("EXPLAIN SELECT a, b FROM pick_t WHERE a = 1");
		assertFalse(expl.rows().isEmpty());
		final String detail = String.valueOf(expl.rows().getFirst()[2]);
		assertFalse(detail.toLowerCase().contains("composite"), "should prefer scalar: " + detail);

		final SqlResult rows = engine.execute("SELECT a, b FROM pick_t WHERE a = 1");
		assertEquals(2, rows.rows().size());
	}

	@Test
	void orderByNextColKeepsCompositePath() {
		engine.execute(
				"CREATE TABLE ord_t (a INT NOT NULL, b VARCHAR NOT NULL, c INT, "
						+ "PRIMARY KEY (a, b))");
		engine.execute("CREATE INDEX idx_a2 ON ord_t (a)");
		engine.execute("INSERT INTO ord_t VALUES (1, 'b', 1)");
		engine.execute("INSERT INTO ord_t VALUES (1, 'a', 2)");

		final SqlResult rows = engine.execute(
				"SELECT b FROM ord_t WHERE a = 1 ORDER BY b ASC LIMIT 0, 10");
		assertEquals(2, rows.rows().size());
		assertEquals("a", rows.rows().getFirst()[0]);
	}
}