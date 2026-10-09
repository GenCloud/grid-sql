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

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Double-quoted identifiers: JOOQ reserved-word escape ({@code "key"}), quotes stripped.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.1
 */
class SqlQuotedIdentIT {
	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(), null, 4);
		engine.execute("CREATE TABLE punish (id INT PRIMARY KEY, key VARCHAR)");
		engine.execute("INSERT INTO punish (id, key) VALUES (1, 'ban')");
	}

	@Test
	void selectQuotedKeyColumn() {
		final SqlResult r = engine.execute("SELECT \"key\" FROM punish WHERE id = 1");
		assertEquals(1, r.rows().size());
		assertEquals("ban", r.rows().getFirst()[0]);
	}

	@Test
	void selectQualifiedQuotedKey() {
		final SqlResult r = engine.execute("SELECT punish.\"key\" FROM punish WHERE id = 1");
		assertEquals(1, r.rows().size());
		assertEquals("ban", r.rows().getFirst()[0]);
	}

	@Test
	void whereQuotedKey() {
		final SqlResult r = engine.execute("SELECT id FROM punish WHERE \"key\" = 'ban'");
		assertEquals(1, r.rows().size());
		assertEquals(1, r.rows().getFirst()[0]);
	}
}
