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
import java.nio.file.Path;

import org.genfork.grid.catalog.PrivilegeCatalog;
import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlServerRuntime;
import org.genfork.grid.sql.SqlSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bootstrap master user (grid/grid) and administrator full-power semantics.
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
class MasterAuthBootstrapIT {
	@TempDir
	Path dataDir;

	@Test
	void runtimeSeedsDefaultMasterWithFullPower() throws Exception {
		final Path dir = Files.createDirectories(dataDir.resolve("master-seed"));
		try (SqlServerRuntime runtime = SqlServerRuntime.builder()
				.dataDir(dir)
				.shards(4)
				.durability(false)
				.listen(false)
				.build()) {
			final PrivilegeCatalog privileges = runtime.engine().privileges();
			assertFalse(privileges.isOpen());
			assertTrue(privileges.userExists(SqlServerRuntime.DEFAULT_AUTH_USER));
			assertTrue(privileges.isAdministrator(SqlServerRuntime.DEFAULT_AUTH_USER));
			assertTrue(privileges.authenticate(
					SqlServerRuntime.DEFAULT_AUTH_USER,
					SqlServerRuntime.DEFAULT_AUTH_PASSWORD));
			assertFalse(privileges.authenticate(SqlServerRuntime.DEFAULT_AUTH_USER, "wrong"));

			final SqlSession master = runtime.engine().newSession(SqlServerRuntime.DEFAULT_AUTH_USER, true);
			assertDoesNotThrow(() -> runtime.engine().execute(master, "CREATE SCHEMA app_full"));
			assertDoesNotThrow(() -> runtime.engine().execute(master,
					"CREATE TABLE app_full.t (id INT PRIMARY KEY, v INT)"));
			assertDoesNotThrow(() -> runtime.engine().execute(master,
					"INSERT INTO app_full.t (id, v) VALUES (1, 10)"));
			assertEquals(1, runtime.engine().execute(master, "SELECT v FROM app_full.t WHERE id = 1")
					.rows().size());
			assertDoesNotThrow(() -> runtime.engine().execute(master,
					"CREATE USER app PASSWORD 'pw'"));
		}
	}

	@Test
	void blankAuthKeepsOpenCatalog() throws Exception {
		final Path dir = Files.createDirectories(dataDir.resolve("open-auth"));
		try (SqlServerRuntime runtime = SqlServerRuntime.builder()
				.dataDir(dir)
				.shards(4)
				.durability(false)
				.listen(false)
				.auth("", "")
				.build()) {
			assertTrue(runtime.engine().privileges().isOpen());
		}
	}

	@Test
	void nonAdminWithoutGrantsDenied() {
		final SqlEngine engine = new SqlEngine(new TableCatalog(), null, 4);
		engine.execute("CREATE USER admin PASSWORD 'secret'");
		final SqlSession admin = engine.newSession("admin", true);
		engine.execute(admin, "CREATE USER bob PASSWORD 'pw'");
		engine.execute(admin, "CREATE TABLE t (id INT PRIMARY KEY)");
		final SqlSession bob = engine.newSession("bob", true);
		assertThrows(SecurityException.class,
				() -> engine.execute(bob, "SELECT id FROM t"));
		assertDoesNotThrow(() -> engine.execute(admin, "SELECT id FROM t"));
	}
}