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

import org.genfork.grid.catalog.IndexDef;
import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.context.config.GridConfigurationProperties;
import org.genfork.grid.mem.index.IndexType;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.sql.SqlEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * P1.7: multi-column {@code CREATE BITMAP INDEX} must reject (not silent drop).
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class BitmapMultiColumnRejectIT {
	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		final GridConfigurationProperties props = new GridConfigurationProperties();
		props.getDurability().setEnabled(false);
		props.getReplication().setEnabled(false);
		final TableCatalog catalog = new TableCatalog();
		final ReplicationCoordinator replication = new ReplicationCoordinator(props);
		engine = new SqlEngine(catalog, replication, props.getSql().getDefaultShards());
		engine.execute("CREATE TABLE bm_multi (id INT PRIMARY KEY, a INT, b INT)");
	}

	@Test
	void createBitmapIndexOnTwoColumnsThrows() {
		final IllegalArgumentException ex = assertThrows(
				IllegalArgumentException.class,
				() -> engine.execute("CREATE BITMAP INDEX bm_ab ON bm_multi (a, b)"));
		assertEquals(IndexDef.BITMAP_SINGLE_COLUMN_ONLY, ex.getMessage());
	}

	@Test
	void indexDefRejectsMultiColumnBitmap() {
		final IllegalArgumentException ex = assertThrows(
				IllegalArgumentException.class,
				() -> IndexDef.of("bm_ab", IndexType.BITMAP, "a", "b"));
		assertEquals(IndexDef.BITMAP_SINGLE_COLUMN_ONLY, ex.getMessage());
	}

	@Test
	void createCompositeBptreeIndexStillAllowed() {
		engine.execute("CREATE INDEX idx_ab ON bm_multi (a, b)");
		engine.execute("INSERT INTO bm_multi (id, a, b) VALUES (1, 1, 2)");
		assertEquals(1, engine.execute("SELECT id FROM bm_multi WHERE a = 1 AND b = 2").rows().size());
	}
}
