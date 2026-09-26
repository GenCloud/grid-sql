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
 * Exact PK bind must not pick a secondary index that shares the PK leading column.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class CompositePkSecondarySameLeadingIT {
	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(), null, 4);
	}

	@Test
	void selectStarUsesCompositePkWhenSecondarySharesLeadingColumn() {
		engine.execute(
				"CREATE TABLE lead_t (a INT NOT NULL, b VARCHAR NOT NULL, c INT, "
						+ "PRIMARY KEY (a, b))");
		engine.execute("CREATE INDEX idx_a_c ON lead_t (a, c)");
		engine.execute("INSERT INTO lead_t VALUES (1, 'x', 10)");
		engine.execute("INSERT INTO lead_t VALUES (1, 'y', 20)");
		engine.execute("INSERT INTO lead_t VALUES (2, 'z', 30)");

		final SqlResult all = engine.execute("SELECT a, b, c FROM lead_t");
		assertEquals(3, all.rows().size(), "SELECT * must walk composite PK, not secondary (a,c)");

		final SqlResult limited = engine.execute("SELECT a, b FROM lead_t LIMIT 0, 2");
		assertEquals(2, limited.rows().size());

		final SqlResult prefix = engine.execute("SELECT a, b, c FROM lead_t WHERE a = 1");
		assertEquals(2, prefix.rows().size());
	}

	@Test
	void forEachPrimaryKeySeesAllRowsWithSecondaryPresent() {
		engine.execute(
				"CREATE TABLE pk_sec (server_id INT NOT NULL, kind VARCHAR NOT NULL, v INT, "
						+ "PRIMARY KEY (server_id, kind))");
		engine.execute("CREATE INDEX idx_server ON pk_sec (server_id)");
		engine.execute("INSERT INTO pk_sec VALUES (3, 'A', 1)");
		engine.execute("INSERT INTO pk_sec VALUES (3, 'B', 2)");
		engine.execute("INSERT INTO pk_sec VALUES (4, 'C', 3)");

		final SqlResult all = engine.execute("SELECT v FROM pk_sec");
		assertEquals(3, all.rows().size());
	}
}
