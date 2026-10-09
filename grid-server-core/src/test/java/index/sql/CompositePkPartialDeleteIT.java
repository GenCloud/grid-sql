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

import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.SqlServerRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Partial WHERE DELETE on composite PRIMARY KEY must remove map rows (DBeaver Save contract).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.1
 */
class CompositePkPartialDeleteIT {
	private static final String TABLE = "bitset_range";
	private static final String DDL = "CREATE TABLE " + TABLE
			+ " (server_id INT, biset_type VARCHAR, min_value INT NOT NULL, max_value INT NOT NULL,"
			+ " PRIMARY KEY (server_id, biset_type))";
	/** Secondary on PK leading column (live-shaped; PK already owns composite tree). */
	private static final String IDX =
			"CREATE INDEX idx_bitset_server ON " + TABLE + " (server_id)";

	@TempDir
	Path tempDir;

	@Test
	void partialDeleteRemovesAllMatchingAndAllowsReinsert() {
		final SqlEngine engine = SqlBenchHelper.createEngine(4);
		engine.execute(DDL);
		engine.execute(IDX);
		engine.execute("INSERT INTO " + TABLE + " VALUES (3, 'ITEMS', 1, 2)");
		engine.execute("INSERT INTO " + TABLE + " VALUES (3, 'OTHER', 3, 4)");
		engine.execute("INSERT INTO " + TABLE + " VALUES (125, 'ITEMS', 5, 6)");

		final SqlResult deleted = engine.execute("DELETE FROM " + TABLE + " WHERE server_id = 3");
		assertEquals(2L, deleted.rowsAffected());

		final SqlResult left = engine.execute("SELECT server_id FROM " + TABLE + " WHERE server_id = 3");
		assertEquals(0, left.rows().size(), "partial DELETE must clear SELECT for server_id=3");

		engine.execute("INSERT INTO " + TABLE + " VALUES (3, 'ITEMS', 1, 2)");
		final SqlResult again = engine.execute(
				"SELECT biset_type FROM " + TABLE + " WHERE server_id = 3 AND biset_type = 'ITEMS'");
		assertEquals(1, again.rows().size());
		assertEquals("ITEMS", again.rows().getFirst()[0]);

		final SqlResult keep = engine.execute("SELECT server_id FROM " + TABLE + " WHERE server_id = 125");
		assertEquals(1, keep.rows().size());
	}

	@Test
	void partialDeleteAfterFullHydrateRemovesAndAllowsReinsert() {
		final Path dataDir = tempDir.resolve("comp-pk-del-hydrate");
		try (SqlServerRuntime first = SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(4)
				.durability(true)
				.hydrateMode("FULL")
				.build()) {
			first.engine().execute(DDL);
			first.engine().execute(IDX);
			first.engine().execute("INSERT INTO " + TABLE + " VALUES (3, 'ITEMS', 100000001, 600000000)");
			first.engine().execute("INSERT INTO " + TABLE + " VALUES (125, 'ITEMS', 5, 6)");
		}

		try (SqlServerRuntime second = SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(4)
				.durability(true)
				.hydrateMode("FULL")
				.build()) {
			final SqlEngine engine = second.engine();
			final SqlResult before = engine.execute(
					"SELECT server_id, biset_type FROM " + TABLE + " ORDER BY server_id");
			assertEquals(2, before.rows().size());

			final SqlResult deleted = engine.execute("DELETE FROM " + TABLE + " WHERE server_id = 3");
			assertTrue(deleted.rowsAffected() >= 1L, "partial DELETE affected=" + deleted.rowsAffected());

			final SqlResult left = engine.execute("SELECT server_id FROM " + TABLE + " WHERE server_id = 3");
			assertEquals(0, left.rows().size(), "SELECT must not see deleted server_id=3");

			engine.execute("INSERT INTO " + TABLE + " VALUES (3, 'ITEMS', 1, 2)");
			final SqlResult point = engine.execute(
					"SELECT min_value FROM " + TABLE + " WHERE server_id = 3 AND biset_type = 'ITEMS'");
			assertEquals(1, point.rows().size());
			assertEquals(1, ((Number) point.rows().getFirst()[0]).intValue());
		}
	}
}
