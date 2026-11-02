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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Catalog DDL epoch: remote stamp under apply, proposer floor, stale reject retained.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
class CatalogDdlEpochStampCharacterizationTest {
	private SqlEngine engine;
	private TableCatalog catalog;

	@BeforeEach
	void setUp() {
		catalog = new TableCatalog();
		engine = new SqlEngine(catalog, null, 4);
	}

	@Test
	void applyReplicatedDdlStampsRemoteEpochOnTableMeta() {
		engine.execute("CREATE TABLE epoch_local (id INT PRIMARY KEY)");
		final long applied = catalog.maxAppliedDdlEpoch();
		final long remote = applied + 10L;
		engine.applyReplicatedDdl(
				"CREATE TABLE epoch_peer (id INT PRIMARY KEY, n INT)",
				remote);
		assertTrue(catalog.exists("epoch_peer"));
		assertEquals(remote, catalog.getSchema("epoch_peer").schemaEpoch());
		assertEquals(remote, catalog.maxAppliedDdlEpoch());
	}

	@Test
	void nextEpochStaysAboveMaxAppliedAfterHighRemoteApply() {
		engine.execute("CREATE TABLE epoch_floor_a (id INT PRIMARY KEY)");
		engine.applyReplicatedDdl(
				"CREATE TABLE epoch_floor_b (id INT PRIMARY KEY)",
				500L);
		assertEquals(500L, catalog.maxAppliedDdlEpoch());
		engine.execute("CREATE TABLE epoch_floor_c (id INT PRIMARY KEY)");
		assertTrue(catalog.getSchema("epoch_floor_c").schemaEpoch() > 500L);
		assertTrue(catalog.maxAppliedDdlEpoch() > 500L);
	}

	@Test
	void noteAppliedHighThenLowerApplyStillRejects() {
		engine.execute("CREATE TABLE epoch_mono (id INT PRIMARY KEY)");
		catalog.noteAppliedDdlEpoch(433L);
		final IllegalStateException ex = assertThrows(IllegalStateException.class,
				() -> engine.applyReplicatedDdl(
						"CREATE TABLE epoch_stale_tip (id INT PRIMARY KEY)",
						193L));
		assertTrue(ex.getMessage().contains("stale schema epoch"));
		assertEquals(433L, catalog.maxAppliedDdlEpoch());
	}

	@Test
	void seedProposerFloorDoesNotMarkApplied() {
		catalog.seedProposerEpochFloor(900L);
		assertEquals(0L, catalog.maxAppliedDdlEpoch());
		assertTrue(catalog.currentEpoch() > 900L);
	}
}
