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
package index.unit.query;

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.SqlStatementTag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P2.1: {@code INSERT … ON CONFLICT} and {@code MERGE INTO …}.
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
public class SqlMergeOnConflictIT {
	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(), null, 4);
		engine.execute("CREATE TABLE kv (id INT PRIMARY KEY, val VARCHAR)");
		engine.execute("INSERT INTO kv VALUES (1, 'a')");
	}

	@Test
	void insertOnConflictDoNothingSkipsDuplicatePk() {
		final SqlResult r = engine.execute(
				"INSERT INTO kv VALUES (1, 'b') ON CONFLICT DO NOTHING");
		assertEquals(SqlStatementTag.INSERT, r.statementTag());
		assertEquals(0L, r.rowsAffected());
		final SqlResult rows = engine.execute("SELECT val FROM kv WHERE id = 1");
		assertEquals("a", rows.rows().getFirst()[0]);
	}

	@Test
	void insertOnConflictDoUpdateMergesLiterals() {
		final SqlResult r = engine.execute(
				"INSERT INTO kv VALUES (1, 'ignored') ON CONFLICT (id) DO UPDATE SET val = 'updated'");
		assertEquals(1L, r.rowsAffected());
		final SqlResult rows = engine.execute("SELECT val FROM kv WHERE id = 1");
		assertEquals("updated", rows.rows().getFirst()[0]);
	}

	@Test
	void insertOnConflictInsertsWhenAbsent() {
		final SqlResult r = engine.execute(
				"INSERT INTO kv VALUES (2, 'new') ON CONFLICT DO UPDATE SET val = 'x'");
		assertEquals(1L, r.rowsAffected());
		assertEquals(2, engine.execute("SELECT id FROM kv").rows().size());
	}

	@Test
	void mergeMatchedUpdates() {
		final SqlResult r = engine.execute(
				"MERGE INTO kv USING (VALUES (1, 'x')) ON id = id "
						+ "WHEN MATCHED THEN UPDATE SET val = 'merged' "
						+ "WHEN NOT MATCHED THEN INSERT VALUES (1, 'x')");
		assertEquals(SqlStatementTag.MERGE, r.statementTag());
		assertEquals(1L, r.rowsAffected());
		assertEquals("merged", engine.execute("SELECT val FROM kv WHERE id = 1").rows().getFirst()[0]);
	}

	@Test
	void mergeNotMatchedInserts() {
		final SqlResult r = engine.execute(
				"MERGE INTO kv USING (VALUES (9, 'n')) ON id = id "
						+ "WHEN MATCHED THEN UPDATE SET val = 'nope' "
						+ "WHEN NOT MATCHED THEN INSERT VALUES (9, 'n')");
		assertEquals(1L, r.rowsAffected());
		assertEquals("n", engine.execute("SELECT val FROM kv WHERE id = 9").rows().getFirst()[0]);
	}
}
