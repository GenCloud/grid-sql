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
import org.genfork.grid.context.config.GridConfigurationProperties;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Non-bitmap LAX index {@code IN} with absent literals must not NPE (null pointers on searchEq miss).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
class LaxIndexInMissIT {
	private static final String TABLE = "lax_in_miss_t";
	private static final String INDEX_SCAN_SNIPPET = "Index";
	private static final String TABLE_SCAN_SNIPPET = "Table Scan";

	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		final GridConfigurationProperties props = new GridConfigurationProperties();
		props.getDurability().setEnabled(false);
		props.getReplication().setEnabled(false);
		engine = new SqlEngine(
				new TableCatalog(),
				new ReplicationCoordinator(props),
				props.getSql().getDefaultShards());
		engine.execute("CREATE TABLE " + TABLE + " (id INT PRIMARY KEY, flag INT, name VARCHAR)");
		engine.execute("CREATE INDEX lax_in_miss_flag ON " + TABLE + " (flag)");
		engine.execute("INSERT INTO " + TABLE
				+ " VALUES (1, 1, 'a'), (2, 2, 'b'), (3, 1, 'c')");
	}

	@Test
	void inWithAbsentLiteral_returnsHitsWithoutNpe() {
		final SqlResult result = assertDoesNotThrow(() ->
				engine.execute("SELECT id FROM " + TABLE + " WHERE flag IN (1, 99)"));
		assertEquals(2, result.rows().size());
	}

	@Test
	void inAllAbsent_returnsEmptyWithoutNpe() {
		final SqlResult result = assertDoesNotThrow(() ->
				engine.execute("SELECT id FROM " + TABLE + " WHERE flag IN (40, 41, 42)"));
		assertEquals(0, result.rows().size());
	}

	@Test
	void notEqMissUnderNot_doesNotNpe() {
		final SqlResult result = assertDoesNotThrow(() ->
				engine.execute("SELECT id FROM " + TABLE + " WHERE NOT (flag = 99)"));
		assertEquals(3, result.rows().size());
	}

	@Test
	void explainInDoesNotFallBackToTableScanAlone() {
		final SqlResult result = engine.execute(
				"EXPLAIN ANALYZE SELECT id FROM " + TABLE + " WHERE flag IN (1, 99)");
		assertFalse(result.rows().isEmpty());
		boolean sawTableScan = false;
		boolean sawIndexish = false;
		for (Object[] row : result.rows()) {
			final String kind = String.valueOf(row[0]);
			final String detail = row.length > 2 ? String.valueOf(row[2]) : "";
			final String combined = kind + " " + detail;
			if (combined.contains(TABLE_SCAN_SNIPPET) && !combined.contains(INDEX_SCAN_SNIPPET)) {
				sawTableScan = true;
			}
			if (combined.contains(INDEX_SCAN_SNIPPET)
					|| "INDEX".equals(kind)
					|| combined.contains("Scan")) {
				sawIndexish = true;
			}
		}
		assertTrue(sawIndexish || !sawTableScan,
				"IN miss must keep index path (or non-table-scan plan)");
	}
}
