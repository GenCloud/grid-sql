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

/**
 * UPDATE SET column-copy and row-aware CASE with {@code col + literal}.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.1
 */
public class SqlUpdateColumnCopyAndCaseIT {

	private static final int MAX_VITALITY = 140_000;

	@TempDir
	Path tempDir;

	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(tempDir.resolve("cat")), null, 4);
		engine.execute(
				"CREATE TABLE player_data (id INT PRIMARY KEY, vitality_points INT, "
						+ "pledge_contribution INT, pledge_contribution_prev INT, "
						+ "pledge_contribution_total INT, pledge_contribution_total_prev INT)");
	}

	@AfterEach
	void tearDown() {
		engine = null;
	}

	private static Object cell(SqlResult r, int col) {
		assertEquals(1, r.rows().size());
		return r.rows().getFirst()[col];
	}

	@Test
	void setColumnCopyQualifiedAndBare() {
		engine.execute(
				"INSERT INTO player_data VALUES (1, 0, 100, 0, 250, 0)");
		engine.execute(
				"UPDATE player_data SET pledge_contribution_prev = player_data.pledge_contribution, "
						+ "pledge_contribution_total_prev = player_data.pledge_contribution_total, "
						+ "pledge_contribution_total = 0, pledge_contribution = 0 WHERE id = 1");
		final SqlResult row = engine.execute(
				"SELECT pledge_contribution_prev, pledge_contribution_total_prev, "
						+ "pledge_contribution, pledge_contribution_total FROM player_data WHERE id = 1");
		assertEquals(100, ((Number) cell(row, 0)).intValue());
		assertEquals(250, ((Number) cell(row, 1)).intValue());
		assertEquals(0, ((Number) cell(row, 2)).intValue());
		assertEquals(0, ((Number) cell(row, 3)).intValue());
	}

	@Test
	void vitalityCaseZeroSetsLiteral() {
		engine.execute("INSERT INTO player_data VALUES (2, 0, 0, 0, 0, 0)");
		engine.execute(
				"UPDATE player_data SET vitality_points = CASE "
						+ "WHEN player_data.vitality_points = 0 THEN 5000 "
						+ "WHEN (player_data.vitality_points + 1000) >= " + MAX_VITALITY + " THEN " + MAX_VITALITY + " "
						+ "ELSE (player_data.vitality_points + 1000) END WHERE id = 2");
		final SqlResult row = engine.execute("SELECT vitality_points FROM player_data WHERE id = 2");
		assertEquals(5000, ((Number) cell(row, 0)).intValue());
	}

	@Test
	void vitalityCaseAddWithoutCap() {
		engine.execute("INSERT INTO player_data VALUES (3, 1000, 0, 0, 0, 0)");
		engine.execute(
				"UPDATE player_data SET vitality_points = CASE "
						+ "WHEN player_data.vitality_points = 0 THEN 5000 "
						+ "WHEN (player_data.vitality_points + 1000) >= " + MAX_VITALITY + " THEN " + MAX_VITALITY + " "
						+ "ELSE (player_data.vitality_points + 1000) END WHERE id = 3");
		final SqlResult row = engine.execute("SELECT vitality_points FROM player_data WHERE id = 3");
		assertEquals(2000, ((Number) cell(row, 0)).intValue());
	}

	@Test
	void vitalityCaseAddCapsAtMax() {
		engine.execute("INSERT INTO player_data VALUES (4, " + (MAX_VITALITY - 500) + ", 0, 0, 0, 0)");
		engine.execute(
				"UPDATE player_data SET vitality_points = CASE "
						+ "WHEN player_data.vitality_points = 0 THEN 5000 "
						+ "WHEN (player_data.vitality_points + 1000) >= " + MAX_VITALITY + " THEN " + MAX_VITALITY + " "
						+ "ELSE (player_data.vitality_points + 1000) END WHERE id = 4");
		final SqlResult row = engine.execute("SELECT vitality_points FROM player_data WHERE id = 4");
		assertEquals(MAX_VITALITY, ((Number) cell(row, 0)).intValue());
	}
}
