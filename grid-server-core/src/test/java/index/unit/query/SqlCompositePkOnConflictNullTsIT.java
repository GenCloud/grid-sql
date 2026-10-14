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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Regress: composite PK ON CONFLICT DO UPDATE with null TIMESTAMP (npc_db_info shape).
 * <p>
 * {@code Map.copyOf} on SET maps NPEs when SQL NULL is assigned ({@code death_time = NULL}).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
public class SqlCompositePkOnConflictNullTsIT {
	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(), null, 4);
		engine.execute(
				"CREATE TABLE npc_db_info ("
						+ "maker_key VARCHAR NOT NULL, "
						+ "npc_key VARCHAR NOT NULL, "
						+ "death_time TIMESTAMP, "
						+ "current_hp INT, "
						+ "alive BOOLEAN, "
						+ "PRIMARY KEY (maker_key, npc_key))");
	}

	@Test
	void insertThenOnConflictUpdateWithNullTimestamp() {
		engine.execute(
				"INSERT INTO npc_db_info (maker_key, npc_key, death_time, current_hp, alive) "
						+ "VALUES ('m1', 'n1', NULL, 10, TRUE)");
		assertDoesNotThrow(() -> engine.execute(
				"INSERT INTO npc_db_info (maker_key, npc_key, death_time, current_hp, alive) "
						+ "VALUES ('m1', 'n1', NULL, 20, FALSE) "
						+ "ON CONFLICT (maker_key, npc_key) DO UPDATE SET "
						+ "death_time = NULL, current_hp = 20, alive = FALSE"));
		final SqlResult rows = engine.execute(
				"SELECT current_hp, death_time, alive FROM npc_db_info "
						+ "WHERE maker_key = 'm1' AND npc_key = 'n1'");
		assertEquals(1, rows.rows().size());
		assertEquals(20, ((Number) rows.rows().getFirst()[0]).intValue());
		assertNull(rows.rows().getFirst()[1]);
	}

	@Test
	void onConflictInsertWhenAbsentWithNullTimestamp() {
		assertDoesNotThrow(() -> engine.execute(
				"INSERT INTO npc_db_info (maker_key, npc_key, death_time, current_hp, alive) "
						+ "VALUES ('m2', 'n2', NULL, 5, TRUE) "
						+ "ON CONFLICT (maker_key, npc_key) DO UPDATE SET current_hp = 5"));
		assertEquals(1, engine.execute("SELECT maker_key FROM npc_db_info").rows().size());
	}
}
