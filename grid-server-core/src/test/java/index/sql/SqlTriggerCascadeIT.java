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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G-TRG v1: CREATE/DROP TRIGGER + BEFORE DELETE cascade via nested DML body.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class SqlTriggerCascadeIT {
	private SqlEngine engine;

	@BeforeEach
	void setUp() throws Exception {
		engine = new SqlEngine(new TableCatalog(Files.createTempDirectory("trg-it")), null, 4);
	}

	@Test
	void beforeDeleteCascadesToChild() {
		engine.execute("CREATE TABLE parent (id INT PRIMARY KEY, name VARCHAR)");
		engine.execute("CREATE TABLE child (id INT PRIMARY KEY, parent_id INT)");
		engine.execute(
				"CREATE TRIGGER trg_parent_del BEFORE DELETE ON parent FOR EACH ROW "
						+ "AS 'DELETE FROM child WHERE parent_id = OLD.id'");
		engine.execute("INSERT INTO parent (id, name) VALUES (1, 'p')");
		engine.execute("INSERT INTO child (id, parent_id) VALUES (10, 1)");
		engine.execute("INSERT INTO child (id, parent_id) VALUES (11, 1)");
		engine.execute("DELETE FROM parent WHERE id = 1");
		assertTrue(engine.execute("SELECT id FROM parent WHERE id = 1").rows().isEmpty());
		assertTrue(engine.execute("SELECT id FROM child WHERE id = 10").rows().isEmpty());
		assertTrue(engine.execute("SELECT id FROM child WHERE id = 11").rows().isEmpty());
	}

	@Test
	void afterInsertFiresNestedInsert() {
		engine.execute("CREATE TABLE src (id INT PRIMARY KEY, v INT)");
		engine.execute("CREATE TABLE audit (id INT PRIMARY KEY, src_id INT)");
		engine.execute(
				"CREATE TRIGGER trg_src_ins AFTER INSERT ON src FOR EACH ROW "
						+ "AS 'INSERT INTO audit (id, src_id) VALUES (NEW.id, NEW.id)'");
		engine.execute("INSERT INTO src (id, v) VALUES (5, 42)");
		final SqlResult r = engine.execute("SELECT src_id FROM audit WHERE id = 5");
		assertEquals(1, r.rows().size());
		assertEquals(5, ((Number) r.rows().getFirst()[0]).intValue());
	}

	@Test
	void dropTriggerStopsFire() {
		engine.execute("CREATE TABLE parent (id INT PRIMARY KEY)");
		engine.execute("CREATE TABLE child (id INT PRIMARY KEY, parent_id INT)");
		engine.execute(
				"CREATE TRIGGER trg_parent_del BEFORE DELETE ON parent FOR EACH ROW "
						+ "AS 'DELETE FROM child WHERE parent_id = OLD.id'");
		engine.execute("DROP TRIGGER trg_parent_del ON parent");
		engine.execute("INSERT INTO parent (id) VALUES (1)");
		engine.execute("INSERT INTO child (id, parent_id) VALUES (10, 1)");
		engine.execute("DELETE FROM parent WHERE id = 1");
		assertEquals(1, engine.execute("SELECT id FROM child WHERE id = 10").rows().size());
	}

	@Test
	void dropTriggerIfExistsQuiet() {
		engine.execute("DROP TRIGGER IF EXISTS missing_trg");
	}

	@Test
	void duplicateTriggerRejected() {
		engine.execute("CREATE TABLE t (id INT PRIMARY KEY)");
		engine.execute(
				"CREATE TRIGGER trg1 BEFORE INSERT ON t FOR EACH ROW AS 'SELECT id FROM t WHERE id = NEW.id'");
		assertThrows(IllegalStateException.class, () -> engine.execute(
				"CREATE TRIGGER trg1 AFTER DELETE ON t FOR EACH ROW AS 'SELECT id FROM t WHERE id = OLD.id'"));
	}
}