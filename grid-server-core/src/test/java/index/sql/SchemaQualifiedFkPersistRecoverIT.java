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

import org.genfork.grid.catalog.FkDef;
import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlServerRuntime;
import org.genfork.grid.sql.SqlSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Session-resolved FK parents must persist as catalogKey for recover / OpLog hydrate / peers.
 * <p>
 * Mirrors {@code after_restart.log}: unqualified {@code REFERENCES} under session {@code public}
 * must not rebind parents to {@code public.*}.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.1
 */
class SchemaQualifiedFkPersistRecoverIT {
	private static final String SCHEMA = "app_fk";
	private static final String PARENT = "app_fk.crests";
	private static final String CHILD = "app_fk.pledge_data";
	private static final String REF_QUALIFIED = "REFERENCES app_fk.crests";
	private static final String REF_BARE_CRESTS = "REFERENCES crests (";

	@TempDir
	Path tempDir;

	@Test
	void setSchemaUnqualifiedFkPersistsCatalogKeyParent() throws Exception {
		final Path dataDir = tempDir.resolve("journal");
		final SqlEngine engine = new SqlEngine(new TableCatalog(dataDir), null, 2);
		seedSchemaFk(engine);

		final List<String> lines = engine.catalog().loadPersistedDdl();
		final String joined = String.join("\n", lines);
		assertTrue(joined.contains(REF_QUALIFIED), joined);
		assertFalse(joined.contains(REF_BARE_CRESTS), joined);

		final TableSchema child = engine.catalog().requireSchema(CHILD);
		assertEquals(1, child.foreignKeys().size());
		final FkDef fk = child.foreignKeys().getFirst();
		assertEquals(SCHEMA, fk.parentSchema());
		assertEquals("crests", fk.parentTable());
		assertEquals(PARENT, fk.parentCatalogKey());
	}

	@Test
	void recoverPersistedCatalogKeepsFkParentSchema() throws Exception {
		final Path dataDir = tempDir.resolve("recover");
		final SqlEngine seed = new SqlEngine(new TableCatalog(dataDir), null, 2);
		seedSchemaFk(seed);

		final SqlEngine recovered = new SqlEngine(new TableCatalog(dataDir), null, 2);
		recovered.recoverPersistedCatalog();
		assertTrue(recovered.catalog().exists(CHILD));
		final FkDef fk = recovered.catalog().requireSchema(CHILD).foreignKeys().getFirst();
		assertEquals(SCHEMA, fk.parentSchema());
		assertEquals(PARENT, fk.parentCatalogKey());
	}

	@Test
	void applyReplicatedDdlWithQualifiedReferencesUnderPublicSession() {
		final SqlEngine engine = new SqlEngine(new TableCatalog(), null, 2);
		engine.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA);
		engine.applyReplicatedDdl(
				"CREATE TABLE IF NOT EXISTS " + PARENT + " (id INT PRIMARY KEY)",
				1L);
		engine.applyReplicatedDdl(
				"CREATE TABLE IF NOT EXISTS " + CHILD
						+ " (id INT PRIMARY KEY, crest_id INT, "
						+ "CONSTRAINT pledge_crest_fk FOREIGN KEY (crest_id) "
						+ REF_QUALIFIED + " (id) ON DELETE CASCADE)",
				2L);
		final FkDef fk = engine.catalog().requireSchema(CHILD).foreignKeys().getFirst();
		assertEquals(SCHEMA, fk.parentSchema());
		assertEquals(PARENT, fk.parentCatalogKey());
	}

	@Test
	void compactThenSecondRecoverKeepsForeignKey() throws Exception {
		final Path dataDir = tempDir.resolve("compact");
		final SqlEngine seed = new SqlEngine(new TableCatalog(dataDir), null, 2);
		seedSchemaFk(seed);
		seed.recoverPersistedCatalog();

		final String compacted = Files.readString(
				dataDir.resolve("catalog").resolve("ddl.sql"), StandardCharsets.UTF_8);
		assertTrue(compacted.contains(REF_QUALIFIED), compacted);

		final SqlEngine second = new SqlEngine(new TableCatalog(dataDir), null, 2);
		second.recoverPersistedCatalog();
		final FkDef fk = second.catalog().requireSchema(CHILD).foreignKeys().getFirst();
		assertEquals(SCHEMA, fk.parentSchema());
		assertEquals(PARENT, fk.parentCatalogKey());
	}

	@Test
	void durableRestartHydratesCatalogFkWithoutPublicRebind() {
		final Path dataDir = tempDir.resolve("durable");
		try (SqlServerRuntime first = SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(2)
				.durability(true)
				.build()) {
			seedSchemaFk(first.engine());
		}

		try (SqlServerRuntime second = SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(2)
				.durability(true)
				.build()) {
			final SqlEngine engine = second.engine();
			assertTrue(engine.catalog().exists(CHILD));
			final FkDef fk = engine.catalog().requireSchema(CHILD).foreignKeys().getFirst();
			assertEquals(SCHEMA, fk.parentSchema());
			assertEquals(PARENT, fk.parentCatalogKey());
		}
	}

	@Test
	void poisonDropParentWhileChildReferencedFailsRecover() throws Exception {
		final Path dataDir = tempDir.resolve("poison");
		final Path catalog = dataDir.resolve("catalog");
		Files.createDirectories(catalog);
		Files.writeString(catalog.resolve("schemas.list"), SCHEMA + "\n", StandardCharsets.UTF_8);
		Files.writeString(
				catalog.resolve("ddl.sql"),
				"""
						CREATE SCHEMA IF NOT EXISTS app_fk
						CREATE TABLE IF NOT EXISTS app_fk.crests (id INT PRIMARY KEY)
						CREATE TABLE IF NOT EXISTS app_fk.pledge_data (id INT PRIMARY KEY, crest_id INT, \
						CONSTRAINT pledge_crest_fk FOREIGN KEY (crest_id) REFERENCES app_fk.crests (id))
						DROP TABLE app_fk.crests
						""",
				StandardCharsets.UTF_8);
		final SqlEngine engine = new SqlEngine(new TableCatalog(dataDir), null, 2);
		final IllegalStateException ex = assertThrows(
				IllegalStateException.class,
				engine::recoverPersistedCatalog);
		assertTrue(ex.getMessage().contains("referenced by FOREIGN KEY")
						|| ex.getMessage().contains("cannot DROP TABLE"),
				ex.getMessage());
	}

	/**
	 * Sticky session required: {@code SqlEngine.execute(String)} opens a fresh session each call.
	 */
	private static void seedSchemaFk(SqlEngine engine) {
		final SqlSession session = engine.newSession();
		engine.execute(session, "CREATE SCHEMA IF NOT EXISTS " + SCHEMA);
		engine.execute(session, "SET SCHEMA " + SCHEMA);
		engine.execute(session, "CREATE TABLE crests (id INT PRIMARY KEY)");
		engine.execute(session,
				"CREATE TABLE pledge_data (id INT PRIMARY KEY, crest_id INT, "
						+ "CONSTRAINT pledge_crest_fk FOREIGN KEY (crest_id) "
						+ "REFERENCES crests (id) ON DELETE CASCADE)");
	}
}
