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
import org.genfork.grid.sql.client.ConnectionOptions;
import org.genfork.grid.sql.client.HostEndpoint;
import org.genfork.grid.sql.client.RemoteConnection;
import org.genfork.grid.sql.client.RemoteConnectionFactory;
import org.genfork.grid.sql.netty.SqlServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Idle pool: warmup / first obtain fills {@code minConnections}; further obtain grows to max.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class RemoteConnectionPoolIT {
	private static final int MIN = 2;
	private static final int MAX = 4;

	private SqlEngine engine;
	private SqlServer server;
	private RemoteConnectionFactory factory;
	private int port;

	@BeforeEach
	void setUp() throws Exception {
		engine = new SqlEngine(new TableCatalog(Files.createTempDirectory("pool-it")), null, 4);
		port = 25910 + (int) (Math.abs(System.nanoTime()) % 300);
		server = new SqlServer("127.0.0.1", port, engine, "u", "p", 32);
		server.start();
		TimeUnit.MILLISECONDS.sleep(120);
		final ConnectionOptions opts = ConnectionOptions.builder()
				.minConnections(MIN)
				.maxConnections(MAX)
				.warmup(true)
				.maxTxContexts(32)
				.build();
		factory = new RemoteConnectionFactory(
				List.of(new HostEndpoint("127.0.0.1", port)), "u", "p", opts, "public");
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
	void warmupFillsMinIntoIdle() {
		factory.warmup().block();
		assertEquals(MIN, factory.activeChannels());
		assertEquals(MIN, factory.idleChannels());
		assertEquals(MIN, factory.minConnections());
		assertEquals(MAX, factory.maxConnections());
	}

	@Test
	void firstObtainFillsMinWithoutPriorWarmup() {
		final Connection c = factory.obtain().block();
		assertTrue(c instanceof RemoteConnection);
		assertTrue(((RemoteConnection) c).isOpen());
		assertEquals(MIN, factory.activeChannels());
		assertEquals(MIN - 1, factory.idleChannels());
		c.close().block();
	}

	@Test
	void lazyGrowUpToMax() {
		factory.warmup().block();
		final Connection c1 = factory.obtain().block();
		final Connection c2 = factory.obtain().block();
		final Connection c3 = factory.obtain().block();
		final Connection c4 = factory.obtain().block();
		assertEquals(MAX, factory.activeChannels());
		assertTrue(factory.idleChannels() <= 0);
		c1.close().block();
		c2.close().block();
		c3.close().block();
		c4.close().block();
	}
}