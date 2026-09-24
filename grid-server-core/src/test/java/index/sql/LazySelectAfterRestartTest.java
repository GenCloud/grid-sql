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
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * LAZY reopen: SELECT by PK and SELECT * must both see committed rows (OpLog hydrate + sync reindex).
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class LazySelectAfterRestartTest {

	private static final String TABLE = "lazy_sel";
	private static final int SHARDS = 4;
	private static final String HYDRATE_LAZY = "LAZY";

	@TempDir
	Path tempDir;

	@Test
	void selectPkAndSelectStarSeeRowsAfterLazyReopen() {
		final Path dataDir = tempDir.resolve("lazy-select");
		try (SqlServerRuntime first = SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(SHARDS)
				.durability(true)
				.hydrateMode(HYDRATE_LAZY)
				.build()) {
			final SqlEngine engine = first.engine();
			engine.execute("CREATE TABLE " + TABLE + " (id INT PRIMARY KEY, v VARCHAR)");
			engine.execute("INSERT INTO " + TABLE + " (id, v) VALUES (1, 'a')");
			engine.execute("INSERT INTO " + TABLE + " (id, v) VALUES (2, 'b')");
		}

		try (SqlServerRuntime second = SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(SHARDS)
				.durability(true)
				.hydrateMode(HYDRATE_LAZY)
				.build()) {
			final SqlEngine engine = second.engine();
			final SqlResult byPk = engine.execute("SELECT v FROM " + TABLE + " WHERE id = 1");
			assertFalse(byPk.rows().isEmpty(), "PK SELECT empty after LAZY reopen");
			assertEquals("a", byPk.rows().getFirst()[0]);

			final SqlResult all = engine.execute("SELECT id, v FROM " + TABLE);
			assertEquals(2, all.rows().size(), "SELECT * empty/partial after LAZY reopen");
		}
	}
}