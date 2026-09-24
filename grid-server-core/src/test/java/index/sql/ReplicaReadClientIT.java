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
import org.genfork.grid.sql.client.Connection;
import org.genfork.grid.sql.client.RemoteConnectionFactory;
import org.genfork.grid.sql.client.SessionRole;
import org.genfork.grid.sql.netty.SqlServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * READ_REPLICA client pool: SELECT OK on solo; begin rejected; DML rejected by server.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public class ReplicaReadClientIT {
	private static final String USER = "u";
	private static final String PASS = "p";
	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private SqlEngine engine;
	private SqlServer server;
	private int port;
	private RemoteConnectionFactory readFactory;
	private RemoteConnectionFactory primaryFactory;

	@BeforeEach
	void setUp() throws Exception {
		engine = new SqlEngine(new TableCatalog(Files.createTempDirectory("replica-read-it")), null, 4);
		engine.execute("CREATE TABLE rr (id INT PRIMARY KEY, v VARCHAR)");
		engine.execute("INSERT INTO rr (id, v) VALUES (1, 'ok')");
		port = 26100 + (int) (Math.abs(System.nanoTime()) % 400);
		server = new SqlServer("127.0.0.1", port, engine, USER, PASS, 32);
		server.start();
		TimeUnit.MILLISECONDS.sleep(120);
	}

	@AfterEach
	void tearDown() {
		if (readFactory != null) {
			readFactory.dispose();
			readFactory = null;
		}
		if (primaryFactory != null) {
			primaryFactory.dispose();
			primaryFactory = null;
		}
		if (server != null) {
			server.close();
			server = null;
		}
	}

	@Test
	void readReplicaSelectOkAndBeginRejected() {
		final String url = "grid://" + USER + ":" + PASS + "@127.0.0.1:" + port
				+ "/public?readEndpoints=127.0.0.1:" + port;
		readFactory = RemoteConnectionFactory.createReadFactory(url);
		assertEquals(SessionRole.READ_REPLICA, readFactory.factoryRole());
		final String v = readFactory.obtain()
				.flatMapMany(conn -> conn.createStatement("SELECT v FROM rr WHERE id = 1").execute()
						.flatMap(r -> r.map((row, meta) -> String.valueOf(row.get(0)))))
				.blockFirst(TIMEOUT);
		assertEquals("ok", v);

		final Connection conn = readFactory.obtain().block(TIMEOUT);
		assertThrows(Exception.class, () -> conn.begin().block(TIMEOUT));
	}

	@Test
	void readReplicaInsertDenied() {
		final String url = "grid://" + USER + ":" + PASS + "@127.0.0.1:" + port
				+ "/public?readEndpoints=127.0.0.1:" + port;
		readFactory = RemoteConnectionFactory.createReadFactory(url);
		final Throwable err = assertThrows(Throwable.class, () ->
				readFactory.obtain()
						.flatMapMany(conn -> conn.createStatement(
								"INSERT INTO rr (id, v) VALUES (2, 'x')").execute()
								.flatMap(r -> r.getRowsUpdated()))
						.blockLast(TIMEOUT));
		assertTrue(err.getMessage() != null && err.getMessage().toLowerCase().contains("read_replica")
				|| err.getCause() != null);
	}

	@Test
	void primarySelectStillWorks() {
		final String url = "grid://" + USER + ":" + PASS + "@127.0.0.1:" + port + "/public";
		primaryFactory = RemoteConnectionFactory.fromUrl(url);
		assertEquals(SessionRole.PRIMARY, primaryFactory.factoryRole());
		final String v = primaryFactory.obtain()
				.flatMapMany(conn -> conn.createStatement("SELECT v FROM rr WHERE id = 1").execute()
						.flatMap(r -> r.map((row, meta) -> String.valueOf(row.get(0)))))
				.blockFirst(TIMEOUT);
		assertEquals("ok", v);
	}
}