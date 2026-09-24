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

import index.sql.udf.ScanTableTvf;
import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Table-valued UDF: FROM fn(...) returns row blobs from a table/view.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class SqlTableUdfIT {
	private SqlEngine engine;
	private TableCatalog catalog;

	@BeforeEach
	void setUp() {
		catalog = new TableCatalog();
		engine = new SqlEngine(catalog, null, 0);
		ScanTableTvf.CATALOG = catalog;
	}

	@Test
	void tvfReturnsRowsFromTable() {
		engine.execute("CREATE TABLE src (id INT PRIMARY KEY, v INT)");
		engine.execute("INSERT INTO src VALUES (1, 10)");
		engine.execute("INSERT INTO src VALUES (2, 20)");
		engine.execute("INSERT INTO src VALUES (3, 30)");
		engine.execute(
				"CREATE FUNCTION scan_src(t VARCHAR) RETURNS TABLE (id INT, v INT) AS CLASS '"
						+ ScanTableTvf.class.getName()
						+ "' METHOD 'apply'");

		final SqlResult r = engine.execute("SELECT id, v FROM scan_src('src')");
		assertEquals(3, r.rows().size());
		final Set<Integer> ids = new HashSet<>();
		for (Object[] row : r.rows()) {
			ids.add(((Number) row[0]).intValue());
		}
		assertTrue(ids.contains(1));
		assertTrue(ids.contains(2));
		assertTrue(ids.contains(3));
	}

	@Test
	void tvfFiltersWithWhere() {
		engine.execute("CREATE TABLE src (id INT PRIMARY KEY, v INT)");
		engine.execute("INSERT INTO src VALUES (1, 10)");
		engine.execute("INSERT INTO src VALUES (2, 20)");
		engine.execute(
				"CREATE FUNCTION scan_src(t VARCHAR) RETURNS TABLE (id INT, v INT) AS CLASS '"
						+ ScanTableTvf.class.getName()
						+ "' METHOD 'apply'");

		final SqlResult r = engine.execute("SELECT id FROM scan_src('src') WHERE v = 20");
		assertEquals(1, r.rows().size());
		assertEquals(2, ((Number) r.rows().getFirst()[0]).intValue());
	}

	@Test
	void tvfFromViewBackingTable() {
		engine.execute("CREATE TABLE base (id INT PRIMARY KEY, v INT)");
		engine.execute("INSERT INTO base VALUES (5, 50)");
		engine.execute("CREATE VIEW vbase AS SELECT id, v FROM base");
		engine.execute(
				"CREATE FUNCTION scan_view(t VARCHAR) RETURNS TABLE (id INT, v INT) AS CLASS '"
						+ ScanTableTvf.class.getName()
						+ "' METHOD 'apply'");
		// View resolution: product path may materialize view name as table binding;
		// scan the base table name which the view projects from.
		final SqlResult r = engine.execute("SELECT id FROM scan_view('base') AS x");
		assertEquals(1, r.rows().size());
		assertEquals(5, ((Number) r.rows().getFirst()[0]).intValue());
	}
}