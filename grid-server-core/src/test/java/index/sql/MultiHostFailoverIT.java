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
import org.genfork.grid.sql.client.RemoteConnectionFactory;
import org.genfork.grid.sql.netty.SqlServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Multi-host grid:// sticky failover: first port down, second live.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public class MultiHostFailoverIT {
	private static final String USER = "u";
	private static final String PASS = "p";
	private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(800);

	private SqlEngine engine;
	private SqlServer server;
	private int livePort;
	private int deadPort;
	private RemoteConnectionFactory factory;

	@BeforeEach
	void setUp() throws Exception {
		engine = new SqlEngine(new TableCatalog(Files.createTempDirectory("multihost-it")), null, 4);
		engine.execute("CREATE TABLE mh (id INT PRIMARY KEY, v VARCHAR)");
		engine.execute("INSERT INTO mh (id, v) VALUES (1, 'ok')");
		livePort = 25910 + (int) (Math.abs(System.nanoTime()) % 400);
		deadPort = livePort + 1;
		server = new SqlServer("127.0.0.1", livePort, engine, USER, PASS, 32);
		server.start();
		TimeUnit.MILLISECONDS.sleep(120);
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
	void failoverWhenFirstHostDown() {
		final String url = "grid://" + USER + ":" + PASS + "@127.0.0.1:" + deadPort
				+ ",127.0.0.1:" + livePort + "/public?connectTimeoutMs=" + CONNECT_TIMEOUT.toMillis();
		factory = RemoteConnectionFactory.fromUrl(url);
		final String v = factory.obtain()
				.flatMapMany(conn -> conn.createStatement("SELECT v FROM mh WHERE id = 1").execute()
						.flatMap(r -> r.map((row, meta) -> String.valueOf(row.get(0)))))
				.blockFirst(Duration.ofSeconds(10));
		assertEquals("ok", v);
		assertEquals(1, factory.stickyIndex());
		assertTrue(factory.activeChannels() >= 1);
	}
}