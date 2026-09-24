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
import org.genfork.grid.sql.ast.DmlAst.ConflictAction;
import org.genfork.grid.sql.ast.DmlAst.InsertSql;
import org.genfork.grid.sql.SqlStatementParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * K1: {@code UPSERT INTO} maps to ON CONFLICT / OpLog UPSERT overwrite path.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class SqlUpsertKeywordIT {
	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(), null, 4);
		engine.execute("CREATE TABLE kv (id INT PRIMARY KEY, val VARCHAR)");
		engine.execute("INSERT INTO kv VALUES (1, 'a')");
	}

	@Test
	void upsertKeywordParsesAsDoUpsertValues() {
		final InsertSql stmt = (InsertSql) SqlStatementParser.parse(
				"UPSERT INTO kv (id, val) VALUES (1, 'b')");
		assertNotNull(stmt.onConflictOrNull());
		assertEquals(ConflictAction.DO_UPSERT_VALUES, stmt.onConflictOrNull().action());
	}

	@Test
	void upsertOverwritesExistingPk() {
		final SqlResult r = engine.execute("UPSERT INTO kv (id, val) VALUES (1, 'b')");
		assertEquals(SqlStatementTag.INSERT, r.statementTag());
		assertEquals(1L, r.rowsAffected());
		assertEquals("b", engine.execute("SELECT val FROM kv WHERE id = 1").rows().getFirst()[0]);
	}

	@Test
	void upsertInsertsWhenAbsent() {
		engine.execute("UPSERT INTO kv (id, val) VALUES (2, 'new')");
		assertEquals(2, engine.execute("SELECT id FROM kv").rows().size());
		assertEquals("new", engine.execute("SELECT val FROM kv WHERE id = 2").rows().getFirst()[0]);
	}

	@Test
	void upsertRejectsCombinedOnConflict() {
		assertThrows(IllegalArgumentException.class, () ->
				engine.execute("UPSERT INTO kv VALUES (1, 'x') ON CONFLICT DO NOTHING"));
	}
}
