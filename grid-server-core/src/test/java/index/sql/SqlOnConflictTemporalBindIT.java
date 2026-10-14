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

import java.nio.file.Files;
import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.SqlSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Engine-level ON CONFLICT temporal binds (no JDBC) — isolate binder vs JDBC.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
class SqlOnConflictTemporalBindIT {
	private static final String UPSERT =
			"INSERT INTO pledge_game_season (id, season, step, prev_winner_id, prev_winner_got_item, updated_at) "
					+ "VALUES (?, ?, ?, ?, ?, ?) "
					+ "ON CONFLICT (id) DO UPDATE SET season = ?, step = ?, prev_winner_id = ?, "
					+ "prev_winner_got_item = ?, updated_at = ?";

	private SqlEngine engine;

	@BeforeEach
	void setUp() throws Exception {
		engine = new SqlEngine(new TableCatalog(Files.createTempDirectory("onconflict-ts")), null, 4);
		engine.execute(
				"CREATE TABLE pledge_game_season ("
						+ "id INT PRIMARY KEY, season INT, step INT, prev_winner_id INT, "
						+ "prev_winner_got_item BOOLEAN, updated_at TIMESTAMP)");
	}

	@AfterEach
	void tearDown() {
		engine = null;
	}

	@Test
	void localDateTimeBindsOnConflict() {
		final LocalDateTime wall = LocalDateTime.of(2026, 10, 10, 11, 28, 8);
		final Object[] binds = new Object[]{1, 2, 3, 4, true, wall, 5, 6, 7, false, wall};
		final SqlResult first = engine.execute((SqlSession) null, UPSERT, binds);
		assertTrue(first.rowsAffected() >= 1L);
		final SqlResult second = engine.execute(
				(SqlSession) null,
				UPSERT,
				new Object[]{1, 5, 6, 7, false, wall, 5, 6, 7, false, wall});
		assertTrue(second.rowsAffected() >= 1L);
		final SqlResult sel = engine.execute(
				"SELECT season, updated_at FROM pledge_game_season WHERE id = 1");
		assertEquals(1, sel.rows().size());
		assertEquals(5, ((Number) sel.rows().getFirst()[0]).intValue());
		assertEquals("2026-10-10T11:28:08", String.valueOf(sel.rows().getFirst()[1]));
	}
}
