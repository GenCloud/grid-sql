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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DROP COLUMN missing must no-op on catalog recover / replicated DDL; live reject without IF EXISTS.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
class CatalogDdlDropColumnIdempotentReplayIT {
	private static final String TABLE = "drop_col_idem_t";

	private SqlEngine engine;
	private TableCatalog catalog;

	@BeforeEach
	void setUp() {
		catalog = new TableCatalog();
		engine = new SqlEngine(catalog, null, 4);
	}

	@Test
	void replicatedDropColumnMissingIsIdempotent() {
		engine.execute("CREATE TABLE " + TABLE + " (id INT PRIMARY KEY, keep_col INT, junk INT)");
		engine.execute("ALTER TABLE " + TABLE + " DROP COLUMN junk");
		assertNull(catalog.requireSchema(TABLE).column("junk"));
		assertEquals(2, catalog.requireSchema(TABLE).columnCount());

		final long epoch = catalog.maxAppliedDdlEpoch() + 1L;
		engine.applyReplicatedDdl("ALTER TABLE " + TABLE + " DROP COLUMN junk", epoch);

		assertNull(catalog.requireSchema(TABLE).column("junk"));
		assertEquals(2, catalog.requireSchema(TABLE).columnCount());
		assertNotNullKeepCol();
	}

	@Test
	void liveDropColumnMissingStillRejects() {
		engine.execute("CREATE TABLE " + TABLE + " (id INT PRIMARY KEY, keep_col INT)");
		final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> engine.execute("ALTER TABLE " + TABLE + " DROP COLUMN junk"));
		assertTrue(ex.getMessage().contains("Unknown column junk"));
		assertEquals(2, catalog.requireSchema(TABLE).columnCount());
	}

	@Test
	void dropColumnIfExistsMissingIsNoOp() {
		engine.execute("CREATE TABLE " + TABLE + " (id INT PRIMARY KEY, keep_col INT)");
		engine.execute("ALTER TABLE " + TABLE + " DROP COLUMN IF EXISTS junk");
		assertEquals(2, catalog.requireSchema(TABLE).columnCount());
		assertNotNullKeepCol();
	}

	private void assertNotNullKeepCol() {
		assertEquals("keep_col", catalog.requireSchema(TABLE).column("keep_col").name());
	}
}
