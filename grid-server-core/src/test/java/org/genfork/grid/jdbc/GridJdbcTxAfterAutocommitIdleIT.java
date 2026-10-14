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

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.netty.SqlServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spring-style TX begin must reuse parked autocommit idle sessions under the
 * SQL server channel hard-cap ({@code maxTxContexts=8}).
 * <p>
 * Without reclaim, eight autocommit queries fill the channel; {@code setAutoCommit(false)}
 * then fails with {@code maxTxContexts=8 exhausted} (EventScheduler / JDBC TX path).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
class GridJdbcTxAfterAutocommitIdleIT {
	private static final int SERVER_CHANNEL_TX_CAP = SqlServer.DEFAULT_MAX_TX_CONTEXTS;
	private static final String USER = "u";
	private static final String PASS = "p";

	private SqlEngine engine;
	private SqlServer server;
	private int port;

	@BeforeEach
	void setUp() throws Exception {
		engine = new SqlEngine(new TableCatalog(Files.createTempDirectory("jdbc-tx-idle-it")), null, 4);
		engine.execute("CREATE TABLE accounts (id INT PRIMARY KEY, balance INT)");
		engine.execute("INSERT INTO accounts (id, balance) VALUES (1, 100)");
		port = freePort();
		server = new SqlServer("127.0.0.1", port, engine, USER, PASS, SERVER_CHANNEL_TX_CAP);
		server.start();
		TimeUnit.MILLISECONDS.sleep(120);
		Class.forName("org.genfork.grid.jdbc.GridDriver");
	}

	@AfterEach
	void tearDown() {
		if (server != null) {
			server.close();
			server = null;
		}
	}

	@Test
	void setAutoCommitFalseReusesIdleAfterChannelCapFilled() throws Exception {
		final String url = "jdbc:grid://" + USER + ":" + PASS + "@127.0.0.1:" + port
				+ "/public?minConnections=10&maxConnections=10&maxTxContexts=8";
		try (GridDataSource ds = new GridDataSource(url);
			 Connection c = ds.getConnection()) {
			for (int i = 0; i < SERVER_CHANNEL_TX_CAP; i++) {
				try (Statement st = c.createStatement();
					 ResultSet rs = st.executeQuery("SELECT balance FROM accounts WHERE id = 1")) {
					assertTrue(rs.next());
					assertEquals(100, rs.getInt(1));
				}
			}
			assertDoesNotThrow(() -> c.setAutoCommit(false));
			try (Statement st = c.createStatement()) {
				assertEquals(1, st.executeUpdate("UPDATE accounts SET balance = 101 WHERE id = 1"));
			}
			c.commit();
		}
	}

	private static int freePort() throws Exception {
		try (ServerSocket ss = new ServerSocket(0)) {
			ss.setReuseAddress(true);
			return ss.getLocalPort();
		}
	}
}
