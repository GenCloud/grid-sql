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

import java.time.Instant;
import java.time.LocalDate;

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Column DEFAULT literals and clock builtins materialized on INSERT omit.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class SqlColumnDefaultClockIT {
	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(), null, 4);
	}

	@Test
	void insertOmitsColumnsUsesLiteralAndClockDefaults() {
		engine.execute(
				"CREATE TABLE defs ("
						+ "id INT PRIMARY KEY, "
						+ "label VARCHAR DEFAULT 'x', "
						+ "created TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP, "
						+ "day DATE DEFAULT CURRENT_DATE)");
		engine.execute("INSERT INTO defs (id) VALUES (1)");
		final SqlResult row = engine.execute("SELECT label, created, day FROM defs WHERE id = 1");
		assertEquals("x", row.rows().getFirst()[0]);
		assertInstanceOf(Instant.class, row.rows().getFirst()[1]);
		assertInstanceOf(LocalDate.class, row.rows().getFirst()[2]);
	}
}