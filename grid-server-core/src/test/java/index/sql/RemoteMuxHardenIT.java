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
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.client.Connection;
import org.genfork.grid.sql.client.RemoteConnection;
import org.genfork.grid.sql.client.RemoteConnectionFactory;
import org.genfork.grid.sql.client.Result;
import org.genfork.grid.sql.client.TxContext;
import org.genfork.grid.sql.netty.SqlServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * N5 / N5b / N6: remote mux harden, timeouts, AUTH, SESSION_OPEN schema, EXPLAIN.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class RemoteMuxHardenIT {
	private SqlEngine engine;
	private SqlServer server;
	private RemoteConnectionFactory remoteFactory;
	private int port;

	@BeforeEach
	void setUp() throws Exception {
		engine = new SqlEngine(new TableCatalog(Files.createTempDirectory("mux-harden")), null, 4);
		port = 25610 + (int) (Math.abs(System.nanoTime()) % 300);
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
	void concurrentAutocommitOnPooledSessions() throws Exception {
		engine.execute("CREATE TABLE t (id INT PRIMARY KEY, v INT)");
		engine.execute("INSERT INTO t VALUES (1, 0)");
		server = new SqlServer("127.0.0.1", port, engine, "u", "p", 256);
		server.start();
		TimeUnit.MILLISECONDS.sleep(120);
		remoteFactory = new RemoteConnectionFactory("127.0.0.1", port, "u", "p", 256);
		final Connection c = remoteFactory.obtain().block();
		final List<Long> updates = Flux.range(0, 64)
				.flatMap(i -> c.createStatement("UPDATE t SET v = v + 1 WHERE id = 1")
						.execute()
						.flatMap(Result::getRowsUpdated))
				.collectList()
				.block(Duration.ofSeconds(30));
		assertEquals(64, updates.size());
		assertEquals(64, ((Number) engine.execute("SELECT v FROM t WHERE id = 1").rows().getFirst()[0]).intValue());
		c.close().block();
	}

	@Test
	void concurrentAutocommitIndependentInserts() throws Exception {
		engine.execute("CREATE TABLE t (id INT PRIMARY KEY, v INT)");
		server = new SqlServer("127.0.0.1", port, engine, "u", "p", 256);
		server.start();
		TimeUnit.MILLISECONDS.sleep(120);
		remoteFactory = new RemoteConnectionFactory("127.0.0.1", port, "u", "p", 256);
		final Connection c = remoteFactory.obtain().block();
		final int n = 48;
		Flux.range(1, n)
				.flatMap(i -> c.createStatement("INSERT INTO t (id, v) VALUES (" + i + ", " + i + ")")
						.execute()
						.flatMap(Result::getRowsUpdated))
				.then()
				.block(Duration.ofSeconds(30));
		SqlSelectAwait.awaitRowCount(engine, "SELECT id FROM t", n);
		c.close().block();
	}

	@Test
	void reconnectAfterChannelDeath() throws Exception {
		engine.execute("CREATE TABLE t (id INT PRIMARY KEY, v VARCHAR)");
		engine.execute("INSERT INTO t VALUES (1, 'a')");
		server = new SqlServer("127.0.0.1", port, engine, "u", "p", 8);
		server.start();
		TimeUnit.MILLISECONDS.sleep(120);
		remoteFactory = new RemoteConnectionFactory("127.0.0.1", port, "u", "p", 8, 2, "public", false);
		final RemoteConnection c1 = (RemoteConnection) remoteFactory.obtain().block();
		final String mid = c1.createStatement("SELECT v FROM t WHERE id = 1")
				.execute()
				.flatMap(r -> r.map((row, meta) -> String.valueOf(row.get(0))))
				.blockFirst();
		assertEquals("a", mid);
		c1.channel().close().syncUninterruptibly();
		TimeUnit.MILLISECONDS.sleep(50);
		// destroy drops liveCount; async min-pool refill may already open a replacement
		final RemoteConnection c2 = (RemoteConnection) remoteFactory.obtain().block(Duration.ofSeconds(5));
		assertTrue(c2.isOpen());
		assertTrue(c2 != c1);
		c2.createStatement("INSERT INTO t (id, v) VALUES (2, 'b')").execute().blockLast();
		assertEquals("b", engine.execute("SELECT v FROM t WHERE id = 2").rows().getFirst()[0]);
		c2.close().block();
	}

	@Test
	void maxTxContextsExhaustionAndParallelBegin() throws Exception {
		engine.execute("CREATE TABLE t (id INT PRIMARY KEY, v INT)");
		server = new SqlServer("127.0.0.1", port, engine, "u", "p", 2);
		server.start();
		TimeUnit.MILLISECONDS.sleep(120);
		remoteFactory = new RemoteConnectionFactory("127.0.0.1", port, "u", "p", 2);
		final Connection c = remoteFactory.obtain().block();
		final TxContext tx1 = c.begin().block();
		final TxContext tx2 = c.begin().block();
		assertTrue(tx1.prepareHandle() > 0L);
		assertTrue(tx2.prepareHandle() > 0L);
		assertTrue(tx1.prepareHandle() != tx2.prepareHandle());
		final Exception ex = assertThrows(Exception.class, () -> c.begin().block(Duration.ofSeconds(3)));
		assertTrue(ex.getMessage().contains("maxTxContexts") || (ex.getCause() != null
				&& ex.getCause().getMessage() != null
				&& ex.getCause().getMessage().contains("maxTxContexts")), ex.getMessage());

		final CountDownLatch started = new CountDownLatch(2);
		final AtomicInteger commits = new AtomicInteger();
		final AtomicReference<Throwable> err = new AtomicReference<>();
		final Thread t1 = Thread.ofVirtual().start(() -> {
			try {
				tx1.createStatement("INSERT INTO t VALUES (1, 1)").execute().blockLast();
				started.countDown();
				tx1.commit().block();
				commits.incrementAndGet();
			} catch (Throwable t) {
				err.set(t);
				started.countDown();
			}
		});
		final Thread t2 = Thread.ofVirtual().start(() -> {
			try {
				tx2.createStatement("INSERT INTO t VALUES (2, 2)").execute().blockLast();
				started.countDown();
				tx2.commit().block();
				commits.incrementAndGet();
			} catch (Throwable t) {
				err.set(t);
				started.countDown();
			}
		});
		assertTrue(started.await(5, TimeUnit.SECONDS));
		t1.join(5000);
		t2.join(5000);
		if (err.get() != null) {
			throw new AssertionError(err.get());
		}
		assertEquals(2, commits.get());
		c.close().block();
	}

	@Test
	void maxConnectionsWarmupIdleEqualsN() throws Exception {
		server = new SqlServer("127.0.0.1", port, engine, "u", "p", 8);
		server.start();
		TimeUnit.MILLISECONDS.sleep(120);
		remoteFactory = new RemoteConnectionFactory(
				"127.0.0.1", port, "u", "p", 8, 3, "public", true);
		remoteFactory.warmup().block(Duration.ofSeconds(10));
		assertEquals(3, remoteFactory.idleChannels());
		assertEquals(3, remoteFactory.activeChannels());
	}

	@Test
	void execTimeoutFires() throws Exception {
		server = new SqlServer("127.0.0.1", port, engine, "u", "p", 8);
		server.start();
		TimeUnit.MILLISECONDS.sleep(120);
		remoteFactory = new RemoteConnectionFactory(
				"127.0.0.1", port, "u", "p", 8, 1, "public", false, Duration.ZERO);
		final Connection setup = remoteFactory.obtain().block();
		setup.createStatement("CREATE TABLE t (id INT PRIMARY KEY)").execute().blockLast();
		setup.close().block();
		remoteFactory.dispose();

		remoteFactory = new RemoteConnectionFactory(
				"127.0.0.1", port, "u", "p", 8, 1, "public", false, Duration.ofMillis(80));
		final Connection c = remoteFactory.obtain().block();
		server.close();
		server = null;
		TimeUnit.MILLISECONDS.sleep(50);
		assertThrows(Exception.class, () ->
				c.createStatement("INSERT INTO t VALUES (1)").execute().blockLast(Duration.ofSeconds(3)));
		c.close().onErrorComplete().block();
	}

	@Test
	void authRejectsWrongPassword() throws Exception {
		server = new SqlServer("127.0.0.1", port, engine, "u", "secret", 8);
		server.start();
		TimeUnit.MILLISECONDS.sleep(120);
		remoteFactory = new RemoteConnectionFactory("127.0.0.1", port, "u", "wrong", 8);
		final Exception ex = assertThrows(Exception.class,
				() -> remoteFactory.obtain().block(Duration.ofSeconds(3)));
		assertTrue(ex.getMessage().toLowerCase().contains("auth")
				|| (ex.getCause() != null && String.valueOf(ex.getCause().getMessage()).toLowerCase().contains("auth")),
				String.valueOf(ex.getMessage()));
	}

	@Test
	void sessionOpenAppliesDefaultSchema() throws Exception {
		engine.execute("CREATE SCHEMA app");
		engine.execute("CREATE TABLE app.t (id INT PRIMARY KEY, v INT)");
		engine.execute("INSERT INTO app.t VALUES (1, 42)");
		server = new SqlServer("127.0.0.1", port, engine, "u", "p", 8);
		server.start();
		TimeUnit.MILLISECONDS.sleep(120);
		remoteFactory = new RemoteConnectionFactory(
				"127.0.0.1", port, "u", "p", 8, 1, "app", false);
		final Connection c = remoteFactory.obtain().block();
		final List<String> rows = c.createStatement("SELECT v FROM t WHERE id = 1")
				.execute()
				.flatMap(r -> r.map((row, meta) -> String.valueOf(row.get(0))))
				.collectList()
				.block();
		assertEquals(List.of("42"), rows);
		c.close().block();
	}

	@Test
	void explainShowsIndexAndJoinKinds() {
		engine.execute("CREATE TABLE a (id INT PRIMARY KEY, v INT)");
		engine.execute("CREATE TABLE b (id INT PRIMARY KEY, a_id INT)");
		final SqlResult pk = engine.execute("EXPLAIN SELECT v FROM a WHERE id = 1");
		assertEquals("INDEX", pk.rows().getFirst()[0]);
		final SqlResult join = engine.execute("EXPLAIN SELECT * FROM a JOIN b ON id = id");
		assertEquals("JOIN_PK", join.rows().getFirst()[0]);
	}
}