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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wave F: lightweight EXPLAIN reports {@code compositeIndex=} when applicable.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class ExplainCompositeIndexIT {
	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(), null, 4);
		engine.execute("CREATE TABLE cx_t (id INT PRIMARY KEY, a INT, b INT)");
		engine.execute("CREATE INDEX idx_cx_ab ON cx_t (a, b)");
		engine.execute("INSERT INTO cx_t VALUES (1, 10, 20), (2, 11, 21)");
	}

	@Test
	void explainIncludesCompositeIndexName() {
		final SqlResult result = engine.execute(
				"EXPLAIN SELECT id FROM cx_t WHERE a = 10 AND b = 20");
		boolean found = false;
		for (Object[] row : result.rows()) {
			final String detail = String.valueOf(row[2]);
			if (detail.contains("compositeIndex=idx_cx_ab")) {
				found = true;
				break;
			}
		}
		assertTrue(found, "expected compositeIndex=idx_cx_ab in EXPLAIN detail");
	}
}