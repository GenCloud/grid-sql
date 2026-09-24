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

import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.SqlServerRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WS eviction + seal dump + OpLog truncate + LAZY reopen: SELECT star and PK must see all rows.
 *
 * @author: GenCloud
 * @date: 2026/05
 * @since: 1.0
 */
class EvictionSealSelectAfterRestartTest {

	private static final String TABLE = "evict_seal_sel";
	private static final int SHARDS = 2;
	private static final int ROW_COUNT = 128;
	private static final int WS_CAP = 16;
	private static final String HYDRATE_LAZY = "LAZY";

	@TempDir(cleanup = CleanupMode.NEVER)
	Path tempDir;

	@Test
	void selectAfterEvictSealTruncateLazyReopen() {
		final Path dataDir = tempDir.resolve("evict-seal");
		try (SqlServerRuntime first = SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(SHARDS)
				.durability(true)
				.hydrateMode(HYDRATE_LAZY)
				.workingSetMaxEntries(WS_CAP)
				.adaptiveDiskFirst(false)
				.build()) {
			final SqlEngine engine = first.engine();
			engine.execute("CREATE TABLE " + TABLE + " (id INT PRIMARY KEY, v VARCHAR)");
			for (int i = 0; i < ROW_COUNT; i++) {
				engine.execute("INSERT INTO " + TABLE + " (id, v) VALUES (" + i + ", 'r" + i + "')");
			}
			final ReplicationCoordinator coord = first.ownedReplication();
			assertNotNull(coord);
			final int sealedRows = coord.dumpDomainSnapshot(TABLE);
			assertTrue(sealedRows >= ROW_COUNT, "seal must retain full domain after eviction, got " + sealedRows);
		}

		try (SqlServerRuntime second = SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(SHARDS)
				.durability(true)
				.hydrateMode(HYDRATE_LAZY)
				.workingSetMaxEntries(WS_CAP)
				.adaptiveDiskFirst(false)
				.build()) {
			final SqlEngine engine = second.engine();
			final SqlResult byPk = engine.execute("SELECT v FROM " + TABLE + " WHERE id = 0");
			assertFalse(byPk.rows().isEmpty(), "PK SELECT empty after eviction+seal+LAZY reopen");
			assertEquals("r0", byPk.rows().getFirst()[0]);

			final SqlResult mid = engine.execute("SELECT v FROM " + TABLE + " WHERE id = " + (ROW_COUNT / 2));
			assertFalse(mid.rows().isEmpty());
			assertEquals("r" + (ROW_COUNT / 2), mid.rows().getFirst()[0]);

			final SqlResult all = engine.execute("SELECT id FROM " + TABLE);
			assertEquals(ROW_COUNT, all.rows().size(), "SELECT star lost rows after seal under WS eviction");
		}
	}
}