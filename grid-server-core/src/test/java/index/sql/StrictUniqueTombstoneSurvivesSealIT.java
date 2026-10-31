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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STRICT UNIQUE displace tombstone must survive seal dump + LAZY reopen (single owner).
 * <p>
 * Product gap: RAM sealed-delete tombstone was skipped by {@code forEachCommitted} while
 * prior sealed still contributed the loser PK into the dump merge map.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
class StrictUniqueTombstoneSurvivesSealIT {
	private static final int SHARDS = 4;
	private static final String HYDRATE_LAZY = "LAZY";
	private static final String TABLE = "uniq_seal_t";
	private static final String K1 = "slot_a";
	private static final String K2 = "slot_b";
	private static final int PK_LOSER = 101;
	private static final int PK_WINNER = 202;
	@TempDir(cleanup = CleanupMode.NEVER)
	Path tempDir;

	@Test
	void displaceThenSealLazyReopenKeepsSingleOwner() {
		final Path dataDir = tempDir.resolve("uniq-seal-displace");
		try (SqlServerRuntime first = openLazy(dataDir)) {
			final SqlEngine engine = first.engine();
			engine.execute(
					"CREATE TABLE " + TABLE + " ("
							+ "id INT PRIMARY KEY, "
							+ "k1 VARCHAR NOT NULL, "
							+ "k2 VARCHAR NOT NULL)");
			engine.execute(
					"CREATE UNIQUE INDEX " + TABLE + "_u ON " + TABLE + " (k1, k2)");
			engine.execute(
					"INSERT INTO " + TABLE + " (id, k1, k2) VALUES ("
							+ PK_LOSER + ", '" + K1 + "', '" + K2 + "')");
			final ReplicationCoordinator coord = first.replication();
			assertNotNull(coord);
			assertTrue(coord.dumpDomainSnapshot(TABLE) >= 1);

			engine.execute(
					"INSERT INTO " + TABLE + " (id, k1, k2) VALUES ("
							+ PK_WINNER + ", '" + K1 + "', '" + K2 + "') "
							+ "ON CONFLICT (k1, k2) DO UPDATE SET id = " + PK_WINNER);
			assertEquals(1, countByUnique(engine));
			assertEquals(0, countById(engine, PK_LOSER));
			assertTrue(coord.dumpDomainSnapshot(TABLE) >= 1);
		}

		try (SqlServerRuntime second = openLazy(dataDir)) {
			final SqlEngine engine = second.engine();
			assertEquals(1, countByUnique(engine),
					"STRICT UNIQUE must keep a single owner after seal+LAZY reopen");
			final SqlResult rows = engine.execute(
					"SELECT id FROM " + TABLE + " WHERE k1 = '" + K1 + "' AND k2 = '" + K2 + "'");
			assertEquals(1, rows.rows().size());
			assertEquals(PK_WINNER, ((Number) rows.rows().getFirst()[0]).intValue());
			assertEquals(0, countById(engine, PK_LOSER),
					"STRICT loser PK must not reappear from seal merge after displace");
		}
	}

	private static int countByUnique(SqlEngine engine) {
		final SqlResult r = engine.execute(
				"SELECT COUNT(*) FROM " + TABLE
						+ " WHERE k1 = '" + K1 + "' AND k2 = '" + K2 + "'");
		return ((Number) r.rows().getFirst()[0]).intValue();
	}

	private static int countById(SqlEngine engine, int id) {
		final SqlResult r = engine.execute(
				"SELECT COUNT(*) FROM " + TABLE + " WHERE id = " + id);
		return ((Number) r.rows().getFirst()[0]).intValue();
	}

	private static SqlServerRuntime openLazy(Path dataDir) {
		return SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(SHARDS)
				.durability(true)
				.hydrateMode(HYDRATE_LAZY)
				.build();
	}
}
