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

import org.genfork.grid.catalog.FkAction;
import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ALTER TABLE ADD/DROP FOREIGN KEY and ON DELETE / ON UPDATE referential actions.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class SqlAlterForeignKeyActionIT {
	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(), null, 4);
	}

	@Test
	void alterAddForeignKeyOnDeleteCascadeRemovesChildren() {
		engine.execute("CREATE TABLE parent (id INT PRIMARY KEY)");
		engine.execute("CREATE TABLE child (id INT PRIMARY KEY, pid INT)");
		engine.execute(
				"ALTER TABLE child ADD CONSTRAINT child_pid_fk FOREIGN KEY (pid) "
						+ "REFERENCES parent (id) ON DELETE CASCADE");
		assertEquals(FkAction.CASCADE,
				engine.catalog().getSchema("child").foreignKeys().getFirst().onDelete());

		engine.execute("INSERT INTO parent VALUES (1)");
		engine.execute("INSERT INTO child VALUES (10, 1)");
		engine.execute("DELETE FROM parent WHERE id = 1");
		assertEquals(0, engine.execute("SELECT id FROM child").rows().size());
	}

	@Test
	void alterAddForeignKeyOnDeleteSetNullClearsChildColumn() {
		engine.execute("CREATE TABLE parent (id INT PRIMARY KEY)");
		engine.execute("CREATE TABLE child (id INT PRIMARY KEY, pid INT)");
		engine.execute(
				"ALTER TABLE child ADD CONSTRAINT child_pid_fk FOREIGN KEY (pid) "
						+ "REFERENCES parent (id) ON DELETE SET NULL");
		assertEquals(FkAction.SET_NULL,
				engine.catalog().getSchema("child").foreignKeys().getFirst().onDelete());

		engine.execute("INSERT INTO parent VALUES (1)");
		engine.execute("INSERT INTO child VALUES (10, 1)");
		engine.execute("DELETE FROM parent WHERE id = 1");
		assertEquals(1, engine.execute("SELECT id FROM child").rows().size());
		assertNull(engine.execute("SELECT pid FROM child WHERE id = 10").rows().getFirst()[0]);
	}

	@Test
	void alterAddForeignKeyOnDeleteRestrictRejectsParentDelete() {
		engine.execute("CREATE TABLE parent (id INT PRIMARY KEY)");
		engine.execute("CREATE TABLE child (id INT PRIMARY KEY, pid INT)");
		engine.execute(
				"ALTER TABLE child ADD CONSTRAINT child_pid_fk FOREIGN KEY (pid) "
						+ "REFERENCES parent (id) ON DELETE RESTRICT");
		assertEquals(FkAction.RESTRICT,
				engine.catalog().getSchema("child").foreignKeys().getFirst().onDelete());

		engine.execute("INSERT INTO parent VALUES (1)");
		engine.execute("INSERT INTO child VALUES (10, 1)");
		assertThrows(IllegalStateException.class, () ->
				engine.execute("DELETE FROM parent WHERE id = 1"));
		assertEquals(1, engine.execute("SELECT id FROM parent").rows().size());
		assertEquals(1, engine.execute("SELECT id FROM child").rows().size());
	}

	@Test
	void alterDropForeignKeyStopsReferentialAction() {
		engine.execute("CREATE TABLE parent (id INT PRIMARY KEY)");
		engine.execute("CREATE TABLE child (id INT PRIMARY KEY, pid INT)");
		engine.execute(
				"ALTER TABLE child ADD CONSTRAINT child_pid_fk FOREIGN KEY (pid) "
						+ "REFERENCES parent (id) ON DELETE CASCADE");
		engine.execute("INSERT INTO parent VALUES (2)");
		engine.execute("INSERT INTO child VALUES (20, 2)");
		engine.execute("ALTER TABLE child DROP CONSTRAINT child_pid_fk");
		engine.execute("DELETE FROM parent WHERE id = 2");
		assertEquals(1, engine.execute("SELECT id FROM child").rows().size());
	}

	@Test
	void alterAddForeignKeyRejectedInsideOpenTransaction() {
		engine.execute("CREATE TABLE parent (id INT PRIMARY KEY)");
		engine.execute("CREATE TABLE child (id INT PRIMARY KEY, pid INT)");
		final SqlSession session = engine.newSession();
		engine.execute(session, "BEGIN");
		assertThrows(IllegalStateException.class, () ->
				engine.execute(session, "ALTER TABLE child ADD FOREIGN KEY (pid) REFERENCES parent (id)"));
		engine.execute(session, "ROLLBACK");
	}

	@Test
	void alterAddPrimaryKeyReplacesPkColumns() {
		engine.execute("CREATE TABLE rel (a INT PRIMARY KEY, b INT)");
		engine.execute("ALTER TABLE rel ADD PRIMARY KEY (a, b)");
		assertEquals(2, engine.catalog().getSchema("rel").pkColumns().size());
	}
}