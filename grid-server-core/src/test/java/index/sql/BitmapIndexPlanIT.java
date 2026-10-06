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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BITMAP EQ / IN / AND via SQL execute + EXPLAIN ANALYZE plan kind (not table-scan).
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
class BitmapIndexPlanIT {
	private static final String TABLE = "bm_plan_t";
	private static final String BITMAP_KIND_SNIPPET = "Bitmap";
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
		engine.execute("CREATE TABLE " + TABLE + " (id INT PRIMARY KEY, flag INT, color INT)");
		engine.execute("CREATE BITMAP INDEX bm_plan_flag ON " + TABLE + " (flag)");
		engine.execute("CREATE BITMAP INDEX bm_plan_color ON " + TABLE + " (color)");
		engine.execute("INSERT INTO " + TABLE
				+ " VALUES (1, 1, 10), (2, 0, 10), (3, 1, 20), (4, 1, 10), (5, 2, 30)");
	}

	@Test
	void eqInAndReturnCorrectRows() {
		final SqlResult eq = engine.execute("SELECT id FROM " + TABLE + " WHERE flag = 1");
		assertEquals(3, eq.rows().size());

		final SqlResult in = engine.execute("SELECT id FROM " + TABLE + " WHERE flag IN (0, 2)");
		assertEquals(2, in.rows().size());

		final SqlResult and = engine.execute(
				"SELECT id FROM " + TABLE + " WHERE flag = 1 AND color = 10");
		assertEquals(2, and.rows().size());
	}

	@Test
	void explainAnalyzeSurfacesIndexOrBitmapPlan() {
		assertExplainUsesIndexPath("EXPLAIN ANALYZE SELECT id FROM " + TABLE + " WHERE flag = 1");
		assertExplainUsesIndexPath("EXPLAIN ANALYZE SELECT id FROM " + TABLE + " WHERE flag IN (0, 2)");
		assertExplainUsesIndexPath(
				"EXPLAIN ANALYZE SELECT id FROM " + TABLE + " WHERE flag = 1 AND color = 10");
	}

	private void assertExplainUsesIndexPath(String sql) {
		final SqlResult result = engine.execute(sql);
		assertFalse(result.rows().isEmpty());
		boolean sawIndexPath = false;
		boolean sawTableScan = false;
		for (Object[] row : result.rows()) {
			final String kind = String.valueOf(row[0]);
			final String detail = String.valueOf(row[2]);
			final String combined = kind + " " + detail;
			if (combined.contains(BITMAP_KIND_SNIPPET)
					|| kind.contains("Index")
					|| "INDEX".equals(kind)
					|| combined.contains("Index Scan")) {
				sawIndexPath = true;
			}
			if (combined.contains(TABLE_SCAN_SNIPPET)) {
				sawTableScan = true;
			}
		}
		assertTrue(sawIndexPath, "expected Index/Bitmap plan node for " + sql);
		assertFalse(sawTableScan, "must not fall back to Table Scan for " + sql);
	}
}
