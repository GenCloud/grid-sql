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
import org.genfork.grid.sql.SqlServerRuntime;
import org.genfork.grid.sql.SqlSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bare FROM/JOIN in CREATE VIEW under a non-public schema must stay session-invariant
 * when selecting the FQ view from session {@code public}.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.1
 */
class SchemaQualifiedViewBodyIT {
	private static final String SCHEMA = "shared_game_server";

	@TempDir
	Path tempDir;

	@Test
	void setSchemaBareViewSelectFromPublicSession() {
		final Path dataDir = tempDir.resolve("view-bare");
		try (SqlServerRuntime runtime = SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(2)
				.durability(false)
				.build()) {
			final SqlEngine engine = runtime.engine();
			engine.execute("CREATE SCHEMA " + SCHEMA);
			engine.execute("CREATE TABLE " + SCHEMA + ".base (id INT PRIMARY KEY, v INT)");
			engine.execute("CREATE TABLE " + SCHEMA + ".other (id INT PRIMARY KEY, base_id INT)");
			engine.execute("INSERT INTO " + SCHEMA + ".base VALUES (1, 42)");
			engine.execute("INSERT INTO " + SCHEMA + ".other VALUES (10, 1)");
			final SqlSession session = engine.newSession();
			engine.execute(session, "SET SCHEMA " + SCHEMA);
			engine.execute(session,
					"CREATE VIEW v AS SELECT b.id, b.v FROM base b JOIN other o ON o.base_id = b.id");
			final TableCatalog.ViewDef def = engine.catalog().getView(SCHEMA + ".v");
			assertTrue(def.selectSql().toLowerCase().contains(SCHEMA + ".base")
					|| engine.catalog().viewSelectBodies().get(SCHEMA + ".v").toLowerCase()
					.contains(SCHEMA + ".base"));
			final SqlResult rows = engine.execute("SELECT id, v FROM " + SCHEMA + ".v");
			assertEquals(1, rows.rows().size());
			assertEquals(1, ((Number) rows.rows().getFirst()[0]).intValue());
			assertEquals(42, ((Number) rows.rows().getFirst()[1]).intValue());
		}
	}

	@Test
	void fqViewNameBareFromUnderPublicSession() {
		final Path dataDir = tempDir.resolve("view-fq");
		try (SqlServerRuntime runtime = SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(2)
				.durability(false)
				.build()) {
			final SqlEngine engine = runtime.engine();
			engine.execute("CREATE SCHEMA " + SCHEMA);
			engine.execute("CREATE TABLE " + SCHEMA + ".base (id INT PRIMARY KEY)");
			engine.execute("INSERT INTO " + SCHEMA + ".base VALUES (7)");
			engine.execute("CREATE VIEW " + SCHEMA + ".v2 AS SELECT id FROM base");
			final SqlResult rows = engine.execute("SELECT id FROM " + SCHEMA + ".v2");
			assertEquals(7, ((Number) rows.rows().getFirst()[0]).intValue());
		}
	}

	@Test
	void durableRestartKeepsBareViewBodyResolvable() {
		final Path dataDir = tempDir.resolve("view-restart");
		try (SqlServerRuntime runtime = SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(2)
				.durability(true)
				.build()) {
			final SqlEngine engine = runtime.engine();
			engine.execute("CREATE SCHEMA " + SCHEMA);
			engine.execute("CREATE TABLE " + SCHEMA + ".base (id INT PRIMARY KEY, v INT)");
			engine.execute("INSERT INTO " + SCHEMA + ".base VALUES (2, 9)");
			final SqlSession session = engine.newSession();
			engine.execute(session, "SET SCHEMA " + SCHEMA);
			engine.execute(session, "CREATE VIEW v AS SELECT id, v FROM base");
		}
		try (SqlServerRuntime runtime = SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(2)
				.durability(true)
				.build()) {
			final SqlResult rows = runtime.engine().execute("SELECT id, v FROM " + SCHEMA + ".v");
			assertEquals(2, ((Number) rows.rows().getFirst()[0]).intValue());
			assertEquals(9, ((Number) rows.rows().getFirst()[1]).intValue());
		}
	}

	@Test
	void multilineJournalCreateViewRecoversAndSelects() throws Exception {
		final Path dataDir = tempDir.resolve("view-multiline-journal");
		try (SqlServerRuntime runtime = SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(2)
				.durability(true)
				.build()) {
			final SqlEngine engine = runtime.engine();
			engine.execute("CREATE SCHEMA " + SCHEMA);
			engine.execute("CREATE TABLE " + SCHEMA + ".base (id INT PRIMARY KEY, v INT)");
			engine.execute("INSERT INTO " + SCHEMA + ".base VALUES (3, 30)");
			final SqlSession session = engine.newSession();
			engine.execute(session, "SET SCHEMA " + SCHEMA);
			engine.execute(session, "CREATE VIEW v AS SELECT id, v FROM base");
		}
		final Path ddl = dataDir.resolve("catalog").resolve("ddl.sql");
		final Path ddlNested = dataDir.resolve("catalog").resolve("catalog").resolve("ddl.sql");
		final Path ddlFile = Files.isRegularFile(ddl) ? ddl : ddlNested;
		final String raw = Files.readString(ddlFile);
		final String marker = " AS SELECT id, v FROM ";
		final int at = raw.indexOf(marker);
		org.junit.jupiter.api.Assertions.assertTrue(at >= 0, "expected CREATE VIEW body in " + ddlFile);
		final String poisoned = raw.substring(0, at) + " AS SELECT id,\n       v\nFROM "
				+ raw.substring(at + marker.length());
		Files.writeString(ddlFile, poisoned);
		try (SqlServerRuntime runtime = SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(2)
				.durability(true)
				.build()) {
			final SqlResult rows = runtime.engine().execute("SELECT id, v FROM " + SCHEMA + ".v");
			assertEquals(3, ((Number) rows.rows().getFirst()[0]).intValue());
			assertEquals(30, ((Number) rows.rows().getFirst()[1]).intValue());
		}
	}
}
