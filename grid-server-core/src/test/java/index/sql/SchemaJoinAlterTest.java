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
import org.genfork.grid.sql.SqlSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CREATE/SET SCHEMA, ALTER TABLE, INNER JOIN v1.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class SchemaJoinAlterTest {
	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(), null, 4);
	}

	@Test
	void schemaAlterAndInnerJoin() {
		final SqlSession session = engine.newSession();
		engine.execute(session, "CREATE SCHEMA app");
		engine.execute(session, "SET SCHEMA app");
		engine.execute(session, "CREATE TABLE left_t (id INT PRIMARY KEY, k INT)");
		engine.execute(session, "CREATE TABLE right_t (id INT PRIMARY KEY, k INT)");
		engine.execute(session, "INSERT INTO left_t VALUES (1, 7)");
		engine.execute(session, "INSERT INTO right_t VALUES (2, 7)");
		engine.execute(session, "ALTER TABLE left_t ADD COLUMN note VARCHAR");
		assertTrue(engine.catalog().exists("app.left_t"));
		assertEquals(3, engine.catalog().requireSchema("app.left_t").columnCount());
		final SqlResult r = engine.execute(session, "SELECT * FROM left_t JOIN right_t ON k = k");
		assertEquals(1, r.rows().size());
	}
}