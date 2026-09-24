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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * LAZY reopen after large OpLog (no full map reindex). Default 10k; override via
 * {@code -Dgrid.lazy.large.rows=100000} for stamp.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
@Tag("large")
class LazySelectLargeOpLogTest {

	private static final String TABLE = "lazy_large";
	private static final int SHARDS = 4;
	private static final String HYDRATE_LAZY = "LAZY";
	private static final String PROP_ROWS = "grid.lazy.large.rows";
	private static final int DEFAULT_ROWS = 10_000;

	@TempDir
	Path tempDir;

	@Test
	void selectStarAfterLargeOpLogLazyReopen() {
		final int rows = Integer.getInteger(PROP_ROWS, DEFAULT_ROWS);
		final Path dataDir = tempDir.resolve("lazy-large");
		try (SqlServerRuntime first = SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(SHARDS)
				.durability(true)
				.hydrateMode(HYDRATE_LAZY)
				.build()) {
			final SqlEngine engine = first.engine();
			engine.execute("CREATE TABLE " + TABLE + " (id INT PRIMARY KEY, v VARCHAR)");
			for (int i = 0; i < rows; i++) {
				engine.execute("INSERT INTO " + TABLE + " (id, v) VALUES (" + i + ", 'x')");
			}
		}

		try (SqlServerRuntime second = SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(SHARDS)
				.durability(true)
				.hydrateMode(HYDRATE_LAZY)
				.build()) {
			final SqlEngine engine = second.engine();
			final SqlResult byPk = engine.execute("SELECT v FROM " + TABLE + " WHERE id = 1");
			assertFalse(byPk.rows().isEmpty());
			final SqlResult all = engine.execute("SELECT id FROM " + TABLE);
			assertEquals(rows, all.rows().size());
		}
	}
}
