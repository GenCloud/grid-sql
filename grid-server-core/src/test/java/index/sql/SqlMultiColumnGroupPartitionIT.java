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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * K2: multi-column GROUP BY + window PARTITION BY with composite wire keys.
 * <p>
 * Hot-path stamp: existing COUNT GROUP BY track in {@code index.benchmarks.SqlFeaturesBenchmark}
 * / compare-all; multi-col uses the same {@code SqlWireAggOps} composite path.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class SqlMultiColumnGroupPartitionIT {
	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(), null, 4);
	}

	@Test
	void multiColumnGroupByCount() {
		engine.execute("CREATE TABLE g (id INT PRIMARY KEY, a INT, b INT)");
		engine.execute("INSERT INTO g VALUES (1, 1, 10)");
		engine.execute("INSERT INTO g VALUES (2, 1, 10)");
		engine.execute("INSERT INTO g VALUES (3, 1, 20)");
		engine.execute("INSERT INTO g VALUES (4, 2, 10)");
		final SqlResult r = engine.execute("SELECT COUNT(*) FROM g GROUP BY a, b");
		assertEquals(3, r.rows().size());
		assertEquals(3, r.columns().size());
	}

	@Test
	void multiColumnGroupByParsed() {
		final SelectSql s = (SelectSql) SqlStatementParser.parse(
				"SELECT COUNT(*) FROM g GROUP BY a, b");
		assertTrue(s.hasGroupBy());
		assertEquals(2, s.groupByColumns().size());
		assertEquals("a", s.groupByColumns().get(0));
		assertEquals("b", s.groupByColumns().get(1));
	}

	@Test
	void multiColumnPartitionByRowNumber() {
		engine.execute("CREATE TABLE w (id INT PRIMARY KEY, a INT, b INT)");
		engine.execute("INSERT INTO w VALUES (1, 1, 1)");
		engine.execute("INSERT INTO w VALUES (2, 1, 1)");
		engine.execute("INSERT INTO w VALUES (3, 1, 2)");
		final SqlResult r = engine.execute(
				"SELECT id, ROW_NUMBER() OVER (PARTITION BY a, b ORDER BY id) FROM w");
		assertEquals(3, r.rows().size());
		int ones = 0;
		int twos = 0;
		for (Object[] row : r.rows()) {
			final double n = ((Number) row[1]).doubleValue();
			if (n == 1.0d) {
				ones++;
			}
			if (n == 2.0d) {
				twos++;
			}
		}
		assertEquals(2, ones);
		assertEquals(1, twos);
	}
}
