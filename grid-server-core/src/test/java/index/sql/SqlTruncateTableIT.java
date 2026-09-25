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
import org.genfork.grid.sql.SqlStatementTag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TRUNCATE TABLE clears all rows and reports rows affected.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class SqlTruncateTableIT {
	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(), null, 4);
	}

	@Test
	void truncateClearsAllRowsAndReportsAffected() {
		engine.execute("CREATE TABLE trunc (id INT PRIMARY KEY)");
		engine.execute("INSERT INTO trunc VALUES (1)");
		engine.execute("INSERT INTO trunc VALUES (2)");
		final SqlResult r = engine.execute("TRUNCATE TABLE trunc");
		assertEquals(SqlStatementTag.TRUNCATE, r.statementTag());
		assertEquals(2L, r.rowsAffected());
		assertTrue(engine.execute("SELECT id FROM trunc").rows().isEmpty());
	}
}