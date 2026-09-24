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

/**
 * Wave F: {@code MERGE INTO … USING table} (scan source rows).
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class MergeUsingTableIT {
	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(), null, 4);
		engine.execute("CREATE TABLE tgt (id INT PRIMARY KEY, val VARCHAR)");
		engine.execute("CREATE TABLE src (id INT PRIMARY KEY, val VARCHAR)");
	}

	@Test
	void mergeUsingTableMatchedAndNotMatched() {
		engine.execute("INSERT INTO tgt VALUES (1, 'old')");
		engine.execute("INSERT INTO src VALUES (1, 's1'), (2, 's2')");

		final SqlResult r = engine.execute(
				"MERGE INTO tgt USING src ON id = id "
						+ "WHEN MATCHED THEN UPDATE SET val = 'merged' "
						+ "WHEN NOT MATCHED THEN INSERT (id, val) VALUES (0, 'x')");
		assertEquals(SqlStatementTag.MERGE, r.statementTag());
		assertEquals(2L, r.rowsAffected());

		assertEquals("merged",
				engine.execute("SELECT val FROM tgt WHERE id = 1").rows().getFirst()[0]);
		assertEquals("s2",
				engine.execute("SELECT val FROM tgt WHERE id = 2").rows().getFirst()[0]);
	}
}