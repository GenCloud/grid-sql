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

import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Virtual information_schema catalog views (ANTLR SELECT path).
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class SqlInformationSchemaTest {
	private SqlEngine engine;

	@BeforeEach
	void setUp() throws Exception {
		engine = new SqlEngine(new TableCatalog(Files.createTempDirectory("info-schema")), null, 4);
		engine.execute("CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR)");
		engine.execute("CREATE INDEX idx_t_name ON t (name)");
	}

	@Test
	void schemataIncludesPublic() {
		final SqlResult r = engine.execute("SELECT schema_name FROM information_schema.schemata");
		final Set<String> names = new HashSet<>();
		for (Object[] row : r.rows()) {
			names.add(String.valueOf(row[0]));
		}
		assertTrue(names.contains("public"));
	}

	@Test
	void tablesListsBaseTable() {
		final SqlResult r = engine.execute(
				"SELECT table_name, table_type FROM information_schema.tables");
		boolean found = false;
		for (Object[] row : r.rows()) {
			if ("t".equalsIgnoreCase(String.valueOf(row[0]))) {
				assertEquals("BASE TABLE", row[1]);
				found = true;
			}
		}
		assertTrue(found);
	}

	@Test
	void columnsListsIdAndName() {
		final SqlResult r = engine.execute(
				"SELECT column_name FROM information_schema.columns WHERE table_name = 't'");
		final Set<String> cols = new HashSet<>();
		for (Object[] row : r.rows()) {
			cols.add(String.valueOf(row[0]).toLowerCase());
		}
		assertTrue(cols.contains("id"));
		assertTrue(cols.contains("name"));
	}

	@Test
	void statisticsListsIndex() {
		final SqlResult r = engine.execute(
				"SELECT index_name FROM information_schema.statistics WHERE table_name = 't'");
		boolean found = false;
		for (Object[] row : r.rows()) {
			if (String.valueOf(row[0]).toLowerCase().contains("name")) {
				found = true;
			}
		}
		assertTrue(found);
	}

	@Test
	void selectQualifiedPublicSchemaResolves() {
		engine.execute("CREATE TABLE append_domain (id INT PRIMARY KEY, v INT)");
		final SqlResult r = engine.execute("SELECT id FROM public.append_domain");
		assertEquals(0, r.rows().size());
		engine.execute("INSERT INTO public.append_domain (id, v) VALUES (1, 10)");
		final SqlResult r2 = engine.execute("SELECT v FROM public.append_domain WHERE id = 1");
		assertEquals(1, r2.rows().size());
		assertEquals(10, ((Number) r2.rows().getFirst()[0]).intValue());
	}

	@Test
	void lowercaseKeywordsParse() {
		engine.execute("create table mixed_case_kw (id int primary key, v int)");
		engine.execute("insert into mixed_case_kw (id, v) values (1, 42)");
		final SqlResult r = engine.execute("select v from mixed_case_kw where id = 1");
		assertEquals(1, r.rows().size());
		assertEquals(42, ((Number) r.rows().getFirst()[0]).intValue());
	}
}
