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
package index.unit.sql;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.genfork.grid.catalog.PrivilegeCatalog;
import org.genfork.grid.catalog.SqlPrivilege;
import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.SqlSession;
import org.genfork.grid.sql.SqlStatementTag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RBAC SQL authorization, snapshots, and durable restart recovery.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class RbacSqlIT {
	private static final int SHARDS = 4;
	private static final String CATALOG_DIRECTORY = "catalog";

	@TempDir
	Path dataDir;

	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(), null, SHARDS);
	}

	@Test
	void openCatalogAllowsAll() {
		assertTrue(engine.privileges().isOpen());
		engine.execute("CREATE TABLE rbac_open (id INT PRIMARY KEY, v VARCHAR)");
		engine.execute("INSERT INTO rbac_open VALUES (1, 'a')");
		final SqlResult rows = engine.execute("SELECT id FROM rbac_open");
		assertEquals(1, rows.rows().size());
	}

	@Test
	void createUserGrantSelectDenyInsertThenGrantInsert() {
		engine.execute("CREATE TABLE rbac_t (id INT PRIMARY KEY, v VARCHAR)");
		engine.execute("INSERT INTO rbac_t VALUES (1, 'seed')");

		final SqlResult created = engine.execute("CREATE USER admin PASSWORD 'secret'");
		assertEquals(SqlStatementTag.CREATE_USER.wire(), created.tag());
		assertTrue(!engine.privileges().isOpen());

		final SqlSession admin = engine.newSession("admin", true);
		engine.execute(admin, "CREATE USER alice PASSWORD 'pw'");
		engine.execute(admin, "GRANT SELECT ON TABLE rbac_t TO alice");

		final SqlSession alice = engine.newSession("alice", true);
		final SqlResult selected = engine.execute(alice, "SELECT id, v FROM rbac_t");
		assertEquals(1, selected.rows().size());

		assertThrows(SecurityException.class,
				() -> engine.execute(alice, "INSERT INTO rbac_t VALUES (2, 'x')"));

		engine.execute(admin, "GRANT INSERT ON TABLE rbac_t TO alice");
		assertDoesNotThrow(() -> engine.execute(alice, "INSERT INTO rbac_t VALUES (2, 'x')"));
		final SqlResult after = engine.execute(alice, "SELECT id FROM rbac_t ORDER BY id");
		assertEquals(2, after.rows().size());
	}

	@Test
	void privilegeSnapshotRoundTrip() {
		engine.execute("CREATE TABLE snap_t (id INT PRIMARY KEY)");
		engine.execute("CREATE USER admin PASSWORD 'secret'");
		final SqlSession admin = engine.newSession("admin", true);
		engine.execute(admin, "CREATE USER bob PASSWORD 'b'");
		engine.execute(admin, "GRANT SELECT ON TABLE snap_t TO bob");

		final byte[] snapshot = engine.privilegeSnapshotBytes();
		final SqlEngine restored = new SqlEngine(new TableCatalog(), null, SHARDS);
		restored.loadPrivilegeSnapshotBytes(snapshot);
		assertTrue(restored.privileges().authenticate("bob", "b"));
		assertDoesNotThrow(() -> restored.privileges().ensure("bob", "public", "snap_t",
				SqlPrivilege.SELECT));
		assertThrows(SecurityException.class,
				() -> restored.privileges().ensure("bob", "public", "snap_t",
						SqlPrivilege.INSERT));
	}

	@Test
	void restartLoadsPersistedUsersAndGrants() {
		final SqlEngine writer = new SqlEngine(new TableCatalog(dataDir), null, SHARDS);
		writer.execute("CREATE USER admin PASSWORD 'secret'");
		final SqlSession admin = writer.newSession("admin", true);
		writer.execute(admin, "CREATE USER reader PASSWORD 'reader-secret'");
		writer.execute(admin, "GRANT SELECT ON TABLE restart_t TO reader");

		final Path privilegeMeta = dataDir.resolve(CATALOG_DIRECTORY)
				.resolve(PrivilegeCatalog.META_FILE_NAME);
		assertTrue(Files.isRegularFile(privilegeMeta));

		final SqlEngine restored = new SqlEngine(new TableCatalog(dataDir), null, SHARDS);
		assertFalse(restored.privileges().isOpen());
		assertTrue(restored.privileges().authenticate("reader", "reader-secret"));
		assertDoesNotThrow(() -> restored.privileges().ensure(
				"reader", "public", "restart_t", SqlPrivilege.SELECT));
		assertThrows(SecurityException.class, () -> restored.privileges().ensure(
				"reader", "public", "restart_t", SqlPrivilege.INSERT));
	}

	@Test
	void missingOrEmptyPrivilegeMetaKeepsOpenAuthentication() throws IOException {
		final SqlEngine missing = new SqlEngine(new TableCatalog(dataDir.resolve("missing")), null, SHARDS);
		assertTrue(missing.privileges().isOpen());

		final Path emptyDataDir = dataDir.resolve("empty");
		final Path catalogDir = emptyDataDir.resolve(CATALOG_DIRECTORY);
		Files.createDirectories(catalogDir);
		Files.write(catalogDir.resolve(PrivilegeCatalog.META_FILE_NAME), new byte[0]);

		final SqlEngine empty = new SqlEngine(new TableCatalog(emptyDataDir), null, SHARDS);
		assertTrue(empty.privileges().isOpen());
	}

	@Test
	void createRoleGrantToRoleAndMembership() {
		engine.execute("CREATE TABLE role_t (id INT PRIMARY KEY, v VARCHAR)");
		engine.execute("INSERT INTO role_t VALUES (1, 'seed')");
		engine.execute("CREATE USER admin PASSWORD 'secret'");
		final SqlSession admin = engine.newSession("admin", true);
		engine.execute(admin, "CREATE USER alice PASSWORD 'pw'");
		final SqlResult createdRole = engine.execute(admin, "CREATE ROLE readers");
		assertEquals(SqlStatementTag.CREATE_ROLE.wire(), createdRole.tag());
		engine.execute(admin, "GRANT SELECT ON TABLE role_t TO ROLE readers");
		engine.execute(admin, "GRANT ROLE readers TO alice");

		final SqlSession alice = engine.newSession("alice", true);
		final SqlResult selected = engine.execute(alice, "SELECT id FROM role_t");
		assertEquals(1, selected.rows().size());
		assertThrows(SecurityException.class,
				() -> engine.execute(alice, "INSERT INTO role_t VALUES (2, 'x')"));

		engine.execute(admin, "DROP ROLE readers");
		assertThrows(SecurityException.class,
				() -> engine.execute(alice, "SELECT id FROM role_t"));
	}
}