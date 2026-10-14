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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * jOOQ DEFAULT renders {@code ON (a = b)}; SimplifiedSql must accept optional parens.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
public class SqlJoinOnParenIT {
	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(), null, 4);
		engine.execute("CREATE TABLE parent (id INT PRIMARY KEY, name VARCHAR)");
		engine.execute("CREATE TABLE child (id INT PRIMARY KEY, parent_id INT, label VARCHAR)");
		engine.execute("INSERT INTO parent VALUES (1, 'p')");
		engine.execute("INSERT INTO child VALUES (10, 1, 'c')");
	}

	@Test
	void joinOnWithOuterParens() {
		assertDoesNotThrow(() -> engine.execute(
				"SELECT child.label FROM child "
						+ "LEFT OUTER JOIN parent AS p ON (child.parent_id = p.id)"));
		final SqlResult rows = engine.execute(
				"SELECT child.label FROM child "
						+ "LEFT OUTER JOIN parent AS p ON (child.parent_id = p.id)");
		assertEquals(1, rows.rows().size());
		assertEquals("c", rows.rows().getFirst()[0]);
	}

	@Test
	void joinOnWithoutParensStillWorks() {
		final SqlResult rows = engine.execute(
				"SELECT child.label FROM child "
						+ "INNER JOIN parent AS p ON child.parent_id = p.id");
		assertEquals(1, rows.rows().size());
		assertEquals("c", rows.rows().getFirst()[0]);
	}
}
