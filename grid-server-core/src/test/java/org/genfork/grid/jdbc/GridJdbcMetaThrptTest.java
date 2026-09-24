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
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.concurrent.TimeUnit;

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.netty.SqlServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lightweight meta/JDBC thrpt stamp (calm host). Full JMH: opt-in elsewhere.
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
class GridJdbcMetaThrptTest {
	private static final int WARM_ITERS = 50;
	private static final int MEASURE_ITERS = 200;
	private static final long MIN_OPS_PER_SEC = 20L;

	private SqlEngine engine;
	private SqlServer server;
	private int port;
	private GridDataSource dataSource;

	@BeforeEach
	void setUp() throws Exception {
		engine = new SqlEngine(new TableCatalog(Files.createTempDirectory("jdbc-meta-thrpt")), null, 4);
		engine.execute("CREATE TABLE accounts (id INT PRIMARY KEY, balance INT)");
		port = freePort();
		server = new SqlServer("127.0.0.1", port, engine, "u", "p", 64);
		server.start();
		TimeUnit.MILLISECONDS.sleep(80);
		Class.forName("org.genfork.grid.jdbc.GridDriver");
		dataSource = new GridDataSource("jdbc:grid://u:p@127.0.0.1:" + port + "/public");
	}

	@AfterEach
	void tearDown() {
		if (dataSource != null) {
			dataSource.close();
		}
		if (server != null) {
			server.close();
		}
	}

	@Test
	void getTablesViaSharedDataSourceExceedsFloor() throws Exception {
		for (int i = 0; i < WARM_ITERS; i++) {
			probeOnce();
		}
		final long start = System.nanoTime();
		for (int i = 0; i < MEASURE_ITERS; i++) {
			probeOnce();
		}
		final long elapsedNs = System.nanoTime() - start;
		final double opsPerSec = MEASURE_ITERS * 1_000_000_000.0 / elapsedNs;
		assertTrue(opsPerSec >= MIN_OPS_PER_SEC,
				"meta thrpt too low: " + opsPerSec + " ops/s");
	}

	private void probeOnce() throws Exception {
		try (Connection c = dataSource.getConnection()) {
			final DatabaseMetaData md = c.getMetaData();
			try (ResultSet rs = md.getTables(null, "public", "%", new String[]{"TABLE"})) {
				while (rs.next()) {
					rs.getString("TABLE_NAME");
				}
			}
		}
	}

	private static int freePort() throws Exception {
		try (ServerSocket ss = new ServerSocket(0)) {
			ss.setReuseAddress(true);
			return ss.getLocalPort();
		}
	}
}
