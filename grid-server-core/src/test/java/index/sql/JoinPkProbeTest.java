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
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * INNER JOIN uses PK probe when ON-column is PRIMARY KEY.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class JoinPkProbeTest {
	@Test
	void joinOnPkFasterPathProducesRows() {
		final SqlEngine engine = new SqlEngine(new TableCatalog(), null, 4);
		engine.execute("CREATE TABLE a (id INT PRIMARY KEY, k INT)");
		engine.execute("CREATE TABLE b (id INT PRIMARY KEY, v INT)");
		for (int i = 0; i < 200; i++) {
			engine.execute("INSERT INTO a VALUES (" + i + ", " + i + ")");
			engine.execute("INSERT INTO b VALUES (" + i + ", " + (i * 10) + ")");
		}
		final long t0 = System.nanoTime();
		final SqlResult r = engine.execute("SELECT * FROM a JOIN b ON id = id");
		final long ms = (System.nanoTime() - t0) / 1_000_000L;
		assertEquals(200, r.rows().size());
		assertTrue(ms < 5_000L, "join should finish quickly, took " + ms + "ms");
	}
}