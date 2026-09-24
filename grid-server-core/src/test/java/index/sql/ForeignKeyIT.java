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
 * P2.4 FOREIGN KEY: RESTRICT / CASCADE / SET NULL + cyclic DDL reject.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class ForeignKeyIT {
	private SqlEngine engine;

	@BeforeEach
	void setUp() throws Exception {
		engine = new SqlEngine(new TableCatalog(Files.createTempDirectory("fk-it")), null, 4);
	}

	@Test
	void restrictBlocksParentDelete() {
		engine.execute("CREATE TABLE parent (id INT PRIMARY KEY, name VARCHAR)");
		engine.execute(
				"CREATE TABLE child (id INT PRIMARY KEY, parent_id INT, "
						+ "FOREIGN KEY (parent_id) REFERENCES parent (id) ON DELETE RESTRICT)");
		engine.execute("INSERT INTO parent (id, name) VALUES (1, 'p')");
		engine.execute("INSERT INTO child (id, parent_id) VALUES (10, 1)");
		assertThrows(IllegalStateException.class,
				() -> engine.execute("DELETE FROM parent WHERE id = 1"));
		final SqlResult still = engine.execute("SELECT id FROM parent WHERE id = 1");
		assertEquals(1, still.rows().size());
	}

	@Test
	void missingParentOnInsertRejected() {
		engine.execute("CREATE TABLE parent (id INT PRIMARY KEY)");
		engine.execute(
				"CREATE TABLE child (id INT PRIMARY KEY, parent_id INT, "
						+ "FOREIGN KEY (parent_id) REFERENCES parent (id))");
		assertThrows(IllegalStateException.class,
				() -> engine.execute("INSERT INTO child (id, parent_id) VALUES (1, 99)"));
	}

	@Test
	void cascadeDeleteParentRemovesChildren() {
		engine.execute("CREATE TABLE parent (id INT PRIMARY KEY)");
		engine.execute(
				"CREATE TABLE child (id INT PRIMARY KEY, parent_id INT, "
						+ "FOREIGN KEY (parent_id) REFERENCES parent (id) ON DELETE CASCADE)");
		engine.execute("INSERT INTO parent (id) VALUES (1)");
		engine.execute("INSERT INTO child (id, parent_id) VALUES (10, 1)");
		engine.execute("INSERT INTO child (id, parent_id) VALUES (11, 1)");
		engine.execute("DELETE FROM parent WHERE id = 1");
		assertTrue(engine.execute("SELECT id FROM parent WHERE id = 1").rows().isEmpty());
		assertTrue(engine.execute("SELECT id FROM child WHERE id = 10").rows().isEmpty());
		assertTrue(engine.execute("SELECT id FROM child WHERE id = 11").rows().isEmpty());
	}

	@Test
	void setNullOnDelete() {
		engine.execute("CREATE TABLE parent (id INT PRIMARY KEY)");
		engine.execute(
				"CREATE TABLE child (id INT PRIMARY KEY, parent_id INT, "
						+ "FOREIGN KEY (parent_id) REFERENCES parent (id) ON DELETE SET NULL)");
		engine.execute("INSERT INTO parent (id) VALUES (1)");
		engine.execute("INSERT INTO child (id, parent_id) VALUES (10, 1)");
		engine.execute("DELETE FROM parent WHERE id = 1");
		final SqlResult r = engine.execute("SELECT parent_id FROM child WHERE id = 10");
		assertEquals(1, r.rows().size());
		assertEquals(null, r.rows().getFirst()[0]);
	}

	@Test
	void selfReferentialFkRejectedAsCycle() {
		assertThrows(IllegalArgumentException.class, () -> engine.execute(
				"CREATE TABLE tree (id INT PRIMARY KEY, parent_id INT, "
						+ "FOREIGN KEY (parent_id) REFERENCES tree (id))"));
	}

	@Test
	void dropParentBlockedWhileReferenced() {
		engine.execute("CREATE TABLE parent (id INT PRIMARY KEY)");
		engine.execute(
				"CREATE TABLE child (id INT PRIMARY KEY, parent_id INT, "
						+ "FOREIGN KEY (parent_id) REFERENCES parent (id))");
		assertThrows(IllegalStateException.class, () -> engine.execute("DROP TABLE parent"));
	}

	@Test
	void informationSchemaListsForeignKey() {
		engine.execute("CREATE TABLE parent (id INT PRIMARY KEY)");
		engine.execute(
				"CREATE TABLE child (id INT PRIMARY KEY, parent_id INT, "
						+ "CONSTRAINT fk_child_parent FOREIGN KEY (parent_id) REFERENCES parent (id) "
						+ "ON DELETE CASCADE)");
		final SqlResult r = engine.execute(
				"SELECT constraint_type FROM information_schema.table_constraints "
						+ "WHERE table_name = 'child'");
		boolean found = false;
		for (Object[] row : r.rows()) {
			if ("FOREIGN KEY".equals(String.valueOf(row[0]))) {
				found = true;
			}
		}
		assertTrue(found);
	}
}
