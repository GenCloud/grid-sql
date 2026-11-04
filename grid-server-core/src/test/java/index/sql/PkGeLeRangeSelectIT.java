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

import java.nio.file.Path;

import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.SqlServerRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Multi-leaf PK range: {@code id >= lo AND id <= hi} must match BETWEEN / COUNT / bare LE.
 * <p>
 * Repro for IdPoolManager-style jOOQ GE∧LE (not SQL BETWEEN).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
class PkGeLeRangeSelectIT {
	private static final String TABLE = "oid_pool_range";
	private static final int SHARDS = 2;
	/** Force multi-leaf PK BPTree (DEFAULT_ORDER=36). */
	private static final int ROW_COUNT = 200;
	private static final int RANGE_LO = 0;
	private static final int RANGE_HI = 600_000_000;
	private static final int MID_HI = 99;

	@TempDir
	Path tempDir;

	@Test
	void geAndLeSelectMatchesBetweenAndCount() {
		final Path dataDir = tempDir.resolve("pk-ge-le");
		try (SqlServerRuntime runtime = SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(SHARDS)
				.durability(false)
				.build()) {
			final SqlEngine engine = runtime.engine();
			engine.execute("CREATE TABLE " + TABLE + " (id INT PRIMARY KEY, label VARCHAR)");
			for (int id = 0; id < ROW_COUNT; id++) {
				engine.execute("INSERT INTO " + TABLE + " (id, label) VALUES (" + id + ", 'r')");
			}

			final int countAll = scalarCount(engine, "SELECT COUNT(*) FROM " + TABLE);
			assertEquals(ROW_COUNT, countAll);

			final int countRange = scalarCount(engine,
					"SELECT COUNT(*) FROM " + TABLE
							+ " WHERE id >= " + RANGE_LO + " AND id <= " + RANGE_HI);
			assertEquals(ROW_COUNT, countRange);

			final int selectGeLe = engine.execute(
					"SELECT id FROM " + TABLE
							+ " WHERE id >= " + RANGE_LO + " AND id <= " + RANGE_HI)
					.rows().size();
			final int selectBetween = engine.execute(
					"SELECT id FROM " + TABLE
							+ " WHERE id BETWEEN " + RANGE_LO + " AND " + RANGE_HI)
					.rows().size();
			final int selectLe = engine.execute(
					"SELECT id FROM " + TABLE + " WHERE id <= " + RANGE_HI)
					.rows().size();
			final int selectLt = engine.execute(
					"SELECT id FROM " + TABLE + " WHERE id < " + (RANGE_HI + 1))
					.rows().size();

			assertEquals(ROW_COUNT, selectGeLe, "GE∧LE SELECT incomplete vs COUNT");
			assertEquals(ROW_COUNT, selectBetween, "BETWEEN SELECT incomplete");
			assertEquals(ROW_COUNT, selectLe, "bare LE SELECT incomplete");
			assertEquals(ROW_COUNT, selectLt, "bare LT SELECT incomplete");

			final int midGeLe = engine.execute(
					"SELECT id FROM " + TABLE
							+ " WHERE id >= 0 AND id <= " + MID_HI)
					.rows().size();
			assertEquals(MID_HI + 1, midGeLe);
		}
	}

	@Test
	void geAndLeSurvivesLazyReopen() {
		final Path dataDir = tempDir.resolve("pk-ge-le-lazy");
		try (SqlServerRuntime first = SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(SHARDS)
				.durability(true)
				.hydrateMode("LAZY")
				.build()) {
			final SqlEngine engine = first.engine();
			engine.execute("CREATE TABLE " + TABLE + " (id INT PRIMARY KEY, label VARCHAR)");
			for (int id = 0; id < ROW_COUNT; id++) {
				engine.execute("INSERT INTO " + TABLE + " (id, label) VALUES (" + id + ", 'r')");
			}
		}

		try (SqlServerRuntime second = SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(SHARDS)
				.durability(true)
				.hydrateMode("LAZY")
				.build()) {
			final SqlEngine engine = second.engine();
			final int countRange = scalarCount(engine,
					"SELECT COUNT(*) FROM " + TABLE
							+ " WHERE id >= " + RANGE_LO + " AND id <= " + RANGE_HI);
			final int selectGeLe = engine.execute(
					"SELECT id FROM " + TABLE
							+ " WHERE id >= " + RANGE_LO + " AND id <= " + RANGE_HI)
					.rows().size();
			assertEquals(ROW_COUNT, countRange);
			assertEquals(ROW_COUNT, selectGeLe, "GE∧LE SELECT incomplete after LAZY reopen");
		}
	}

	private static int scalarCount(SqlEngine engine, String sql) {
		final SqlResult result = engine.execute(sql);
		final Object cell = result.rows().getFirst()[0];
		return ((Number) cell).intValue();
	}
}
