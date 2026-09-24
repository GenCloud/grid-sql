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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wave F: multi-column FOREIGN KEY when parent has a matching UNIQUE index.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class CompositeFkIT {
	private SqlEngine engine;

	@BeforeEach
	void setUp() throws Exception {
		engine = new SqlEngine(new TableCatalog(Files.createTempDirectory("composite-fk-it")), null, 4);
	}

	@Test
	void createTableCompositeFkSucceedsWithUniqueParentIndex() {
		engine.execute("CREATE TABLE parent (id INT PRIMARY KEY, a INT, b INT)");
		engine.execute("CREATE UNIQUE INDEX uq_parent_ab ON parent (a, b)");
		assertDoesNotThrow(() -> engine.execute(
				"CREATE TABLE child (id INT PRIMARY KEY, a INT, b INT, "
						+ "FOREIGN KEY (a, b) REFERENCES parent (a, b))"));
	}

	@Test
	void createTableCompositeFkRejectedWithoutUniqueParentIndex() {
		engine.execute("CREATE TABLE parent (id INT PRIMARY KEY, a INT, b INT)");
		final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> engine.execute(
						"CREATE TABLE child (id INT PRIMARY KEY, a INT, b INT, "
								+ "FOREIGN KEY (a, b) REFERENCES parent (a, b))"));
		assertTrue(ex.getMessage().contains("UNIQUE") || ex.getMessage().contains("composite"),
				ex.getMessage());
	}
}