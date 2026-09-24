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

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.sql.client.RemoteConnection;
import org.genfork.grid.sql.netty.SqlServer;
import org.genfork.grid.sql.SqlEngine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JDBC DriverManager IT against {@link SqlServer}.
 * <p>
 * Hosted in {@code grid-server-core} (not {@code grid-sql-client}) to avoid a Maven reactor
 * cycle: client test → server → client. Driver under test remains {@link GridDriver} in client.
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
class GridJdbcIT {
	private SqlEngine engine;
	private SqlServer server;
	private int port;

	@BeforeEach
	void setUp() throws Exception {
		engine = new SqlEngine(new TableCatalog(Files.createTempDirectory("jdbc-it")), null, 4);
		engine.execute("CREATE TABLE accounts (id INT PRIMARY KEY, balance INT)");
		engine.execute("INSERT INTO accounts (id, balance) VALUES (1, 100)");
		port = freePort();
		server = new SqlServer("127.0.0.1", port, engine, "u", "p", 32);
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
	void connectQueryMetadataAndTx() throws Exception {
		final String url = "jdbc:grid://u:p@127.0.0.1:" + port + "/public";
		try (Connection c = DriverManager.getConnection(url)) {
			assertTrue(c.isValid(5));
			assertEquals("public", c.getSchema());

			final DatabaseMetaData md = c.getMetaData();
			boolean foundTable = false;
			try (ResultSet tables = md.getTables(null, "public", "%", new String[]{"TABLE"})) {
				while (tables.next()) {
					if ("accounts".equalsIgnoreCase(tables.getString("TABLE_NAME"))) {
						foundTable = true;
					}
				}
			}
			assertTrue(foundTable);

			boolean foundCol = false;
			try (ResultSet cols = md.getColumns(null, "public", "accounts", "%")) {
				while (cols.next()) {
					if ("balance".equalsIgnoreCase(cols.getString("COLUMN_NAME"))) {
						foundCol = true;
					}
				}
			}
			assertTrue(foundCol);

			try (Statement st = c.createStatement();
				 ResultSet rs = st.executeQuery("SELECT balance FROM accounts WHERE id = 1")) {
				assertTrue(rs.next());
				assertEquals(100, rs.getInt(1));
			}

			c.setAutoCommit(false);
			try (PreparedStatement ps = c.prepareStatement(
					"UPDATE accounts SET balance = ? WHERE id = ?")) {
				ps.setInt(1, 150);
				ps.setInt(2, 1);
				assertEquals(1, ps.executeUpdate());
			}
			c.commit();

			try (Statement st = c.createStatement();
				 ResultSet rs = st.executeQuery("SELECT balance FROM accounts WHERE id = 1")) {
				assertTrue(rs.next());
				assertEquals(150, rs.getInt(1));
			}
		}
	}

	/**
	 * Mimics DBeaver navigator: isValid → schemas → tables → columns on one Connection,
	 * plus a second Connection while the first is still open.
	 */
	@Test
	void dbeaverExpandTableListSequence() throws Exception {
		final String url = "jdbc:grid://u:p@127.0.0.1:" + port + "/public";
		try (Connection c1 = DriverManager.getConnection(url)) {
			assertTrue(c1.isValid(5));
			final DatabaseMetaData md = c1.getMetaData();

			boolean foundSchema = false;
			try (ResultSet schemas = md.getSchemas()) {
				while (schemas.next()) {
					if ("public".equalsIgnoreCase(schemas.getString(1))) {
						foundSchema = true;
					}
				}
			}
			assertTrue(foundSchema);
			assertTrue(findAccountsTable(md));

			boolean foundCol = false;
			try (ResultSet cols = md.getColumns(null, "public", "accounts", "%")) {
				while (cols.next()) {
					if ("id".equalsIgnoreCase(cols.getString("COLUMN_NAME"))) {
						foundCol = true;
					}
				}
			}
			assertTrue(foundCol);

			try (Connection c2 = DriverManager.getConnection(url)) {
				assertTrue(c2.isValid(5));
				assertTrue(findAccountsTable(c2.getMetaData()));
			}
			assertTrue(c1.isValid(5));
			assertTrue(findAccountsTable(c1.getMetaData()));
		}
	}

	@Test
	void urlPathSchemaExposedViaGetSchema() throws Exception {
		final String schemaName = "url_path_schema";
		final String url = "jdbc:grid://u:p@127.0.0.1:" + port + "/" + schemaName;
		try (Connection c = DriverManager.getConnection(url)) {
			assertEquals(schemaName, c.getSchema());
		}
	}

	@Test
	void schemaLifecycleCreateDropAndUserName() throws Exception {
		final String url = "jdbc:grid://u:p@127.0.0.1:" + port + "/public";
		try (Connection c = DriverManager.getConnection(url)) {
			assertEquals("u", c.getMetaData().getUserName());
			try (Statement st = c.createStatement()) {
				st.execute("CREATE SCHEMA IF NOT EXISTS dbeaver_demo");
			}
			boolean found = false;
			try (ResultSet schemas = c.getMetaData().getSchemas()) {
				while (schemas.next()) {
					if ("dbeaver_demo".equalsIgnoreCase(schemas.getString(1))) {
						found = true;
					}
				}
			}
			assertTrue(found);
			try (Statement st = c.createStatement()) {
				st.execute("DROP SCHEMA dbeaver_demo");
			}
			found = false;
			try (ResultSet schemas = c.getMetaData().getSchemas()) {
				while (schemas.next()) {
					if ("dbeaver_demo".equalsIgnoreCase(schemas.getString(1))) {
						found = true;
					}
				}
			}
			assertFalse(found);
		}
	}

	@Test
	void emptyUserPasswordAuthWhenServerOpen() throws Exception {
		final SqlEngine openEngine = new SqlEngine(new TableCatalog(Files.createTempDirectory("jdbc-open")), null, 4);
		openEngine.execute("CREATE TABLE t (id INT PRIMARY KEY)");
		final int openPort = freePort();
		final SqlServer openServer = new SqlServer("127.0.0.1", openPort, openEngine, "", "", 32);
		openServer.start();
		TimeUnit.MILLISECONDS.sleep(120);
		try {
			final String url = "jdbc:grid://127.0.0.1:" + openPort + "/public";
			try (Connection c = DriverManager.getConnection(url)) {
				assertTrue(c.isValid(5));
				boolean found = false;
				try (ResultSet tables = c.getMetaData().getTables(null, null, "%", null)) {
					while (tables.next()) {
						if ("t".equalsIgnoreCase(tables.getString("TABLE_NAME"))) {
							found = true;
						}
					}
				}
				assertTrue(found);
			}
		} finally {
			openServer.close();
		}
	}

	@Test
	void parkDoesNotMarkChannelClosed() throws Exception {
		final String url = "jdbc:grid://u:p@127.0.0.1:" + port + "/public";
		try (Connection c = DriverManager.getConnection(url)) {
			final GridConnection gc = c.unwrap(GridConnection.class);
			final RemoteConnection remote = (RemoteConnection) gc.spi();
			assertTrue(remote.isOpen());
			remote.close().block();
			assertTrue(remote.isOpen(), "park must not set closed / kill channel");
			assertTrue(findAccountsTable(c.getMetaData()));
		}
	}

	@Test
	void getTablesTwiceAndAfterDelay() throws Exception {
		final String url = "jdbc:grid://u:p@127.0.0.1:" + port + "/public";
		try (Connection c = DriverManager.getConnection(url)) {
			assertTrue(findAccountsTable(c.getMetaData()));
			TimeUnit.MILLISECONDS.sleep(50);
			assertTrue(findAccountsTable(c.getMetaData()));
			assertTrue(c.isValid(3));
		}
	}

	@Test
	void reconnectAfterChannelDeathThenGetTables() throws Exception {
		final String url = "jdbc:grid://u:p@127.0.0.1:" + port + "/public";
		try (Connection c = DriverManager.getConnection(url)) {
			assertTrue(findAccountsTable(c.getMetaData()));

			final GridConnection gc = c.unwrap(GridConnection.class);
			final RemoteConnection remote = (RemoteConnection) gc.spi();
			remote.channel().close().syncUninterruptibly();
			TimeUnit.MILLISECONDS.sleep(150);
			assertFalse(remote.isOpen());

			assertTrue(findAccountsTable(c.getMetaData()));
			assertTrue(c.isValid(3));
			assertTrue(findAccountsTable(c.getMetaData()));
		}
	}

	@Test
	void setAutoCommitRoundTripKeepsMetadataWorking() throws Exception {
		final String url = "jdbc:grid://u:p@127.0.0.1:" + port + "/public";
		try (Connection c = DriverManager.getConnection(url)) {
			c.setAutoCommit(false);
			assertTrue(findAccountsTable(c.getMetaData()));
			c.setAutoCommit(true);
			assertTrue(findAccountsTable(c.getMetaData()));
			assertTrue(c.getAutoCommit());
		}
	}

	@Test
	void concurrentGetTablesOnSameConnection() throws Exception {
		final String url = "jdbc:grid://u:p@127.0.0.1:" + port + "/public";
		try (Connection c = DriverManager.getConnection(url)) {
			final int threads = 4;
			final CountDownLatch ready = new CountDownLatch(threads);
			final CountDownLatch start = new CountDownLatch(1);
			final CountDownLatch done = new CountDownLatch(threads);
			final AtomicReference<Throwable> err = new AtomicReference<>();
			final List<Thread> workers = new ArrayList<>(threads);
			for (int i = 0; i < threads; i++) {
				final Thread t = Thread.ofVirtual().start(() -> {
					ready.countDown();
					try {
						assertTrue(start.await(5, TimeUnit.SECONDS));
						assertTrue(findAccountsTable(c.getMetaData()));
					} catch (Throwable ex) {
						err.compareAndSet(null, ex);
					} finally {
						done.countDown();
					}
				});
				workers.add(t);
			}
			assertTrue(ready.await(5, TimeUnit.SECONDS));
			start.countDown();
			assertTrue(done.await(20, TimeUnit.SECONDS));
			for (Thread t : workers) {
				t.join(2_000);
			}
			if (err.get() != null) {
				throw new AssertionError(err.get());
			}
		}
	}

	@Test
	void multiHostFailoverFirstPortDown() throws Exception {
		final int deadPort = freePort();
		final String url = "jdbc:grid://u:p@127.0.0.1:" + deadPort
				+ ",127.0.0.1:" + port + "/public?connectTimeoutMs=800";
		try (Connection c = DriverManager.getConnection(url)) {
			assertTrue(c.isValid(5));
			try (Statement st = c.createStatement();
				 ResultSet rs = st.executeQuery("SELECT balance FROM accounts WHERE id = 1")) {
				assertTrue(rs.next());
				assertEquals(100, rs.getInt(1));
			}
		}
	}

	@Test
	void multiStatementScriptAsBatch() throws Exception {
		final String url = "jdbc:grid://u:p@127.0.0.1:" + port + "/public";
		try (Connection c = DriverManager.getConnection(url);
			 Statement st = c.createStatement()) {
			final boolean hasResult = st.execute(
					"INSERT INTO accounts (id, balance) VALUES (2, 200); "
							+ "SELECT balance FROM accounts WHERE id = 2;");
			assertFalse(hasResult);
			assertEquals(1, st.getUpdateCount());
			assertTrue(st.getMoreResults());
			try (ResultSet rs = st.getResultSet()) {
				assertTrue(rs.next());
				assertEquals(200, rs.getInt(1));
			}
		}
	}

	@Test
	void timezoneUnwrapAndQueryTimeoutSmoke() throws Exception {
		final String url = "jdbc:grid://u:p@127.0.0.1:" + port + "/public";
		try (Connection c = DriverManager.getConnection(url)) {
			final GridConnection gc = c.unwrap(GridConnection.class);
			assertNotNull(gc.unwrap(org.genfork.grid.sql.client.sync.SyncConnection.class));
			assertNotNull(gc.serverMeta());
			c.setClientInfo(GridConnection.CLIENT_INFO_TIMEZONE, "UTC");
			try (Statement st = c.createStatement()) {
				st.setQueryTimeout(30);
				try (ResultSet rs = st.executeQuery("SELECT balance FROM accounts WHERE id = 1")) {
					assertTrue(rs.next());
					assertEquals(100, rs.getInt(1));
				}
			}
		}
	}

	private static int freePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			socket.setReuseAddress(true);
			return socket.getLocalPort();
		}
	}

	private static boolean findAccountsTable(DatabaseMetaData md) throws Exception {
		try (ResultSet tables = md.getTables(null, "public", "%", new String[]{"TABLE"})) {
			while (tables.next()) {
				if ("accounts".equalsIgnoreCase(tables.getString("TABLE_NAME"))) {
					return true;
				}
			}
		}
		return false;
	}
}