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

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlReplicaDmlDeniedException;
import org.genfork.grid.sql.SqlSession;
import org.genfork.grid.sql.client.SessionRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * READ_REPLICA session admission after ANTLR (no replication coordinator).
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public class SqlReplicaAdmissionSessionTest {
	private SqlEngine engine;

	@BeforeEach
	void setUp() throws Exception {
		engine = new SqlEngine(new TableCatalog(Files.createTempDirectory("replica-adm")), null, 4);
		engine.execute("CREATE TABLE t (id INT PRIMARY KEY, v VARCHAR)");
		engine.execute("INSERT INTO t (id, v) VALUES (1, 'a')");
	}

	@Test
	void readReplicaSelectOkWithoutReplication() {
		final SqlSession session = engine.newSession();
		session.setSessionRole(SessionRole.READ_REPLICA);
		assertDoesNotThrow(() -> engine.execute(session, "SELECT v FROM t WHERE id = 1"));
	}

	@Test
	void readReplicaRejectsInsert() {
		final SqlSession session = engine.newSession();
		session.setSessionRole(SessionRole.READ_REPLICA);
		assertThrows(SqlReplicaDmlDeniedException.class,
				() -> engine.execute(session, "INSERT INTO t (id, v) VALUES (2, 'b')"));
	}

	@Test
	void readReplicaRejectsBegin() {
		final SqlSession session = engine.newSession();
		session.setSessionRole(SessionRole.READ_REPLICA);
		assertThrows(SqlReplicaDmlDeniedException.class,
				() -> engine.execute(session, "BEGIN"));
	}
}