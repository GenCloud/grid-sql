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

import java.net.ServerSocket;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.client.ConnectionOptions;
import org.genfork.grid.sql.client.RemoteConnectionFactory;
import org.genfork.grid.sql.client.ServerMeta;
import org.genfork.grid.sql.client.SessionRole;
import org.genfork.grid.sql.client.StaleReadPolicy;
import org.genfork.grid.sql.netty.SqlServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1 primary + N replicas: {@code readEndpoints} FAIL_CLOSED rotate on {@code applyLagStale}
 * via {@link org.genfork.grid.sql.client.ReadEndpointSelector}.
 * <p>
 * Lightweight multi-{@link SqlServer} (no full Netty HA): lag forced via
 * {@link SqlServer#overrideApplyLagStale(Boolean)}.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public class ReplicaReadEndpointsRotateIT {
	private static final String USER = "u";
	private static final String PASS = "p";
	private static final String BIND_HOST = "127.0.0.1";
	private static final int MAX_TX_CONTEXTS = 32;
	private static final int TEST_SHARDS = 4;
	private static final long BOOT_SLEEP_MS = 120L;
	private static final Duration TIMEOUT = Duration.ofSeconds(10);
	private static final String DDL = "CREATE TABLE rr_n (id INT PRIMARY KEY, v VARCHAR)";
	private static final String SEED = "INSERT INTO rr_n (id, v) VALUES (1, 'ok')";
	private static final String SELECT = "SELECT v FROM rr_n WHERE id = 1";
	private static final String EXPECTED_VALUE = "ok";
	private static final String STALE_ERR_SNIPPET = "apply lag stale";

	private SqlEngine primaryEngine;
	private SqlEngine replica0Engine;
	private SqlEngine replica1Engine;
	private SqlServer primary;
	private SqlServer replica0;
	private SqlServer replica1;
	private int primaryPort;
	private int replica0Port;
	private int replica1Port;
	private RemoteConnectionFactory readFactory;

	@BeforeEach
	void setUp() throws Exception {
		primaryPort = freePort();
		replica0Port = freePort();
		replica1Port = freePort();

		primaryEngine = newEngine("rr-n-primary");
		replica0Engine = newEngine("rr-n-r0");
		replica1Engine = newEngine("rr-n-r1");

		primary = startServer(primaryEngine, primaryPort);
		replica0 = startServer(replica0Engine, replica0Port);
		replica1 = startServer(replica1Engine, replica1Port);
		TimeUnit.MILLISECONDS.sleep(BOOT_SLEEP_MS);
	}

	@AfterEach
	void tearDown() {
		if (readFactory != null) {
			readFactory.dispose();
			readFactory = null;
		}
		closeQuietly(replica0);
		closeQuietly(replica1);
		closeQuietly(primary);
		replica0 = null;
		replica1 = null;
		primary = null;
	}

	@Test
	void rotatePastApplyLagStaleToFreshReplica() {
		replica0.overrideApplyLagStale(Boolean.TRUE);
		replica1.overrideApplyLagStale(null);

		final String url = readUrl(replica0Port, replica1Port);
		readFactory = RemoteConnectionFactory.createReadFactory(url);
		assertEquals(SessionRole.READ_REPLICA, readFactory.factoryRole());
		assertEquals(StaleReadPolicy.FAIL_CLOSED, readFactory.options().staleReadPolicy());
		assertEquals(2, readFactory.endpoints().size());

		final String v = readFactory.obtain()
				.flatMapMany(conn -> conn.createStatement(SELECT).execute()
						.flatMap(r -> r.map((row, meta) -> String.valueOf(row.get(0)))))
				.blockFirst(TIMEOUT);
		assertEquals(EXPECTED_VALUE, v);
		assertEquals(1, readFactory.stickyIndex(), "should land on fresh replica1 after rotate");
		final ServerMeta meta = readFactory.lastServerMeta();
		assertFalse(meta.applyLagStale());
	}

	@Test
	void failClosedWhenAllReadEndpointsStale() {
		replica0.overrideApplyLagStale(Boolean.TRUE);
		replica1.overrideApplyLagStale(Boolean.TRUE);

		final String url = readUrl(replica0Port, replica1Port);
		readFactory = RemoteConnectionFactory.createReadFactory(url);
		assertEquals(StaleReadPolicy.FAIL_CLOSED, readFactory.options().staleReadPolicy());

		final Throwable err = assertThrows(Throwable.class, () ->
				readFactory.obtain().block(TIMEOUT));
		assertTrue(
				messageChainContains(err, STALE_ERR_SNIPPET),
				"expected FAIL_CLOSED apply-lag stale, got: " + err);
	}

	@Test
	void primaryWriteUrlUnaffectedByReadEndpoints() {
		final String url = "grid://" + USER + ":" + PASS + "@" + BIND_HOST + ":" + primaryPort
				+ "/public?readEndpoints=" + BIND_HOST + ":" + replica0Port
				+ "," + BIND_HOST + ":" + replica1Port;
		final RemoteConnectionFactory writer = RemoteConnectionFactory.fromUrl(url);
		try {
			assertEquals(SessionRole.PRIMARY, writer.factoryRole());
			assertEquals(ConnectionOptions.DEFAULT_STALE_READ_POLICY, writer.options().staleReadPolicy());
			final String v = writer.obtain()
					.flatMapMany(conn -> conn.createStatement(SELECT).execute()
							.flatMap(r -> r.map((row, meta) -> String.valueOf(row.get(0)))))
					.blockFirst(TIMEOUT);
			assertEquals(EXPECTED_VALUE, v);
		} finally {
			writer.dispose();
		}
	}

	private String readUrl(int ep0, int ep1) {
		return "grid://" + USER + ":" + PASS + "@" + BIND_HOST + ":" + primaryPort
				+ "/public?readEndpoints=" + BIND_HOST + ":" + ep0
				+ "," + BIND_HOST + ":" + ep1
				+ "&readPreference=REPLICA&staleReadPolicy=FAIL_CLOSED";
	}

	private static SqlEngine newEngine(String dirPrefix) throws Exception {
		final SqlEngine engine = new SqlEngine(
				new TableCatalog(Files.createTempDirectory(dirPrefix)), null, TEST_SHARDS);
		engine.execute(DDL);
		engine.execute(SEED);
		return engine;
	}

	private static SqlServer startServer(SqlEngine engine, int port) {
		final SqlServer server = new SqlServer(BIND_HOST, port, engine, USER, PASS, MAX_TX_CONTEXTS);
		server.start();
		return server;
	}

	private static int freePort() throws Exception {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	private static void closeQuietly(SqlServer server) {
		if (server != null) {
			server.close();
		}
	}

	private static boolean messageChainContains(Throwable err, String snippet) {
		Throwable cur = err;
		final String needle = snippet.toLowerCase();
		while (cur != null) {
			final String msg = cur.getMessage();
			if (msg != null && msg.toLowerCase().contains(needle)) {
				return true;
			}
			cur = cur.getCause();
		}
		return false;
	}
}
