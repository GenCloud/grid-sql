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
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Multiplex {@link GridDataSource} + Hikari bridge fail-closed IT.
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
class GridDataSourceIT {
	private SqlEngine engine;
	private SqlServer server;
	private int port;

	@BeforeEach
	void setUp() throws Exception {
		engine = new SqlEngine(new TableCatalog(Files.createTempDirectory("jdbc-ds-it")), null, 4);
		engine.execute("CREATE TABLE accounts (id INT PRIMARY KEY, balance INT)");
		engine.execute("INSERT INTO accounts (id, balance) VALUES (1, 100)");
		port = freePort();
		server = new SqlServer("127.0.0.1", port, engine, "u", "p", 64);
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
	void warmsAtLeastMinTcpChannelsAndReturnsConnections() throws Exception {
		final String url = "jdbc:grid://u:p@127.0.0.1:" + port + "/public";
		try (GridDataSource ds = new GridDataSource(url)) {
			assertTrue(ds.tcpChannelFloor() >= GridDataSource.MIN_TCP_CHANNELS);
			assertTrue(ds.syncFactory().activeChannels() >= GridDataSource.MIN_TCP_CHANNELS);
			assertEquals(GridDataSource.MIN_TCP_CHANNELS, ds.syncFactory().maxConnections());

			final List<Connection> held = new ArrayList<>();
			try {
				for (int i = 0; i < GridDataSource.MIN_TCP_CHANNELS; i++) {
					held.add(ds.getConnection());
				}
				try (Connection c = ds.getConnection();
					 Statement st = c.createStatement();
					 ResultSet rs = st.executeQuery("SELECT balance FROM accounts WHERE id = 1")) {
					assertTrue(rs.next());
					assertEquals(100, rs.getInt(1));
				}
			} finally {
				for (Connection c : held) {
					c.close();
				}
			}
		}
	}

	@Test
	void driverAndDataSourceShareSameSyncFactory() throws Exception {
		final String url = "jdbc:grid://u:p@127.0.0.1:" + port + "/public";
		try (GridDataSource ds = new GridDataSource(url);
			 Connection fromDs = ds.getConnection();
			 Connection fromDriver = DriverManager.getConnection(url)) {
			final GridConnection gcDs = fromDs.unwrap(GridConnection.class);
			final GridConnection gcDrv = fromDriver.unwrap(GridConnection.class);
			assertSame(ds.syncFactory(), gcDs.syncFactory());
			assertSame(ds.syncFactory(), gcDrv.syncFactory());
		}
	}


	@Test
	void driverRetainSurvivesDataSourceClose() throws Exception {
		final String url = "jdbc:grid://u:p@127.0.0.1:" + port + "/public";
		final Connection fromDriver = DriverManager.getConnection(url);
		try {
			final GridDataSource ds = new GridDataSource(url);
			assertSame(ds.syncFactory(), fromDriver.unwrap(GridConnection.class).syncFactory());
			ds.close();
			assertTrue(fromDriver.isValid(3));
			try (Statement st = fromDriver.createStatement();
				 ResultSet rs = st.executeQuery("SELECT balance FROM accounts WHERE id = 1")) {
				assertTrue(rs.next());
				assertEquals(100, rs.getInt(1));
			}
		} finally {
			fromDriver.close();
		}
	}

	@Test
	void connectionCloseParksNotKillsChannel() throws Exception {
		final String url = "jdbc:grid://u:p@127.0.0.1:" + port + "/public";
		try (GridDataSource ds = new GridDataSource(url)) {
			final org.genfork.grid.sql.client.RemoteConnection remote;
			try (Connection c = ds.getConnection()) {
				final GridConnection gc = c.unwrap(GridConnection.class);
				remote = gc.spi();
				assertTrue(remote.isOpen());
			}
			assertTrue(remote.isOpen(), "park must leave channel open while DataSource retains factory");
		}
	}
	private static int freePort() throws Exception {
		try (ServerSocket ss = new ServerSocket(0)) {
			ss.setReuseAddress(true);
			return ss.getLocalPort();
		}
	}
}
