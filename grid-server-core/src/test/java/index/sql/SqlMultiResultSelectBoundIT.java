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
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.client.BatchStatement;
import org.genfork.grid.sql.client.Connection;
import org.genfork.grid.sql.client.RemoteConnectionFactory;
import org.genfork.grid.sql.client.Result;
import org.genfork.grid.sql.netty.SqlServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bound multi-result SELECT: one {@code BATCH_EXEC} → ordered {@link Result} stream.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
class SqlMultiResultSelectBoundIT {
	private SqlEngine engine;
	private SqlServer server;
	private RemoteConnectionFactory remoteFactory;
	private int port;

	@BeforeEach
	void setUp() throws Exception {
		engine = new SqlEngine(new TableCatalog(Files.createTempDirectory("multi-select-bound")), null, 4);
		port = 26210 + (int) (Math.abs(System.nanoTime()) % 300);
		engine.execute("CREATE TABLE msel (id INT PRIMARY KEY, v INT)");
		engine.execute("INSERT INTO msel VALUES (1, 10)");
		engine.execute("INSERT INTO msel VALUES (2, 20)");
		engine.execute("INSERT INTO msel VALUES (3, 30)");
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
	void remoteBoundSelectBatchOrdered() throws Exception {
		server = new SqlServer("127.0.0.1", port, engine, "u", "p", 32);
		server.start();
		TimeUnit.MILLISECONDS.sleep(120);
		remoteFactory = new RemoteConnectionFactory("127.0.0.1", port, "u", "p", 32);
		final Connection connection = remoteFactory.obtain().block();
		assertNotNull(connection);

		final List<Result> results = connection.executeBatchBound(List.of(
						new BatchStatement("SELECT id FROM msel WHERE id = ?", new Object[]{1}),
						new BatchStatement("SELECT v FROM msel WHERE id = ?", new Object[]{2}),
						new BatchStatement("SELECT v FROM msel WHERE id = ?", new Object[]{3})))
				.collectList()
				.block(Duration.ofSeconds(20));
		assertNotNull(results);
		assertEquals(3, results.size());
		assertTrue(results.get(0).isResultSet());
		assertTrue(results.get(1).isResultSet());
		assertTrue(results.get(2).isResultSet());
		assertEquals(List.of(1), mapInts(results.get(0)));
		assertEquals(List.of(20), mapInts(results.get(1)));
		assertEquals(List.of(30), mapInts(results.get(2)));

		connection.close().block();
	}

	private static List<Integer> mapInts(Result result) {
		return result.map((row, meta) -> ((Number) row.get(0)).intValue())
				.collectList()
				.block(Duration.ofSeconds(10));
	}
}
