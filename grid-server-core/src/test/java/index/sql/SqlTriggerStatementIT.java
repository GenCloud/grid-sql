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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Statement triggers fire once per DML statement and cannot bind OLD/NEW rows.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class SqlTriggerStatementIT {
	private static final int SHARDS = 4;

	private SqlEngine engine;

	@BeforeEach
	void setUp() throws Exception {
		engine = new SqlEngine(new TableCatalog(Files.createTempDirectory("trg-statement-it")), null, SHARDS);
	}

	@Test
	void afterStatementFiresOnceForMultiRowInsert() {
		engine.execute("CREATE TABLE statement_src (id INT PRIMARY KEY)");
		engine.execute("CREATE TABLE statement_audit (id INT PRIMARY KEY)");
		engine.execute(
				"CREATE TRIGGER trg_statement AFTER INSERT ON statement_src FOR EACH STATEMENT "
						+ "AS 'INSERT INTO statement_audit (id) VALUES (1)'");

		engine.execute("INSERT INTO statement_src (id) VALUES (10), (11)");

		assertEquals(2, engine.execute("SELECT id FROM statement_src").rows().size());
		assertEquals(1, engine.execute("SELECT id FROM statement_audit").rows().size());
	}

	@Test
	void statementTriggerRejectsRowReference() {
		engine.execute("CREATE TABLE statement_src (id INT PRIMARY KEY)");

		assertThrows(IllegalArgumentException.class, () -> engine.execute(
				"CREATE TRIGGER trg_bad AFTER INSERT ON statement_src FOR EACH STATEMENT "
						+ "AS 'DELETE FROM statement_src WHERE id = NEW.id'"));
	}
}
