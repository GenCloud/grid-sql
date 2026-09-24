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
import org.genfork.grid.sql.SqlSession;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Minimal RBAC lifecycle and SELECT denial integration checks.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public class RbacAuthorizationIT {
	@Test
	void selectRequiresGrantOnceUsersExist() {
		final SqlEngine engine = new SqlEngine(new TableCatalog(), null, 1);
		engine.execute("CREATE TABLE secure_t (id INT PRIMARY KEY, value INT)");
		engine.execute("INSERT INTO secure_t VALUES (1, 10)");
		engine.execute("CREATE USER admin PASSWORD 'admin-secret'");

		final SqlSession admin = engine.newSession("admin", true);
		engine.execute(admin, "CREATE USER reader PASSWORD 'reader-secret'");
		final SqlSession reader = engine.newSession("reader", true);

		assertThrows(SecurityException.class, () -> engine.execute(reader, "SELECT * FROM secure_t"));
		engine.execute(admin, "GRANT SELECT ON TABLE secure_t TO reader");
		assertDoesNotThrow(() -> engine.execute(reader, "SELECT * FROM secure_t"));
		engine.execute(admin, "REVOKE SELECT ON TABLE secure_t FROM reader");
		assertThrows(SecurityException.class, () -> engine.execute(reader, "SELECT * FROM secure_t"));
		assertTrue(engine.privileges().authenticate("reader", "reader-secret"));
	}

	@Test
	void joinRequiresSelectOnBothTables() {
		final SqlEngine engine = new SqlEngine(new TableCatalog(), null, 1);
		engine.execute("CREATE TABLE left_t (id INT PRIMARY KEY, v INT)");
		engine.execute("CREATE TABLE right_t (id INT PRIMARY KEY, v INT)");
		engine.execute("INSERT INTO left_t VALUES (1, 10)");
		engine.execute("INSERT INTO right_t VALUES (1, 20)");
		engine.execute("CREATE USER admin PASSWORD 'admin-secret'");
		final SqlSession admin = engine.newSession("admin", true);
		engine.execute(admin, "CREATE USER reader PASSWORD 'reader-secret'");
		engine.execute(admin, "GRANT SELECT ON TABLE left_t TO reader");
		final SqlSession reader = engine.newSession("reader", true);
		assertThrows(SecurityException.class,
				() -> engine.execute(reader, "SELECT * FROM left_t INNER JOIN right_t ON left_t.id = right_t.id"));
		engine.execute(admin, "GRANT SELECT ON TABLE right_t TO reader");
		assertDoesNotThrow(
				() -> engine.execute(reader, "SELECT * FROM left_t INNER JOIN right_t ON left_t.id = right_t.id"));
	}

	@Test
	void alterUserPasswordChangesCredential() {
		final SqlEngine engine = new SqlEngine(new TableCatalog(), null, 1);
		engine.execute("CREATE USER admin PASSWORD 'admin-secret'");
		final SqlSession admin = engine.newSession("admin", true);
		engine.execute(admin, "CREATE USER reader PASSWORD 'old-secret'");
		assertTrue(engine.privileges().authenticate("reader", "old-secret"));
		engine.execute(admin, "ALTER USER reader PASSWORD 'new-secret'");
		assertFalse(engine.privileges().authenticate("reader", "old-secret"));
		assertTrue(engine.privileges().authenticate("reader", "new-secret"));
	}
}
