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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stale ALTER DROP COLUMN must remain rejected (no blind re-exec heal).
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
class CatalogDdlStaleAlterMustNotDestroyIT {
	private static final String TABLE = "stale_alter_t";

	private SqlEngine engine;
	private TableCatalog catalog;

	@BeforeEach
	void setUp() {
		catalog = new TableCatalog();
		engine = new SqlEngine(catalog, null, 4);
	}

	@Test
	void staleAlterDropColumnStillRejects() {
		engine.execute("CREATE TABLE " + TABLE + " (id INT PRIMARY KEY, keep_col INT, junk INT)");
		catalog.noteAppliedDdlEpoch(500L);
		final IllegalStateException ex = assertThrows(IllegalStateException.class,
				() -> engine.applyReplicatedDdl(
						"ALTER TABLE " + TABLE + " DROP COLUMN junk",
						193L));
		assertTrue(ex.getMessage().contains("stale schema epoch"));
		assertEquals(500L, catalog.maxAppliedDdlEpoch());
		assertEquals(3, catalog.requireSchema(TABLE).columnCount(),
				"column must survive rejected stale ALTER DROP");
		assertNotNull(catalog.requireSchema(TABLE).column("junk"));
	}
}
