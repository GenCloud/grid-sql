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

import java.nio.file.Files;

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression: INT literals against DOUBLE columns must coerce before wire compare.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class WireLiteralCoerceIT {
	private static final int ROW_COUNT = 200;
	private static final double SALARY_FLOOR = 1000.0;
	private static final double SALARY_SPAN = 4000.0;
	private static final int SALARY_LITERAL = 2000;

	private SqlEngine engine;

	@BeforeEach
	void setUp() throws Exception {
		engine = new SqlEngine(new TableCatalog(Files.createTempDirectory("wire-coerce-it")), null, 4);
	}

	@Test
	void intLiteralAgainstDoubleColumnFiltersCorrectly() {
		engine.execute("CREATE TABLE emp (id INT PRIMARY KEY, salary DOUBLE)");
		for (int i = 0; i < ROW_COUNT; i++) {
			final double salary = SALARY_FLOOR + (SALARY_SPAN * i / ROW_COUNT);
			engine.execute("INSERT INTO emp (id, salary) VALUES (" + i + ", " + salary + ")");
		}
		final SqlResult filtered = engine.execute(
				"SELECT id, salary FROM emp WHERE salary > " + SALARY_LITERAL);
		assertTrue(filtered.rows().size() > 0);
		assertTrue(filtered.rows().size() < ROW_COUNT);
		for (Object[] row : filtered.rows()) {
			final double salary = ((Number) row[1]).doubleValue();
			assertTrue(salary > SALARY_LITERAL, "salary=" + salary);
		}
		assertEquals(
				filtered.rows().size(),
				engine.execute("SELECT id FROM emp WHERE salary > " + SALARY_LITERAL + ".0").rows().size());
	}
}