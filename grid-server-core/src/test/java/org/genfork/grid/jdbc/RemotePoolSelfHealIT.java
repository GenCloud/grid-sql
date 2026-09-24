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
package org.genfork.grid.jdbc;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.client.ConnectionOptions;
import org.genfork.grid.sql.client.HostEndpoint;
import org.genfork.grid.sql.client.RemoteConnection;
import org.genfork.grid.sql.client.RemoteConnectionFactory;
import org.genfork.grid.sql.netty.SqlServer;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TCP min-pool refills after mass channel death (no JDBC traffic required).
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
class RemotePoolSelfHealIT {
	private static final int MIN = 4;
	private static final int MAX = 4;
	private static final long REFILL_WAIT_MS = 15_000L;
	private static final long POLL_MS = 100L;

	private SqlEngine engine;
	private SqlServer server;
	private int port;
	private RemoteConnectionFactory factory;

	@BeforeEach
	void setUp() throws Exception {
		engine = new SqlEngine(new TableCatalog(Files.createTempDirectory("pool-heal")), null, 4);
		port = freePort();
		server = new SqlServer("127.0.0.1", port, engine, "u", "p", 32);
		server.start();
		TimeUnit.MILLISECONDS.sleep(120);
		final ConnectionOptions options = ConnectionOptions.builder()
				.minConnections(MIN)
				.maxConnections(MAX)
				.warmup(true)
				.build();
		factory = new RemoteConnectionFactory(
				List.of(new HostEndpoint("127.0.0.1", port)),
				"u",
				"p",
				options,
				ConnectionOptions.DEFAULT_SCHEMA);
		factory.warmup().block();
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
	void refillsToMinAfterMassDisconnect() throws Exception {
		assertTrue(factory.activeChannels() >= MIN);
		final List<RemoteConnection> snapshot = new ArrayList<>();
		for (int i = 0; i < MIN; i++) {
			final org.genfork.grid.sql.client.Connection c = factory.obtain().block();
			assertTrue(c instanceof RemoteConnection);
			snapshot.add((RemoteConnection) c);
		}
		for (RemoteConnection c : snapshot) {
			c.channel().close().syncUninterruptibly();
		}
		final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(REFILL_WAIT_MS);
		while (System.nanoTime() < deadline) {
			if (factory.activeChannels() >= MIN) {
				return;
			}
			TimeUnit.MILLISECONDS.sleep(POLL_MS);
		}
		assertTrue(factory.activeChannels() >= MIN,
				"expected refill to minConnections, live=" + factory.activeChannels());
	}

	private static int freePort() throws Exception {
		try (ServerSocket ss = new ServerSocket(0)) {
			ss.setReuseAddress(true);
			return ss.getLocalPort();
		}
	}
}