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

import java.util.List;

import org.genfork.grid.serial.SqlWireUtil;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.store.TableStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * STRICT UNIQUE owner replace: second row with same unique columns wins; ghost PK tombstoned.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
class StrictUniqueGhostApplyIT {
	private static final int SHARDS = 4;
	private static final int PK_A = 10;
	private static final int PK_B = 20;
	private static final String TABLE = "uniq_pair_t";
	private static final String K1 = "alpha";
	private static final String K2 = "beta";

	@Test
	void upsertReplacesStrictUniqueOwnerAndTomestonesGhost() {
		final SqlEngine engine = SqlBenchHelper.createEngine(SHARDS);
		engine.execute(
				"CREATE TABLE " + TABLE + " ("
						+ "id INT PRIMARY KEY, "
						+ "k1 VARCHAR NOT NULL, "
						+ "k2 VARCHAR NOT NULL, "
						+ "ts_col TIMESTAMP)");
		engine.execute(
				"CREATE UNIQUE INDEX " + TABLE + "_u "
						+ "ON " + TABLE + " (k1, k2)");
		final TableStore store = SqlBenchHelper.store(engine, TABLE);

		engine.execute(
				"INSERT INTO " + TABLE + " (id, k1, k2) VALUES ("
						+ PK_A + ", '" + K1 + "', '" + K2 + "')");
		engine.execute(
				"INSERT INTO " + TABLE + " (id, k1, k2) VALUES ("
						+ PK_B + ", '" + K1 + "', '" + K2 + "') "
						+ "ON CONFLICT (k1, k2) DO UPDATE SET id = " + PK_B);

		final byte[] keyB = store.keyBytesForPk(PK_B);
		final List<byte[]> hits = store.lookupEqKeys(
				List.of("k1", "k2"),
				new byte[][]{
						SqlWireUtil.toGenericArray(K1),
						SqlWireUtil.toGenericArray(K2)
				});
		assertEquals(1, hits.size());
		assertEquals(0, java.util.Arrays.compareUnsigned(keyB, hits.getFirst()));

		final SqlResult rows = engine.execute(
				"SELECT id FROM " + TABLE + " WHERE k1 = '" + K1 + "' AND k2 = '" + K2 + "'");
		assertEquals(1, rows.rows().size());
		assertEquals(PK_B, ((Number) rows.rows().getFirst()[0]).intValue());
		assertEquals(0, engine.execute("SELECT id FROM " + TABLE + " WHERE id = " + PK_A).rows().size());
	}
}
