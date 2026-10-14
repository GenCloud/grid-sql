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

import java.nio.file.Files;

import org.genfork.grid.catalog.CatalogPersistUtil;
import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Trigger body bare names resolve under ON-table schema even when session is {@code public}.
 * <p>
 * Repro of live cluster: {@code Unknown table: item_data} on DELETE parent while session schema
 * is not the trigger table schema.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
class SqlTriggerSchemaScopeIT {
	private static final int SHARDS = 4;
	private static final String SCHEMA = "app_trg";
	private static final String PARENT = "parent";
	private static final String ITEM_DATA = "item_data";
	private static final String CHILD_NEST = "child_nest";
	private static final String GRANDCHILD = "grandchild";
	private static final String QUAL_PARENT = SCHEMA + "." + PARENT;
	private static final String QUAL_ITEM = SCHEMA + "." + ITEM_DATA;
	private static final String QUAL_CHILD = SCHEMA + "." + CHILD_NEST;
	private static final String QUAL_GRAND = SCHEMA + "." + GRANDCHILD;

	private SqlEngine engine;

	@BeforeEach
	void setUp() throws Exception {
		engine = new SqlEngine(new TableCatalog(Files.createTempDirectory("trg-schema-scope-it")), null, SHARDS);
	}

	@Test
	void bareItemDeleteInTriggerBodyFromPublicSession() {
		engine.execute("CREATE SCHEMA " + SCHEMA);
		final SqlSession setup = engine.newSession();
		engine.execute(setup, "SET SCHEMA " + SCHEMA);
		engine.execute(setup, "CREATE TABLE " + PARENT + " (id INT PRIMARY KEY)");
		engine.execute(setup, "CREATE TABLE " + ITEM_DATA + " (id INT PRIMARY KEY, owner_id INT)");
		engine.execute(setup,
				"CREATE TRIGGER player_cascade_remove_items_trigger BEFORE DELETE ON " + PARENT
						+ " FOR EACH ROW AS 'DELETE FROM " + ITEM_DATA + " WHERE owner_id = OLD.id'");
		engine.execute(setup, "INSERT INTO " + PARENT + " (id) VALUES (1)");
		engine.execute(setup, "INSERT INTO " + ITEM_DATA + " (id, owner_id) VALUES (10, 1)");
		engine.execute(setup, "INSERT INTO " + ITEM_DATA + " (id, owner_id) VALUES (11, 1)");

		final SqlSession publicSession = engine.newSession();
		engine.execute(publicSession, "SET SCHEMA " + CatalogPersistUtil.SCHEMA_PUBLIC);
		assertEquals(CatalogPersistUtil.SCHEMA_PUBLIC, publicSession.currentSchema());

		engine.execute(publicSession, "DELETE FROM " + QUAL_PARENT + " WHERE id = 1");

		assertEquals(CatalogPersistUtil.SCHEMA_PUBLIC, publicSession.currentSchema());
		assertTrue(engine.execute("SELECT id FROM " + QUAL_PARENT + " WHERE id = 1").rows().isEmpty());
		assertTrue(engine.execute("SELECT id FROM " + QUAL_ITEM + " WHERE owner_id = 1").rows().isEmpty());
	}

	@Test
	void qualifiedBodyStillWorksFromPublicSession() {
		engine.execute("CREATE SCHEMA " + SCHEMA);
		final SqlSession setup = engine.newSession();
		engine.execute(setup, "SET SCHEMA " + SCHEMA);
		engine.execute(setup, "CREATE TABLE " + PARENT + " (id INT PRIMARY KEY)");
		engine.execute(setup, "CREATE TABLE " + ITEM_DATA + " (id INT PRIMARY KEY, owner_id INT)");
		engine.execute(setup,
				"CREATE TRIGGER trg_fq_body BEFORE DELETE ON " + PARENT
						+ " FOR EACH ROW AS 'DELETE FROM " + QUAL_ITEM + " WHERE owner_id = OLD.id'");
		engine.execute(setup, "INSERT INTO " + PARENT + " (id) VALUES (2)");
		engine.execute(setup, "INSERT INTO " + ITEM_DATA + " (id, owner_id) VALUES (20, 2)");

		final SqlSession publicSession = engine.newSession();
		engine.execute(publicSession, "SET SCHEMA " + CatalogPersistUtil.SCHEMA_PUBLIC);
		engine.execute(publicSession, "DELETE FROM " + QUAL_PARENT + " WHERE id = 2");

		assertEquals(CatalogPersistUtil.SCHEMA_PUBLIC, publicSession.currentSchema());
		assertTrue(engine.execute("SELECT id FROM " + QUAL_ITEM + " WHERE owner_id = 2").rows().isEmpty());
	}

	@Test
	void nestedTriggerRestoresCallerSchema() {
		engine.execute("CREATE SCHEMA " + SCHEMA);
		final SqlSession setup = engine.newSession();
		engine.execute(setup, "SET SCHEMA " + SCHEMA);
		engine.execute(setup, "CREATE TABLE " + PARENT + " (id INT PRIMARY KEY)");
		engine.execute(setup, "CREATE TABLE " + CHILD_NEST + " (id INT PRIMARY KEY, parent_id INT)");
		engine.execute(setup, "CREATE TABLE " + GRANDCHILD + " (id INT PRIMARY KEY, child_id INT)");
		engine.execute(setup,
				"CREATE TRIGGER trg_parent_del BEFORE DELETE ON " + PARENT
						+ " FOR EACH ROW AS 'DELETE FROM " + CHILD_NEST + " WHERE parent_id = OLD.id'");
		engine.execute(setup,
				"CREATE TRIGGER trg_child_del BEFORE DELETE ON " + CHILD_NEST
						+ " FOR EACH ROW AS 'DELETE FROM " + GRANDCHILD + " WHERE child_id = OLD.id'");
		engine.execute(setup, "INSERT INTO " + PARENT + " (id) VALUES (3)");
		engine.execute(setup, "INSERT INTO " + CHILD_NEST + " (id, parent_id) VALUES (30, 3)");
		engine.execute(setup, "INSERT INTO " + GRANDCHILD + " (id, child_id) VALUES (300, 30)");

		final SqlSession publicSession = engine.newSession();
		engine.execute(publicSession, "SET SCHEMA " + CatalogPersistUtil.SCHEMA_PUBLIC);
		engine.execute(publicSession, "DELETE FROM " + QUAL_PARENT + " WHERE id = 3");

		assertEquals(CatalogPersistUtil.SCHEMA_PUBLIC, publicSession.currentSchema());
		assertTrue(engine.execute("SELECT id FROM " + QUAL_PARENT + " WHERE id = 3").rows().isEmpty());
		assertTrue(engine.execute("SELECT id FROM " + QUAL_CHILD + " WHERE id = 30").rows().isEmpty());
		assertTrue(engine.execute("SELECT id FROM " + QUAL_GRAND + " WHERE id = 300").rows().isEmpty());
	}
}
