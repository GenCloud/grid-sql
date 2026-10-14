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
import org.genfork.grid.sql.SqlSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Open-TX plain {@code INSERT} PK probe must honor dirty {@code DELETE} / {@code TRUNCATE}.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
class SqlTxDeleteInsertPkVisibilityIT {
	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(), null, 4);
		engine.execute("CREATE TABLE t (id INT PRIMARY KEY, v VARCHAR)");
		engine.execute("INSERT INTO t VALUES (1, 'committed')");
	}

	@Test
	void deleteThenPlainInsertSamePkInOpenTx() {
		final SqlSession session = engine.newSession();
		engine.execute(session, "BEGIN");
		engine.execute(session, "DELETE FROM t WHERE id = 1");
		engine.execute(session, "INSERT INTO t VALUES (1, 'replaced')");
		assertEquals(1, engine.execute(session, "SELECT id FROM t").rows().size());
		assertEquals("replaced", engine.execute(session, "SELECT v FROM t WHERE id = 1").rows().getFirst()[0]);
		engine.execute(session, "COMMIT");
		assertEquals("replaced", engine.execute("SELECT v FROM t WHERE id = 1").rows().getFirst()[0]);
	}

	@Test
	void truncateThenPlainInsertSamePkInOpenTx() {
		engine.execute("INSERT INTO t VALUES (2, 'two')");
		final SqlSession session = engine.newSession();
		engine.execute(session, "BEGIN");
		engine.execute(session, "TRUNCATE TABLE t");
		engine.execute(session, "INSERT INTO t VALUES (1, 'after-trunc')");
		engine.execute(session, "INSERT INTO t VALUES (2, 'after-trunc-2')");
		assertEquals(2, engine.execute(session, "SELECT id FROM t").rows().size());
		engine.execute(session, "COMMIT");
		assertEquals(2, engine.execute("SELECT id FROM t").rows().size());
	}

	@Test
	void plainInsertStillRejectsDuplicatePkWithoutPriorDelete() {
		final SqlSession session = engine.newSession();
		engine.execute(session, "BEGIN");
		assertThrows(Exception.class, () -> engine.execute(session, "INSERT INTO t VALUES (1, 'dup')"));
		engine.execute(session, "ROLLBACK");
	}

	@Test
	void autocommitPlainInsertStillRejectsDuplicatePk() {
		assertThrows(Exception.class, () -> engine.execute("INSERT INTO t VALUES (1, 'dup')"));
		final SqlResult r = engine.execute("SELECT v FROM t WHERE id = 1");
		assertEquals("committed", r.rows().getFirst()[0]);
	}
}
