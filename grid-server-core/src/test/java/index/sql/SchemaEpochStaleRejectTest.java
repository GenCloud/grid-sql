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
import org.springframework.context.support.GenericApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan F: catalog DDL epoch — peers reject stale schemaEpoch fail-closed.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class SchemaEpochStaleRejectTest {
	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(), null, 4);
	}

	@Test
	void localDdlAdvancesAppliedEpoch() {
		engine.execute("CREATE TABLE ep_t (id INT PRIMARY KEY)");
		assertTrue(engine.catalog().maxAppliedDdlEpoch() >= 1L);
		final long afterCreate = engine.catalog().maxAppliedDdlEpoch();
		engine.execute("ALTER TABLE ep_t ADD COLUMN note VARCHAR");
		assertTrue(engine.catalog().maxAppliedDdlEpoch() > afterCreate);
	}

	@Test
	void applyReplicatedDdlRejectsStaleEpoch() {
		engine.execute("CREATE TABLE ep_stale (id INT PRIMARY KEY)");
		engine.execute("ALTER TABLE ep_stale ADD COLUMN v VARCHAR");
		final long applied = engine.catalog().maxAppliedDdlEpoch();
		assertTrue(applied >= 2L);
		final IllegalStateException ex = assertThrows(IllegalStateException.class,
				() -> engine.applyReplicatedDdl("CREATE TABLE ghost (id INT PRIMARY KEY)", applied - 1L));
		assertTrue(ex.getMessage().contains("stale schema epoch"));
		assertEquals(applied, engine.catalog().maxAppliedDdlEpoch());
	}

	@Test
	void applyReplicatedDdlAcceptsNewerEpoch() {
		engine.execute("CREATE TABLE ep_ok (id INT PRIMARY KEY)");
		final long applied = engine.catalog().maxAppliedDdlEpoch();
		engine.applyReplicatedDdl("CREATE TABLE ep_peer (id INT PRIMARY KEY, n INT)", applied + 1L);
		assertTrue(engine.catalog().exists("ep_peer"));
		assertEquals(applied + 1L, engine.catalog().maxAppliedDdlEpoch());
	}
}