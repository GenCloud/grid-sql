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
import org.genfork.grid.sql.client.Result;
import org.genfork.grid.sql.client.TxContext;
import org.genfork.grid.sql.netty.SqlServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BATCH_EXEC: N SQL / 1 RTT → N ordered Results (remote).
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class SqlBatchExecIT {
	private SqlEngine engine;
	private SqlServer server;
	private RemoteConnectionFactory remoteFactory;
	private int port;

	@BeforeEach
	void setUp() throws Exception {
		engine = new SqlEngine(new TableCatalog(Files.createTempDirectory("batch-exec")), null, 4);
		port = 25810 + (int) (Math.abs(System.nanoTime()) % 300);

		engine.execute("CREATE TABLE batch_t (id INT PRIMARY KEY, v INT)");
		engine.execute("INSERT INTO batch_t VALUES (1, 10)");
		engine.execute("INSERT INTO batch_t VALUES (2, 20)");
		engine.execute("INSERT INTO batch_t VALUES (3, 30)");
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
	void remoteSelectAndWriteBatch() throws Exception {
		server = new SqlServer("127.0.0.1", port, engine, "u", "p", 32);
		server.start();
		TimeUnit.MILLISECONDS.sleep(120);
		remoteFactory = new RemoteConnectionFactory("127.0.0.1", port, "u", "p", 32);
		final Connection c = remoteFactory.obtain().block();
		assertNotNull(c);

		final List<Result> selects = c.executeBatch(List.of(
						"SELECT id FROM batch_t WHERE id = 1",
						"SELECT id FROM batch_t WHERE id = 2"))
				.collectList()
				.block(Duration.ofSeconds(20));
		assertNotNull(selects);
		assertEquals(2, selects.size());
		assertEquals(List.of(1), mapInts(selects.get(0)));
		assertEquals(List.of(2), mapInts(selects.get(1)));

		final List<Long> writes = c.executeBatch(List.of(
						"INSERT INTO batch_t VALUES (30, 300)",
						"UPDATE batch_t SET v = 301 WHERE id = 30"))
				.concatMap(Result::getRowsUpdated)
				.collectList()
				.block(Duration.ofSeconds(20));
		assertEquals(List.of(1L, 1L), writes);

		final List<Result> mixed = c.executeBatch(List.of(
						"SELECT v FROM batch_t WHERE id = 30",
						"DELETE FROM batch_t WHERE id = 30"))
				.collectList()
				.block(Duration.ofSeconds(20));
		assertNotNull(mixed);
		assertEquals(2, mixed.size());
		assertEquals(List.of(301), mapInts(mixed.get(0)));
		assertEquals(1L, mixed.get(1).getRowsUpdated().block());

		final TxContext tx = c.begin().block();
		assertNotNull(tx);
		tx.executeBatch(List.of(
						"INSERT INTO batch_t VALUES (20, 1)",
						"UPDATE batch_t SET v = 99 WHERE id = 20",
						"SELECT v FROM batch_t WHERE id = 20"))
				.collectList()
				.block(Duration.ofSeconds(20));
		tx.commit().block();

		assertEquals(99, ((Number) engine.execute("SELECT v FROM batch_t WHERE id = 20")
				.rows().getFirst()[0]).intValue());

		c.close().block();
	}

	@Test
	void remoteFailFastAbortsRemainder() throws Exception {
		server = new SqlServer("127.0.0.1", port, engine, "u", "p", 32);
		server.start();
		TimeUnit.MILLISECONDS.sleep(120);
		remoteFactory = new RemoteConnectionFactory("127.0.0.1", port, "u", "p", 32);
		final Connection c = remoteFactory.obtain().block();
		assertNotNull(c);

		final List<Result> seen = new ArrayList<>();
		Throwable err = null;
		try {
			c.executeBatch(List.of(
							"INSERT INTO batch_t VALUES (40, 1)",
							"INSERT INTO batch_t VALUES (1, 999)",
							"INSERT INTO batch_t VALUES (41, 1)"))
					.doOnNext(seen::add)
					.collectList()
					.block(Duration.ofSeconds(20));
		} catch (Throwable t) {
			err = t;
		}

		assertNotNull(err);
		assertTrue(seen.size() <= 1, "fail-fast: at most first success before error");
		assertEquals(0, engine.execute("SELECT id FROM batch_t WHERE id = 41").rows().size());
		c.close().block();
	}

	private static List<Integer> mapInts(Result result) {
		return result.map((row, meta) -> ((Number) row.get(0)).intValue())
				.collectList()
				.block(Duration.ofSeconds(10));
	}
}
