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
 * Verifies side-effect-free EXPLAIN plan kinds for mutating statements.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class ExplainMutationKindsIT {
	private SqlEngine engine;

	@BeforeEach
	void setUp() throws Exception {
		engine = new SqlEngine(
				new TableCatalog(Files.createTempDirectory("explain-mutation")),
				null,
				4
		);
		engine.execute("CREATE TABLE em_t (id INT PRIMARY KEY, value VARCHAR)");
		engine.execute("INSERT INTO em_t VALUES (1, 'before')");
	}

	@Test
	void explainDmlReturnsKindWithoutSideEffects() {
		assertDryKind(
				"EXPLAIN INSERT INTO em_t VALUES (2, 'inserted')",
				"INSERT"
		);
		assertDryKind(
				"EXPLAIN UPDATE em_t SET value = 'updated' WHERE id = 1",
				"UPDATE"
		);
		assertDryKind(
				"EXPLAIN DELETE FROM em_t WHERE id = 1",
				"DELETE"
		);
		assertDryKind(
				"EXPLAIN MERGE INTO em_t USING (VALUES (1, 'merged')) ON id = id "
						+ "WHEN MATCHED THEN UPDATE SET value = 'merged' "
						+ "WHEN NOT MATCHED THEN INSERT VALUES (1, 'merged')",
				"MERGE"
		);

		final SqlResult rows = engine.execute("SELECT id, value FROM em_t");
		assertEquals(1, rows.rows().size());
		assertEquals("before", rows.rows().getFirst()[1]);
	}

	@Test
	void explainAnalyzeMutationStaysDry() {
		assertDryKind(
				"EXPLAIN ANALYZE DELETE FROM em_t WHERE id = 1",
				"DELETE"
		);
		assertEquals(1, engine.execute("SELECT id FROM em_t").rows().size());
	}

	private void assertDryKind(String sql, String expectedKind) {
		final SqlResult result = engine.execute(sql);
		assertEquals(expectedKind, result.rows().getFirst()[0]);
		assertEquals("em_t", result.rows().getFirst()[1]);
		assertTrue(String.valueOf(result.rows().getFirst()[2]).contains("side-effects=none"));
	}
}
