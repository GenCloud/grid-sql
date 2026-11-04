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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Duplicate schemaEpoch without catalog effect must execute CREATE* (heal), not silent-skip.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
class CatalogDdlDuplicateEpochMissingEffectIT {
	private static final String BASE = "dup_eff_base";
	private static final String VIEW = "dup_eff_view";
	private static final long EPOCH = 900L;

	private SqlEngine engine;
	private TableCatalog catalog;

	@BeforeEach
	void setUp() {
		catalog = new TableCatalog();
		engine = new SqlEngine(catalog, null, 4);
	}

	@Test
	void duplicateEpochCreateViewHealsMissingEffect() {
		engine.execute("CREATE TABLE " + BASE + " (id INT PRIMARY KEY, v INT)");
		catalog.noteAppliedDdlEpoch(EPOCH);
		assertNull(catalog.getView(VIEW));
		engine.applyReplicatedDdl(
				"CREATE VIEW " + VIEW + " AS SELECT id, v FROM " + BASE,
				EPOCH);
		assertNotNull(catalog.getView(VIEW), "missing effect under duplicate epoch must create VIEW");
		assertTrue(catalog.maxAppliedDdlEpoch() >= EPOCH);
	}
}
