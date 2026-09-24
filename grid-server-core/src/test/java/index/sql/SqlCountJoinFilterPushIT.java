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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Load-SLO shaped COUNT JOIN: ON id = a_id WHERE a_id = ? must not full-scan B.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class SqlCountJoinFilterPushIT {

	@Test
	void countJoinWhereJoinColUsesIndexProbe() {
		final SqlEngine engine = new SqlEngine(new TableCatalog(), null, 4);
		engine.execute("CREATE TABLE load_slo_a (id INT PRIMARY KEY, val VARCHAR, n INT)");
		engine.execute("CREATE TABLE load_slo_b (bid INT PRIMARY KEY, a_id INT, label VARCHAR)");
		engine.execute("CREATE INDEX idx_load_slo_b_a_id ON load_slo_b (a_id)");
		for (int i = 1; i <= 500; i++) {
			engine.execute("INSERT INTO load_slo_a (id, val, n) VALUES (" + i + ", 'seed-" + i + "', 0)");
			engine.execute("INSERT INTO load_slo_b (bid, a_id, label) VALUES (" + i + ", " + i + ", 'b-" + i + "')");
		}
		final long t0 = System.nanoTime();
		final SqlResult r = engine.execute(
				"SELECT COUNT(*) FROM load_slo_a JOIN load_slo_b ON id = a_id WHERE a_id = 42");
		final long ms = (System.nanoTime() - t0) / 1_000_000L;
		assertEquals(1, r.rows().size());
		assertEquals(1L, ((Number) r.rows().getFirst()[0]).longValue());
		assertTrue(ms < 500L, "selective count-join should be sub-500ms, took " + ms + "ms");
	}
}