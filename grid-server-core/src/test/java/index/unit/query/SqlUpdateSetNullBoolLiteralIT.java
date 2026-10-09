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
package index.unit.query;

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UPDATE / ON CONFLICT SET with NULL / TRUE / FALSE literals (not ColumnRef via keywordAsIdent).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.1
 */
public class SqlUpdateSetNullBoolLiteralIT {

	@TempDir
	Path tempDir;

	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(tempDir.resolve("cat")), null, 4);
		engine.execute(
				"CREATE TABLE global_variables (variable VARCHAR PRIMARY KEY, value VARCHAR, "
						+ "expiration_date TIMESTAMP)");
		engine.execute(
				"CREATE TABLE pledge_game_season (id INT PRIMARY KEY, season INT, step INT, "
						+ "prev_winner_got_item BOOLEAN, updated_at TIMESTAMP)");
	}

	@AfterEach
	void tearDown() {
		engine = null;
	}

	@Test
	void updateSetNullLiteralClearsTimestamp() {
		engine.execute(
				"INSERT INTO global_variables VALUES ('GAME_TIME', 'x', "
						+ "TIMESTAMP '2026-01-01 00:00:00')");
		engine.execute(
				"UPDATE global_variables SET value = 'y', expiration_date = NULL "
						+ "WHERE variable = 'GAME_TIME'");
		final SqlResult row = engine.execute(
				"SELECT value, expiration_date FROM global_variables WHERE variable = 'GAME_TIME'");
		assertEquals(1, row.rows().size());
		assertEquals("y", row.rows().getFirst()[0]);
		assertNull(row.rows().getFirst()[1]);
	}

	@Test
	void onConflictDoUpdateSetFalseLiteral() {
		engine.execute(
				"INSERT INTO pledge_game_season VALUES (1, 1, 0, TRUE, "
						+ "TIMESTAMP '2026-01-01 00:00:00')");
		engine.execute(
				"INSERT INTO pledge_game_season VALUES (1, 2, 1, FALSE, "
						+ "TIMESTAMP '2026-02-01 00:00:00') "
						+ "ON CONFLICT (id) DO UPDATE SET season = 2, step = 1, "
						+ "prev_winner_got_item = FALSE, "
						+ "updated_at = TIMESTAMP '2026-02-01 00:00:00'");
		final SqlResult row = engine.execute(
				"SELECT season, step, prev_winner_got_item FROM pledge_game_season WHERE id = 1");
		assertEquals(1, row.rows().size());
		assertEquals(2, ((Number) row.rows().getFirst()[0]).intValue());
		assertEquals(1, ((Number) row.rows().getFirst()[1]).intValue());
		assertFalse((Boolean) row.rows().getFirst()[2]);
	}

	@Test
	void updateSetTrueLiteral() {
		engine.execute(
				"INSERT INTO pledge_game_season VALUES (2, 0, 0, FALSE, "
						+ "TIMESTAMP '2026-01-01 00:00:00')");
		engine.execute(
				"UPDATE pledge_game_season SET prev_winner_got_item = TRUE WHERE id = 2");
		final SqlResult row = engine.execute(
				"SELECT prev_winner_got_item FROM pledge_game_season WHERE id = 2");
		assertTrue((Boolean) row.rows().getFirst()[0]);
	}
}
