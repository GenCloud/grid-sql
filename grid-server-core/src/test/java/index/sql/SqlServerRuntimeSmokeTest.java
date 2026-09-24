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

import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.SqlServerRuntime;
import org.genfork.grid.sql.client.RemoteConnectionFactory;
import org.genfork.grid.sql.client.Result;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Standalone SqlServerRuntime smoke (no Spring Boot): TCP listen + remote loopback.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public class SqlServerRuntimeSmokeTest {
	private static final int PORT = 25441;

	@Test
	void runtimeListenAndRemote() throws Exception {
		try (SqlServerRuntime runtime = SqlServerRuntime.builder()
				.dataDir(Files.createTempDirectory("grid-sql-runtime"))
				.shards(4)
				.listen(true)
				.bind("127.0.0.1", PORT)
				.auth(SqlServerRuntime.DEFAULT_AUTH_USER, SqlServerRuntime.DEFAULT_AUTH_PASSWORD)
				.build()
				.start()) {
			assertNotNull(runtime.tcpServer());
			assertNotNull(runtime.engine());

			runtime.engine().execute("CREATE TABLE rt (id INT PRIMARY KEY, v VARCHAR)");
			runtime.engine().execute("INSERT INTO rt (id, v) VALUES (1, 'tcp')");

			TimeUnit.MILLISECONDS.sleep(150);
			final RemoteConnectionFactory remote = new RemoteConnectionFactory(
					"127.0.0.1",
					PORT,
					SqlServerRuntime.DEFAULT_AUTH_USER,
					SqlServerRuntime.DEFAULT_AUTH_PASSWORD);
			try {
				final String v = remote.obtain()
						.flatMapMany(conn -> conn.createStatement("SELECT v FROM rt WHERE id = 1").execute()
								.flatMap(r -> r.map((row, meta) -> String.valueOf(row.get(0)))))
						.blockFirst();
				assertEquals("tcp", v);
			} finally {
				remote.dispose();
			}

			final SqlResult sel = runtime.engine().execute("SELECT v FROM rt WHERE id = 1");
			assertEquals("tcp", sel.rows().getFirst()[0]);
		}
	}
}
