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
import org.genfork.grid.sql.netty.SqlServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

import java.nio.file.Files;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan C wire streaming: FETCH window + CANCEL mid-stream.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class SqlWireStreamIT {
	private SqlEngine engine;
	private SqlServer server;
	private RemoteConnectionFactory remoteFactory;
	int port;

	@BeforeEach
	void setUp() throws Exception {
		engine = new SqlEngine(new TableCatalog(Files.createTempDirectory("wire-stream")), null, 4);
		port = 25710 + (int) (Math.abs(System.nanoTime()) % 300);
	}

	@AfterEach
	void tearDown() {
		if (remoteFactory != null) {
			remoteFactory.dispose();
			remoteFactory = null;
		}
		if (server != null) {
			server.close();
			server = null;
		}
	}

	@Test
	void selectStreamsAllRowsViaFetchWindows() throws Exception {
		final int n = 200;
		engine.execute("CREATE TABLE t (id INT PRIMARY KEY, v INT)");
		for (int i = 1; i <= n; i++) {
			engine.execute("INSERT INTO t (id, v) VALUES (" + i + ", " + (i * 10) + ")");
		}
		SqlSelectAwait.awaitRowCount(engine, "SELECT id FROM t", n);

		server = new SqlServer("127.0.0.1", port, engine, "u", "p", 32);
		server.start();
		TimeUnit.MILLISECONDS.sleep(120);
		remoteFactory = new RemoteConnectionFactory("127.0.0.1", port, "u", "p", 32);
		final Connection c = remoteFactory.obtain().block();
		assertNotNull(c);

		final List<Integer> ids = c.createStatement("SELECT id, v FROM t")
				.execute()
				.concatMap(r -> r.map((row, meta) -> ((Number) row.get(0)).intValue()))
				.collectList()
				.block(Duration.ofSeconds(30));

		assertNotNull(ids);
		assertEquals(n, ids.size(), "streamed rows");
		assertEquals(n, new HashSet<>(ids).size(), "unique ids");
		assertTrue(ids.contains(1));
		assertTrue(ids.contains(n));
		c.close().block();
	}

	@Test
	void cancelMidStreamDoesNotHang() throws Exception {
		final int n = 200;
		engine.execute("CREATE TABLE t (id INT PRIMARY KEY, v INT)");
		for (int i = 1; i <= n; i++) {
			engine.execute("INSERT INTO t (id, v) VALUES (" + i + ", " + i + ")");
		}
		SqlSelectAwait.awaitRowCount(engine, "SELECT id FROM t", n);

		server = new SqlServer("127.0.0.1", port, engine, "u", "p", 32);
		server.start();
		TimeUnit.MILLISECONDS.sleep(120);
		remoteFactory = new RemoteConnectionFactory("127.0.0.1", port, "u", "p", 32);
		final Connection c = remoteFactory.obtain().block();
		assertNotNull(c);

		final List<Integer> partial = c.createStatement("SELECT id FROM t")
				.execute()
				.concatMap(r -> r.map((row, meta) -> ((Number) row.get(0)).intValue()))
				.take(10)
				.collectList()
				.block(Duration.ofSeconds(15));

		assertNotNull(partial);
		assertEquals(10, partial.size());
		TimeUnit.MILLISECONDS.sleep(300);

		final List<Integer> again = c.createStatement("SELECT id FROM t WHERE id = 1")
				.execute()
				.concatMap(r -> r.map((row, meta) -> ((Number) row.get(0)).intValue()))
				.collectList()
				.block(Duration.ofSeconds(10));
		assertEquals(List.of(1), again);
		c.close().block();
	}

}
