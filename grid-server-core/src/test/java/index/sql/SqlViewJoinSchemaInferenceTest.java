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

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CREATE VIEW schema inference when JOIN sides are plain VIEW (Flyway V3 rankers shape).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.1
 */
class SqlViewJoinSchemaInferenceTest {
	private static final String UNKNOWN_TABLE_PREFIX = "Unknown table:";

	private SqlEngine engine;

	@BeforeEach
	void setUp() throws Exception {
		engine = new SqlEngine(new TableCatalog(Files.createTempDirectory("view-join-infer")), null, 4);
		engine.execute(
				"CREATE TABLE player_data ("
						+ "id INT PRIMARY KEY, server_id INT, character_name VARCHAR, experience BIGINT, "
						+ "level INT, base_class INT, race INT, pledge_id INT, online_time BIGINT)");
		engine.execute(
				"CREATE TABLE rank_snapshot ("
						+ "owner_id INT PRIMARY KEY, rank INT, rank_race INT, rank_class INT)");
		engine.execute(
				"CREATE TABLE pledge_data (pledge_id INT PRIMARY KEY, pledge_name VARCHAR)");
	}

	@Test
	void createViewJoiningPlainViewsInfersColumns() {
		engine.execute(
				"CREATE VIEW rankers_race AS WITH ranked_race AS ("
						+ "SELECT p.id AS owner_id, p.server_id, p.character_name AS name, p.experience, "
						+ "p.level, p.base_class AS class, p.race, "
						+ "COALESCE(pledge.pledge_name, '') AS pledge_name, "
						+ "RANK() OVER (PARTITION BY p.race ORDER BY p.experience DESC, p.online_time DESC) AS rank, "
						+ "COALESCE(rs.rank, 0) AS rank_snapshot, "
						+ "COALESCE(rs.rank_race, 0) AS rank_race_snapshot, "
						+ "COALESCE(rs.rank_class, 0) AS rank_class_snapshot "
						+ "FROM player_data p "
						+ "LEFT JOIN rank_snapshot rs ON p.id = rs.owner_id "
						+ "LEFT JOIN pledge_data pledge ON pledge.pledge_id = p.pledge_id "
						+ "WHERE p.level >= 85"
						+ ") SELECT * FROM ranked_race WHERE rank <= 100");
		engine.execute(
				"CREATE VIEW rankers_class AS WITH ranked_class AS ("
						+ "SELECT p.id AS owner_id, p.server_id, p.character_name AS name, p.experience, "
						+ "p.level, p.base_class AS class, p.race, "
						+ "COALESCE(pledge.pledge_name, '') AS pledge_name, "
						+ "RANK() OVER (PARTITION BY p.base_class ORDER BY p.experience DESC, p.online_time DESC) AS rank, "
						+ "COALESCE(rs.rank, 0) AS rank_snapshot, "
						+ "COALESCE(rs.rank_race, 0) AS rank_race_snapshot, "
						+ "COALESCE(rs.rank_class, 0) AS rank_class_snapshot "
						+ "FROM player_data p "
						+ "LEFT JOIN rank_snapshot rs ON p.id = rs.owner_id "
						+ "LEFT JOIN pledge_data pledge ON pledge.pledge_id = p.pledge_id "
						+ "WHERE p.level >= 85"
						+ ") SELECT * FROM ranked_class WHERE rank <= 100");
		engine.execute(
				"CREATE VIEW rankers AS "
						+ "SELECT p.id AS owner_id, p.server_id, p.character_name AS name, p.experience, "
						+ "p.level, p.base_class AS class, p.race, p.pledge_id, "
						+ "COALESCE(pledge.pledge_name, '') AS pledge_name, "
						+ "RANK() OVER (w) AS rank, "
						+ "COALESCE(rr.rank, 0) AS rank_race, "
						+ "COALESCE(rs.rank, 0) AS rank_snapshot, "
						+ "COALESCE(rs.rank_race, 0) AS rank_race_snapshot, "
						+ "COALESCE(rc.rank, 0) AS rank_class, "
						+ "COALESCE(rc.rank_class_snapshot, 0) AS rank_class_snapshot "
						+ "FROM player_data p "
						+ "LEFT JOIN rank_snapshot rs ON p.id = rs.owner_id "
						+ "LEFT JOIN pledge_data pledge ON pledge.pledge_id = p.pledge_id "
						+ "JOIN rankers_race rr ON p.id = rr.owner_id "
						+ "JOIN rankers_class rc ON p.id = rc.owner_id "
						+ "WHERE p.level >= 85 "
						+ "WINDOW w AS (ORDER BY p.experience DESC, online_time DESC)");
		final SqlResult r = engine.execute(
				"SELECT column_name FROM information_schema.columns WHERE table_name = 'rankers'");
		final Set<String> cols = new HashSet<>();
		for (Object[] row : r.rows()) {
			cols.add(String.valueOf(row[0]).toLowerCase(Locale.ROOT));
		}
		assertTrue(cols.contains("owner_id"));
		assertTrue(cols.contains("rank_race"));
		assertTrue(cols.contains("rank_class"));
	}

	@Test
	void createViewJoinUnknownNameFailsClosed() {
		engine.execute("CREATE TABLE t (id INT PRIMARY KEY)");
		final IllegalArgumentException ex = assertThrows(
				IllegalArgumentException.class,
				() -> engine.execute(
						"CREATE VIEW bad AS SELECT t.id FROM t JOIN missing_v m ON t.id = m.id"));
		assertTrue(ex.getMessage().contains(UNKNOWN_TABLE_PREFIX));
	}
}
