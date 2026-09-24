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

import org.genfork.grid.context.config.GridAutoConfiguration;
import org.genfork.grid.context.config.GridSqlAutoConfiguration;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.SqlServerRuntime;
import org.genfork.grid.sql.client.RemoteConnectionFactory;
import org.genfork.grid.sql.client.Result;
import org.genfork.grid.sql.netty.SqlServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Spring Boot SQL path: Environment → SqlServerRuntime + TCP; client via remote factory.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
@SpringBootTest(classes = {
		GridAutoConfiguration.class,
		GridSqlAutoConfiguration.class
})
public class SqlSpringServerIT {
	private static final int PORT = 25442;

	@TempDir
	static Path tempDir;

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		registry.add("grid.durability.enabled", () -> "false");
		registry.add("grid.sql.data-dir", () -> tempDir.resolve("catalog").toString());
		registry.add("grid.sql.default-shards", () -> "4");
		registry.add("grid.sql-server.enabled", () -> "true");
		registry.add("grid.sql-server.host", () -> "127.0.0.1");
		registry.add("grid.sql-server.port", () -> Integer.toString(PORT));
		registry.add("grid.sql-server.user", () -> "u");
		registry.add("grid.sql-server.password", () -> "p");
	}

	@Autowired
	private SqlServerRuntime runtime;

	@Autowired
	private SqlEngine engine;

	@Autowired
	private SqlServer sqlServer;

	@Test
	void runtimeTcpAndRemoteClient() {
		assertNotNull(runtime);
		assertNotNull(sqlServer);
		assertEquals(PORT, sqlServer.port());

		final RemoteConnectionFactory remote = new RemoteConnectionFactory("127.0.0.1", PORT, "u", "p");
		try {
			remote.obtain()
					.flatMapMany(conn -> conn.createStatement(
									"CREATE TABLE se (id INT PRIMARY KEY, v VARCHAR)")
							.execute()
							.flatMap(Result::getRowsUpdated))
					.blockLast();
			remote.obtain()
					.flatMapMany(conn -> conn.createStatement(
									"INSERT INTO se (id, v) VALUES (3, 'spring')")
							.execute()
							.flatMap(Result::getRowsUpdated))
					.blockLast();
		} finally {
			remote.dispose();
		}

		final SqlResult sel = engine.execute("SELECT v FROM se WHERE id = 3");
		assertEquals("spring", sel.rows().getFirst()[0]);
	}
}
