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
 * COALESCE projection and ON CONFLICT DO UPDATE EXCLUDED / COALESCE resolution.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class SqlCoalesceExcludedIT {
	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(), null, 4);
	}

	@Test
	void coalesceSelectFallsBackToNonNullArg() {
		engine.execute("CREATE TABLE kv (id INT PRIMARY KEY, val VARCHAR, n INT)");
		engine.execute("INSERT INTO kv VALUES (1, NULL, 5)");
		final SqlResult coal = engine.execute("SELECT COALESCE(val, 'fallback') FROM kv WHERE id = 1");
		assertEquals("fallback", coal.rows().getFirst()[0]);
	}

	@Test
	void onConflictDoUpdateUsesExcludedAndCoalesce() {
		engine.execute("CREATE TABLE kv (id INT PRIMARY KEY, val VARCHAR, n INT)");
		engine.execute("INSERT INTO kv VALUES (1, NULL, 5)");
		engine.execute(
				"INSERT INTO kv VALUES (1, 'ignored', 9) "
						+ "ON CONFLICT (id) DO UPDATE SET val = EXCLUDED.val, n = COALESCE(EXCLUDED.n, n)");
		final SqlResult after = engine.execute("SELECT val, n FROM kv WHERE id = 1");
		assertEquals("ignored", after.rows().getFirst()[0]);
		assertEquals(9, ((Number) after.rows().getFirst()[1]).intValue());
	}
}