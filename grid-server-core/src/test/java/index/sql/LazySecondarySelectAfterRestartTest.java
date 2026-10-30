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
 * LAZY reopen must see rows via secondary-index EQ (lobby {@code account_name} shape),
 * not only PK / {@code SELECT *} — otherwise game restart cold-load looks empty while
 * PK get still finds the character.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
class LazySecondarySelectAfterRestartTest {

	private static final String TABLE = "lazy_sec";
	private static final String INDEX = "lazy_sec_acct";
	private static final int SHARDS = 4;
	private static final String HYDRATE_LAZY = "LAZY";

	@TempDir
	Path tempDir;

	@Test
	void secondaryEqSeesRowsAfterLazyReopen() {
		final Path dataDir = tempDir.resolve("lazy-sec");
		try (SqlServerRuntime first = SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(SHARDS)
				.durability(true)
				.hydrateMode(HYDRATE_LAZY)
				.build()) {
			final SqlEngine engine = first.engine();
			engine.execute("CREATE TABLE " + TABLE
					+ " (id INT PRIMARY KEY, account_name VARCHAR, server_id INT)");
			engine.execute("CREATE INDEX " + INDEX + " ON " + TABLE + " (account_name)");
			engine.execute("INSERT INTO " + TABLE
					+ " (id, account_name, server_id) VALUES (1, 'acc1', 3)");
			engine.execute("INSERT INTO " + TABLE
					+ " (id, account_name, server_id) VALUES (2, 'acc2', 3)");
		}

		try (SqlServerRuntime second = SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(SHARDS)
				.durability(true)
				.hydrateMode(HYDRATE_LAZY)
				.build()) {
			final SqlEngine engine = second.engine();

			final SqlResult byPk = engine.execute(
					"SELECT account_name FROM " + TABLE + " WHERE id = 1");
			assertFalse(byPk.rows().isEmpty(), "PK SELECT empty after LAZY reopen");
			assertEquals("acc1", byPk.rows().getFirst()[0]);

			final SqlResult byAcct = engine.execute(
					"SELECT id FROM " + TABLE
							+ " WHERE account_name = 'acc1' AND server_id = 3");
			assertFalse(byAcct.rows().isEmpty(),
					"secondary EQ empty after LAZY reopen (lobby account_name shape)");
			assertEquals(1, ((Number) byAcct.rows().getFirst()[0]).intValue());

			final SqlResult all = engine.execute("SELECT id FROM " + TABLE);
			assertEquals(2, all.rows().size(), "SELECT * partial after LAZY reopen");
		}
	}
}
