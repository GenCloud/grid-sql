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

import org.genfork.grid.catalog.CatalogQualifiedName;
import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.SqlSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Schema isolation + explicit cross-schema SELECT/JOIN.
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
class SchemaIsolationCrossSchemaIT {
	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(), null, 4);
	}

	@Test
	void sameLocalNameIsolatedAcrossSchemas() {
		engine.execute("CREATE SCHEMA a");
		engine.execute("CREATE SCHEMA b");
		engine.execute("CREATE TABLE a.t (id INT PRIMARY KEY, v VARCHAR)");
		engine.execute("CREATE TABLE b.t (id INT PRIMARY KEY, v VARCHAR)");
		engine.execute("INSERT INTO a.t VALUES (1, 'a')");
		engine.execute("INSERT INTO b.t VALUES (1, 'b')");
		assertEquals("a", engine.catalog().requireSchema("a.t").schemaName());
		assertEquals("t", engine.catalog().requireSchema("a.t").tableName());
		assertFalse(engine.catalog().requireSchema("a.t").tableName().contains("."));
		final SqlResult ra = engine.execute("SELECT v FROM a.t WHERE id = 1");
		final SqlResult rb = engine.execute("SELECT v FROM b.t WHERE id = 1");
		assertEquals("a", ra.rows().getFirst()[0]);
		assertEquals("b", rb.rows().getFirst()[0]);
	}

	@Test
	void unqualifiedResolvesOnlyCurrentSchema() {
		engine.execute("CREATE SCHEMA a");
		engine.execute("CREATE SCHEMA b");
		engine.execute("CREATE TABLE a.t (id INT PRIMARY KEY, v INT)");
		engine.execute("CREATE TABLE b.t (id INT PRIMARY KEY, v INT)");
		engine.execute("INSERT INTO a.t VALUES (1, 10)");
		engine.execute("INSERT INTO b.t VALUES (1, 20)");
		final SqlSession sa = engine.newSession();
		engine.execute(sa, "SET SCHEMA a");
		assertEquals(10, engine.execute(sa, "SELECT v FROM t WHERE id = 1").rows().getFirst()[0]);
		final SqlSession sb = engine.newSession();
		engine.execute(sb, "SET SCHEMA b");
		assertEquals(20, engine.execute(sb, "SELECT v FROM t WHERE id = 1").rows().getFirst()[0]);
	}

	@Test
	void crossSchemaJoinExplicitQualifier() {
		engine.execute("CREATE SCHEMA left_s");
		engine.execute("CREATE SCHEMA right_s");
		engine.execute("CREATE TABLE left_s.a (id INT PRIMARY KEY, k INT)");
		engine.execute("CREATE TABLE right_s.b (id INT PRIMARY KEY, k INT, label VARCHAR)");
		engine.execute("INSERT INTO left_s.a VALUES (1, 7)");
		engine.execute("INSERT INTO right_s.b VALUES (10, 7, 'ok')");
		final SqlResult r = engine.execute(
				"SELECT label FROM left_s.a JOIN right_s.b ON k = k");
		assertEquals(1, r.rows().size());
		assertEquals("ok", r.rows().getFirst()[0]);
	}

	@Test
	void crossSchemaForeignKey() {
		engine.execute("CREATE SCHEMA parent_s");
		engine.execute("CREATE SCHEMA child_s");
		engine.execute("CREATE TABLE parent_s.p (id INT PRIMARY KEY)");
		engine.execute(
				"CREATE TABLE child_s.c (id INT PRIMARY KEY, pid INT, "
						+ "FOREIGN KEY (pid) REFERENCES parent_s.p (id))");
		engine.execute("INSERT INTO parent_s.p VALUES (1)");
		engine.execute("INSERT INTO child_s.c VALUES (10, 1)");
		assertEquals(1, engine.execute("SELECT id FROM child_s.c WHERE pid = 1").rows().size());
		assertThrows(RuntimeException.class, () -> engine.execute("INSERT INTO child_s.c VALUES (11, 99)"));
	}

	@Test
	void qualifiedNameCatalogKey() {
		final CatalogQualifiedName q = CatalogQualifiedName.of("auth_server", "t");
		assertEquals("auth_server.t", q.catalogKey());
		assertEquals("t", CatalogQualifiedName.parse("t", "public").catalogKey());
		assertEquals("public", CatalogQualifiedName.parse("t", "public").schemaName());
	}

	@Test
	void crossSchemaViewInformationSchemaNoReverseParse() {
		engine.execute("CREATE SCHEMA a");
		engine.execute("CREATE TABLE a.base (id INT PRIMARY KEY, v INT)");
		engine.execute("CREATE VIEW a.v AS SELECT id, v FROM a.base");
		final TableCatalog.ViewDef def = engine.catalog().getView("a.v");
		assertEquals("a", def.schemaName());
		assertEquals("v", def.objectName());
		assertEquals("a.v", def.catalogKey());
		final SqlResult rows = engine.execute(
				"SELECT table_schema, table_name, table_type FROM information_schema.tables");
		boolean found = false;
		for (Object[] row : rows.rows()) {
			if ("a".equals(row[0]) && "v".equals(row[1]) && "VIEW".equals(row[2])) {
				found = true;
				break;
			}
		}
		assertTrue(found);
	}

	@Test
	void crossSchemaViewSelectExplicitQualifier() {
		engine.execute("CREATE SCHEMA a");
		engine.execute("CREATE SCHEMA b");
		engine.execute("CREATE TABLE a.base (id INT PRIMARY KEY)");
		engine.execute("CREATE TABLE b.base (id INT PRIMARY KEY)");
		engine.execute("INSERT INTO a.base VALUES (1)");
		engine.execute("INSERT INTO b.base VALUES (2)");
		engine.execute("CREATE VIEW a.v AS SELECT id FROM a.base");
		engine.execute("CREATE VIEW b.v AS SELECT id FROM b.base");
		assertEquals(1, engine.execute("SELECT id FROM a.v").rows().getFirst()[0]);
		assertEquals(2, engine.execute("SELECT id FROM b.v").rows().getFirst()[0]);
	}
}