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
 * Committed VARCHAR loc UPDATE must survive graceful sealed shutdown dump + LAZY reopen.
 * <p>
 * Lab: sequential Ctrl+C on HA nodes left some rows at an older loc value after restart.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
class CommittedLocSurvivesGracefulSealStopIT {
	private static final int SHARDS = 4;
	private static final int WS_CAP = 8;
	private static final int ROW_COUNT = 24;
	private static final String TABLE = "loc_graceful_t";
	private static final String LOC_STORED = "STORED";
	private static final String LOC_EQUIPPED = "EQUIPPED";
	private static final String HYDRATE_LAZY = "LAZY";

	@TempDir(cleanup = CleanupMode.NEVER)
	Path tempDir;

	@Test
	void updateLocSurvivesGracefulStopDumpAndLazyReopen() {
		final Path dataDir = tempDir.resolve("loc-graceful");
		try (SqlServerRuntime first = openLazy(dataDir)) {
			final SqlEngine engine = first.engine();
			engine.execute(
					"CREATE TABLE " + TABLE + " ("
							+ "id INT PRIMARY KEY, "
							+ "loc VARCHAR)");
			for (int id = 1; id <= ROW_COUNT; id++) {
				engine.execute(
						"INSERT INTO " + TABLE + " (id, loc) VALUES ("
								+ id + ", '" + LOC_STORED + "')");
			}
			final ReplicationCoordinator coord = first.replication();
			assertNotNull(coord);
			assertTrue(coord.dumpDomainSnapshot(TABLE) >= 1);

			for (int id = 1; id <= ROW_COUNT; id++) {
				engine.execute(
						"UPDATE " + TABLE + " SET loc = '" + LOC_EQUIPPED
								+ "' WHERE id = " + id);
			}
			assertEquals(ROW_COUNT, countLoc(engine, LOC_EQUIPPED));

			// Product graceful stop path: sealed dump with truncateOpLog=false.
			coord.stop();
		}

		try (SqlServerRuntime second = openLazy(dataDir)) {
			final SqlEngine engine = second.engine();
			assertEquals(ROW_COUNT, countLoc(engine, LOC_EQUIPPED),
					"all committed EQUIPPED rows must survive graceful seal stop + LAZY reopen");
			assertEquals(0, countLoc(engine, LOC_STORED));
			for (int id = 1; id <= ROW_COUNT; id++) {
				final SqlResult row = engine.execute(
						"SELECT loc FROM " + TABLE + " WHERE id = " + id);
				assertFalse(row.rows().isEmpty(), "missing id=" + id);
				assertEquals(LOC_EQUIPPED, String.valueOf(row.rows().getFirst()[0]),
						"id=" + id);
			}
		}
	}

	private static int countLoc(SqlEngine engine, String loc) {
		final SqlResult r = engine.execute(
				"SELECT COUNT(*) FROM " + TABLE + " WHERE loc = '" + loc + "'");
		return ((Number) r.rows().getFirst()[0]).intValue();
	}

	private static SqlServerRuntime openLazy(Path dataDir) {
		return SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(SHARDS)
				.durability(true)
				.hydrateMode(HYDRATE_LAZY)
				.workingSetMaxEntries(WS_CAP)
				.adaptiveDiskFirst(false)
				.build();
	}
}
