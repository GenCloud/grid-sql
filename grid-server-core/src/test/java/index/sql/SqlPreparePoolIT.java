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
import org.genfork.grid.sql.SqlSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PREPARE pool: per-session LRU cap ({@code grid.sql.preparePoolSize}).
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class SqlPreparePoolIT {
	private SqlEngine engine;

	@BeforeEach
	void setUp() throws Exception {
		engine = new SqlEngine(
				new TableCatalog(Files.createTempDirectory("prepare-pool")),
				null,
				4,
				4
		);
	}

	@AfterEach
	void tearDown() {
	}

	@Test
	void exceedCapEvictsOldestAndNewPrepareExecuteWorks() {
		engine.execute("CREATE TABLE t (id INT PRIMARY KEY, v INT)");
		final SqlSession s = engine.newSession();
		assertEquals(4, s.preparePoolMax());

		engine.execute(s, "PREPARE p0 AS INSERT INTO t (id, v) VALUES (0, 0)");
		engine.execute(s, "PREPARE p1 AS INSERT INTO t (id, v) VALUES (1, 1)");
		engine.execute(s, "PREPARE p2 AS INSERT INTO t (id, v) VALUES (2, 2)");
		engine.execute(s, "PREPARE p3 AS INSERT INTO t (id, v) VALUES (3, 3)");
		assertEquals(4, s.preparedCount());
		assertTrue(s.hasPrepared("p0"));

		engine.execute(s, "PREPARE p4 AS INSERT INTO t (id, v) VALUES (4, 4)");
		assertEquals(4, s.preparedCount());
		assertFalse(s.hasPrepared("p0"), "oldest PREPARE must be LRU-evicted");
		assertTrue(s.hasPrepared("p4"));

		assertThrows(IllegalArgumentException.class, () -> engine.execute(s, "EXECUTE p0"));
		engine.execute(s, "EXECUTE p4");
		assertEquals(4, engine.execute("SELECT v FROM t WHERE id = 4").rows().getFirst()[0]);
	}

	@Test
	void executeTouchesLruSoIdlePrepareIsEvictedFirst() {
		engine.execute("CREATE TABLE t (id INT PRIMARY KEY, v INT)");
		final SqlSession s = engine.newSession();
		engine.execute(s, "PREPARE p0 AS SELECT id FROM t WHERE id = 0");
		engine.execute(s, "PREPARE p1 AS SELECT id FROM t WHERE id = 1");
		engine.execute(s, "PREPARE p2 AS SELECT id FROM t WHERE id = 2");
		engine.execute(s, "PREPARE p3 AS SELECT id FROM t WHERE id = 3");
		// Touch p0 so p1 becomes the oldest by access order.
		engine.execute(s, "EXECUTE p0");
		engine.execute(s, "PREPARE p4 AS SELECT id FROM t WHERE id = 4");
		assertFalse(s.hasPrepared("p1"));
		assertTrue(s.hasPrepared("p0"));
		assertTrue(s.hasPrepared("p4"));
		engine.execute(s, "EXECUTE p4");
	}
}
