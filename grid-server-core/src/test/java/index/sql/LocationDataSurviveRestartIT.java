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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lab symptom: {@code item_data.location_data} PAPER_DOLL reverted to INVENTORY after cluster restart.
 * UPDATE (and ON CONFLICT DO UPDATE) must survive seal dump + LAZY reopen.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
class LocationDataSurviveRestartIT {

	private static final String TABLE = "item_loc_survive_t";
	private static final int SHARDS = 4;
	private static final int WS_CAP = 16;
	private static final int PK = 42;
	private static final String LOC_INVENTORY = "INVENTORY";
	private static final String LOC_PAPER_DOLL = "PAPER_DOLL";
	private static final String HYDRATE_LAZY = "LAZY";

	@TempDir(cleanup = CleanupMode.NEVER)
	Path tempDir;

	@Test
	void updatePaperDollSurvivesSealLazyReopen() {
		final Path dataDir = tempDir.resolve("loc-survive");
		try (SqlServerRuntime first = SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(SHARDS)
				.durability(true)
				.hydrateMode(HYDRATE_LAZY)
				.workingSetMaxEntries(WS_CAP)
				.adaptiveDiskFirst(false)
				.build()) {
			final SqlEngine engine = first.engine();
			engine.execute(
					"CREATE TABLE " + TABLE + " ("
							+ "id INT PRIMARY KEY, "
							+ "owner_id INT, "
							+ "location_data VARCHAR)");
			engine.execute(
					"INSERT INTO " + TABLE + " (id, owner_id, location_data) VALUES ("
							+ PK + ", 7, '" + LOC_INVENTORY + "')");
			engine.execute(
					"UPDATE " + TABLE + " SET location_data = '" + LOC_PAPER_DOLL
							+ "' WHERE id = " + PK);
			assertEquals(LOC_PAPER_DOLL, readLocation(engine));

			final ReplicationCoordinator coord = first.replication();
			assertTrue(coord != null);
			assertTrue(coord.dumpDomainSnapshot(TABLE) >= 1);
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
			final SqlResult byPk = engine.execute(
					"SELECT location_data FROM " + TABLE + " WHERE id = " + PK);
			assertFalse(byPk.rows().isEmpty(), "row missing after LAZY reopen");
			assertEquals(LOC_PAPER_DOLL, String.valueOf(byPk.rows().getFirst()[0]),
					"PAPER_DOLL must survive seal+LAZY restart (lab: reverted to INVENTORY)");
		}
	}

	@Test
	void onConflictPaperDollSurvivesSealLazyReopen() {
		final Path dataDir = tempDir.resolve("loc-oc-survive");
		try (SqlServerRuntime first = SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(SHARDS)
				.durability(true)
				.hydrateMode(HYDRATE_LAZY)
				.build()) {
			final SqlEngine engine = first.engine();
			engine.execute(
					"CREATE TABLE " + TABLE + " ("
							+ "id INT PRIMARY KEY, "
							+ "owner_id INT, "
							+ "location_data VARCHAR)");
			engine.execute(
					"INSERT INTO " + TABLE + " (id, owner_id, location_data) VALUES ("
							+ PK + ", 7, '" + LOC_INVENTORY + "')");
			engine.execute(
					"INSERT INTO " + TABLE + " (id, owner_id, location_data) VALUES ("
							+ PK + ", 7, '" + LOC_PAPER_DOLL + "') "
							+ "ON CONFLICT (id) DO UPDATE SET location_data = '" + LOC_PAPER_DOLL + "'");
			assertEquals(LOC_PAPER_DOLL, readLocation(engine));
			final ReplicationCoordinator coord = first.replication();
			assertTrue(coord != null);
			assertTrue(coord.dumpDomainSnapshot(TABLE) >= 1);
		}

		try (SqlServerRuntime second = SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(SHARDS)
				.durability(true)
				.hydrateMode(HYDRATE_LAZY)
				.build()) {
			assertEquals(LOC_PAPER_DOLL, readLocation(second.engine()));
		}
	}

	private static String readLocation(SqlEngine engine) {
		final SqlResult r = engine.execute(
				"SELECT location_data FROM " + TABLE + " WHERE id = " + PK);
		assertFalse(r.rows().isEmpty());
		return String.valueOf(r.rows().getFirst()[0]);
	}
}
