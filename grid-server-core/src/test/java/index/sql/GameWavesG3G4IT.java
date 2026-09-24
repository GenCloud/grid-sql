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
import org.genfork.grid.store.TableStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Game compatibility waves G3 composite PK and G4 UPDATE FROM integration coverage.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class GameWavesG3G4IT {
	private SqlEngine engine;
	private TableCatalog catalog;

	@BeforeEach
	void setUp() throws Exception {
		catalog = new TableCatalog(Files.createTempDirectory("game-g3-g4"));
		engine = new SqlEngine(catalog, null, 4);
	}

	@Test
	void compositePrimaryKeySupportsInsertGetDeleteAndForeignKey() {
		engine.execute("CREATE TABLE parent (world_id INT, object_id BIGINT, name VARCHAR, "
				+ "PRIMARY KEY (world_id, object_id))");
		engine.execute("CREATE TABLE child (id INT PRIMARY KEY, world_id INT, object_id BIGINT, "
				+ "FOREIGN KEY (world_id, object_id) REFERENCES parent (world_id, object_id))");
		engine.execute("INSERT INTO parent VALUES (1, 100, 'npc')");
		engine.execute("INSERT INTO child VALUES (10, 1, 100)");

		final TableStore parent = catalog.getStore("parent");
		assertNotNull(parent);
		final Object[] row = parent.getByPk(new Object[]{1, 100L});
		assertNotNull(row);
		assertEquals("npc", row[2]);
		assertThrows(IllegalStateException.class,
				() -> engine.execute("INSERT INTO child VALUES (11, 1, 999)"));
		engine.execute("DELETE FROM child WHERE id = 10");
		assertEquals(1L, parent.deleteByPk(new Object[]{1, 100L}));
		assertTrue(engine.execute("SELECT * FROM parent").rows().isEmpty());
	}

	@Test
	void updateFromUsesColumnEquality() {
		engine.execute("CREATE TABLE target_t (id INT PRIMARY KEY, match_key BIGINT, state VARCHAR)");
		engine.execute("CREATE INDEX target_t_match ON target_t (match_key)");
		engine.execute("CREATE TABLE source_t (id INT PRIMARY KEY, match_key BIGINT)");
		engine.execute("INSERT INTO target_t VALUES (1, 100, 'old'), (2, 200, 'old'), (3, 300, 'old')");
		engine.execute("INSERT INTO source_t VALUES (10, 100), (20, 300)");

		final SqlResult updated = engine.execute(
				"UPDATE target_t SET state = 'restored' FROM source_t "
						+ "WHERE target_t.match_key = source_t.match_key");

		assertEquals(2L, updated.rowsAffected());
		assertEquals("restored", engine.execute("SELECT state FROM target_t WHERE id = 1")
				.rows().getFirst()[0]);
		assertEquals("old", engine.execute("SELECT state FROM target_t WHERE id = 2")
				.rows().getFirst()[0]);
		assertEquals("restored", engine.execute("SELECT state FROM target_t WHERE id = 3")
				.rows().getFirst()[0]);
	}
}