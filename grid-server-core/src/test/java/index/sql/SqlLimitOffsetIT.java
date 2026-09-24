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
import org.genfork.grid.sql.SqlStatementParser;
import org.genfork.grid.sql.ast.SelectAst.SelectSql;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * K4: {@code LIMIT n OFFSET m} (MySQL {@code LIMIT offset,count} remains supported).
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class SqlLimitOffsetIT {
	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(), null, 4);
		engine.execute("CREATE TABLE t (id INT PRIMARY KEY)");
		engine.execute("INSERT INTO t VALUES (1)");
		engine.execute("INSERT INTO t VALUES (2)");
		engine.execute("INSERT INTO t VALUES (3)");
		engine.execute("INSERT INTO t VALUES (4)");
		engine.execute("INSERT INTO t VALUES (5)");
	}

	@Test
	void limitOffsetParsed() {
		final SelectSql s = (SelectSql) SqlStatementParser.parse(
				"SELECT id FROM t ORDER BY id LIMIT 2 OFFSET 1");
		assertEquals(1, s.offset());
		assertEquals(2, s.limitOrNull());
	}

	@Test
	void limitOffsetSkipsRows() {
		final SqlResult r = engine.execute("SELECT id FROM t ORDER BY id LIMIT 2 OFFSET 2");
		assertEquals(2, r.rows().size());
		assertEquals(3, r.rows().get(0)[0]);
		assertEquals(4, r.rows().get(1)[0]);
	}

	@Test
	void legacyLimitOffsetCommaStillWorks() {
		final SqlResult r = engine.execute("SELECT id FROM t ORDER BY id LIMIT 2, 2");
		assertEquals(2, r.rows().size());
		assertEquals(3, r.rows().get(0)[0]);
		assertEquals(4, r.rows().get(1)[0]);
	}

	@Test
	void rejectsLimitCommaCombinedWithOffsetKeyword() {
		assertThrows(IllegalArgumentException.class, () ->
				engine.execute("SELECT id FROM t LIMIT 1, 2 OFFSET 3"));
	}
}
