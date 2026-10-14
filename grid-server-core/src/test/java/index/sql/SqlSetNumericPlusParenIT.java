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
 * jOOQ Field.plus SET RMW renders {@code = (col + ?)}; SimplifiedSql must accept parens.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
public class SqlSetNumericPlusParenIT {
	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(), null, 4);
		engine.execute("CREATE TABLE counters (id INT PRIMARY KEY, n INT)");
		engine.execute("INSERT INTO counters VALUES (1, 10)");
	}

	@Test
	void updateSetWithParenNumericPlus() {
		assertDoesNotThrow(() -> engine.execute(
				"UPDATE counters SET n = (counters.n + 5) WHERE counters.id = 1"));
		final SqlResult rows = engine.execute("SELECT n FROM counters WHERE id = 1");
		assertEquals(15, ((Number) rows.rows().getFirst()[0]).intValue());
	}

	@Test
	void updateSetWithoutParensStillWorks() {
		engine.execute("UPDATE counters SET n = counters.n + 1 WHERE counters.id = 1");
		final SqlResult rows = engine.execute("SELECT n FROM counters WHERE id = 1");
		assertEquals(11, ((Number) rows.rows().getFirst()[0]).intValue());
	}
}
