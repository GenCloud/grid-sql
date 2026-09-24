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

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQL SAVEPOINT keeps earlier dirty rows and discards later mutations.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class SqlSavepointIT {
	private static final int SHARDS = 4;

	private SqlEngine engine;

	@BeforeEach
	void setUp() throws Exception {
		engine = new SqlEngine(new TableCatalog(Files.createTempDirectory("savepoint-it")), null, SHARDS);
		engine.execute("CREATE TABLE savepoint_items (id INT PRIMARY KEY, payload VARCHAR)");
	}

	@Test
	void rollbackToSavepointKeepsEarlierDirtyMutation() {
		final SqlSession session = engine.newSession();
		engine.execute(session, "BEGIN");
		engine.execute(session, "INSERT INTO savepoint_items VALUES (1, 'kept')");
		engine.execute(session, "SAVEPOINT after_first");
		engine.execute(session, "INSERT INTO savepoint_items VALUES (2, 'discarded')");
		engine.execute(session, "ROLLBACK TO SAVEPOINT after_first");
		engine.execute(session, "RELEASE SAVEPOINT after_first");
		engine.execute(session, "COMMIT");

		assertEquals(1, engine.execute("SELECT payload FROM savepoint_items WHERE id = 1").rows().size());
		assertTrue(engine.execute("SELECT payload FROM savepoint_items WHERE id = 2").rows().isEmpty());
	}
}
