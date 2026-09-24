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
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Autocommit session reuse must not share dirty/locks with parallel begin() TxContexts.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class AutocommitSessionReuseIT {
	private SqlEngine engine;
	private SqlServer server;
	private RemoteConnectionFactory factory;
	private int port;

	@BeforeEach
	void setUp() throws Exception {
		engine = new SqlEngine(new TableCatalog(Files.createTempDirectory("ac-reuse")), null, 4);
		port = 25820 + (int) (Math.abs(System.nanoTime()) % 300);
		server = new SqlServer("127.0.0.1", port, engine, "u", "p", 32);
		server.start();
		TimeUnit.MILLISECONDS.sleep(120);
		factory = new RemoteConnectionFactory("127.0.0.1", port, "u", "p", 32);
		engine.execute("CREATE TABLE ac_t (id INT PRIMARY KEY, v INT)");
		engine.execute("INSERT INTO ac_t VALUES (1, 10)");
	}

	@AfterEach
	void tearDown() {
		if (factory != null) {
			factory.dispose();
			factory = null;
		}
		if (server != null) {
			server.close();
			server = null;
		}
	}

	@Test
	void concurrentAutocommitAndParallelBeginDoNotCrossDirty() throws Exception {
		final Connection conn = factory.obtain().block();
		final CountDownLatch started = new CountDownLatch(1);
		final CountDownLatch allowCommit = new CountDownLatch(1);
		final AtomicReference<Throwable> txErr = new AtomicReference<>();

		final Thread txThread = Thread.ofVirtual().start(() -> {
			try {
				final TxContext tx = conn.begin().block();
				tx.createStatement("UPDATE ac_t SET v = 99 WHERE id = 1")
						.execute()
						.flatMap(Result::getRowsUpdated)
						.blockLast();
				started.countDown();
				assertTrue(allowCommit.await(5, TimeUnit.SECONDS));
				tx.commit().block();
			} catch (Throwable t) {
				txErr.set(t);
				started.countDown();
			}
		});

		assertTrue(started.await(5, TimeUnit.SECONDS));

		final List<String> mid = conn.createStatement("SELECT v FROM ac_t WHERE id = 1")
				.execute()
				.flatMap(r -> r.map((row, meta) -> String.valueOf(row.get(0))))
				.collectList()
				.block();
		assertEquals(1, mid.size());
		assertEquals("10", mid.get(0), "autocommit must not see open TX dirty");

		allowCommit.countDown();
		txThread.join(TimeUnit.SECONDS.toMillis(5));
		if (txErr.get() != null) {
			throw new AssertionError(txErr.get());
		}

		final List<String> after = conn.createStatement("SELECT v FROM ac_t WHERE id = 1")
				.execute()
				.flatMap(r -> r.map((row, meta) -> String.valueOf(row.get(0))))
				.collectList()
				.block();
		assertEquals("99", after.get(0));

		final List<Long> updates = Flux.range(0, 32)
				.flatMap(i -> conn.createStatement("UPDATE ac_t SET v = v + 1 WHERE id = 1")
						.execute()
						.flatMap(Result::getRowsUpdated))
				.collectList()
				.block();
		assertEquals(32, updates.size());

		conn.close().block();
	}
}
