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
import org.genfork.grid.store.TableStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AlwaysTrue + ORDER BY PK ASC uses ordered leaf (orderSatisfied); LIMIT pages once.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.1
 */
public class SqlOrderByPkLeafPlanIT {
	private static final String TABLE = "pk_leaf_order";
	private static final int REPEAT = 30;

	@TempDir
	Path tempDir;

	private SqlEngine engine;
	private TableStore store;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(tempDir.resolve("cat")), null, 4);
		engine.execute(
				"CREATE TABLE " + TABLE
						+ " (server_id INT, biset_type VARCHAR, payload INT, PRIMARY KEY (server_id, biset_type))");
		engine.execute("INSERT INTO " + TABLE + " VALUES (125, 'ITEMS', 1)");
		engine.execute("INSERT INTO " + TABLE + " VALUES (3, 'OTHER', 2)");
		engine.execute("INSERT INTO " + TABLE + " VALUES (3, 'ITEMS', 3)");
		engine.execute("INSERT INTO " + TABLE + " VALUES (50, 'X', 4)");
		store = engine.catalog().getStore(TABLE);
	}

	@AfterEach
	void tearDown() {
		engine = null;
		store = null;
	}

	@Test
	void multiColumnPkOrderByIsOrderSatisfied() {
		final String sql = "SELECT server_id, biset_type FROM " + TABLE
				+ " ORDER BY server_id, biset_type";
		assertTrue(store.isUseSameOrderAscendingIndex(sql));
		for (int i = 0; i < REPEAT; i++) {
			final SqlResult rs = engine.execute(sql);
			assertEquals(3, ((Number) rs.rows().get(0)[0]).intValue(), "run " + i);
			assertEquals("ITEMS", rs.rows().get(0)[1], "run " + i);
		}
	}

	@Test
	void orderByLimitDoesNotDoubleSkip() {
		final String sql = "SELECT server_id FROM " + TABLE
				+ " ORDER BY server_id, biset_type LIMIT 1, 1";
		assertTrue(store.isUseSameOrderAscendingIndex(sql));
		for (int i = 0; i < REPEAT; i++) {
			final SqlResult rs = engine.execute(sql);
			assertEquals(1, rs.rows().size(), "run " + i);
			assertEquals(3, ((Number) rs.rows().get(0)[0]).intValue(), "run " + i + " second PK row");
		}
	}

	@Test
	void nonPkOrderByStillSorted() {
		final String sql = "SELECT server_id, payload FROM " + TABLE + " ORDER BY payload DESC";
		for (int i = 0; i < REPEAT; i++) {
			final SqlResult rs = engine.execute(sql);
			assertEquals(4, ((Number) rs.rows().get(0)[1]).intValue(), "run " + i);
			assertEquals(1, ((Number) rs.rows().get(3)[1]).intValue(), "run " + i);
		}
	}
}
