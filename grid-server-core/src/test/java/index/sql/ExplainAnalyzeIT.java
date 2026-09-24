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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQL {@code EXPLAIN ANALYZE SELECT} returns plan rows from {@link org.genfork.grid.query.plan.ExplainQuery}.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class ExplainAnalyzeIT {

	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		final GridConfigurationProperties props = new GridConfigurationProperties();
		props.getDurability().setEnabled(false);
		props.getReplication().setEnabled(false);
		final TableCatalog catalog = new TableCatalog();
		final ReplicationCoordinator replication = new ReplicationCoordinator(props);
		engine = new SqlEngine(catalog, replication, props.getSql().getDefaultShards());
		engine.execute("CREATE TABLE ea_t (id INT PRIMARY KEY, flag INT)");
		engine.execute("CREATE BITMAP INDEX ea_flag ON ea_t (flag)");
		engine.execute("INSERT INTO ea_t VALUES (1, 1), (2, 0), (3, 1)");
	}

	@Test
	void explainAnalyzeSelectReturnsPlanRows() {
		final SqlResult result = engine.execute("EXPLAIN ANALYZE SELECT id FROM ea_t WHERE flag = 1");
		assertFalse(result.rows().isEmpty());
		boolean sawPlan = false;
		boolean sawSummary = false;
		for (Object[] row : result.rows()) {
			final String kind = String.valueOf(row[0]);
			if ("SUMMARY".equals(kind)) {
				sawSummary = true;
				assertTrue(String.valueOf(row[2]).contains("timeMs="));
				assertTrue(String.valueOf(row[2]).contains("estCost="), "SUMMARY should include estCost");
			} else if (kind.contains("Bitmap") || kind.contains("Index") || kind.contains("PARSE")) {
				sawPlan = true;
			}
		}
		assertTrue(sawSummary, "expected SUMMARY row");
		assertTrue(sawPlan, "expected plan node row");
	}
}