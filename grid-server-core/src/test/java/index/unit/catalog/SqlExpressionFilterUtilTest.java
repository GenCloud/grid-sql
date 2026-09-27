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
package index.unit.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.query.filters.FilterCondition;
import org.genfork.grid.query.plan.QueryParser;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.junit.jupiter.api.Test;

/**
 * Expression → filter without synthetic SELECT wrap.
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
class SqlExpressionFilterUtilTest {
	@Test
	void parseExpressionBuildsFilter() {
		final FilterCondition c = QueryParser.parseExpression("id = 1");
		assertTrue(c != null);
	}

	@Test
	void keysMatchingAndCheckWithoutSyntheticSelect() {
		final SqlEngine engine = new SqlEngine(new TableCatalog(), null, 4);
		engine.execute("CREATE TABLE t (id INT PRIMARY KEY, v INT, CHECK (v > 0))");
		engine.execute("INSERT INTO t VALUES (1, 10)");
		final SqlResult r = engine.execute("UPDATE t SET v = 11 WHERE id = 1");
		assertEquals(1, r.rowsAffected());
		try {
			engine.execute("INSERT INTO t VALUES (2, -1)");
			assertFalse(true, "CHECK should reject");
		} catch (RuntimeException ex) {
			assertTrue(ex.getMessage().contains("CHECK") || ex.getCause() != null);
		}
	}
}