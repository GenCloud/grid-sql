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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DBeaver Save-path: partial WHERE delete on composite PRIMARY KEY must be durable.
 * <p>
 * Contract: {@code DELETE … WHERE server_id=?} (or {@code deleteRow} with first-PK predicate)
 * removes all matching rows, not only full PK tuples.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.1
 */
class GridJdbcCompositePkPartialDeleteIT {
	private static final String SCHEMA = "shared_game_server";
	private static final String TABLE = "bitset_range_like";
	private static final String QUALIFIED = SCHEMA + "." + TABLE;
	private static final String DDL = "CREATE TABLE " + QUALIFIED
			+ " (server_id INT, biset_type VARCHAR, min_value INT, max_value INT,"
			+ " PRIMARY KEY (server_id, biset_type))";
	private static final String SELECT_ALL =
			"SELECT server_id, biset_type, min_value, max_value FROM " + QUALIFIED
					+ " ORDER BY server_id, biset_type";
	private static final String SELECT_BY_SERVER =
			"SELECT server_id, biset_type FROM " + QUALIFIED + " WHERE server_id = ";
	private static final String SELECT_BY_SERVER_AND_TYPE =
			"SELECT server_id FROM " + QUALIFIED + " WHERE server_id = ";
	private static final String AND_TYPE = " AND biset_type = '";
	private static final String SQL_CLOSE_QUOTE = "'";
	private static final String DELETE_BY_SERVER =
			"DELETE FROM " + QUALIFIED + " WHERE server_id = ";
	/** DBeaver Data Editor often aliases the table as {@code T} in the UI log only. */
	private static final String SELECT_WITH_ALIAS =
			"SELECT server_id, biset_type, min_value, max_value FROM " + QUALIFIED + " T"
					+ " ORDER BY server_id, biset_type";
	private static final int SERVER_TARGET = 3;
	private static final int SERVER_KEEP = 125;
	private static final int CYCLE_COUNT = 3;

	private SqlEngine engine;
	private SqlServer server;
	private int port;
	private String url;

	@BeforeEach
	void setUp() throws Exception {
		engine = new SqlEngine(new TableCatalog(Files.createTempDirectory("jdbc-comp-pk-del")), null, 4);
		engine.execute("CREATE SCHEMA IF NOT EXISTS shared_game_server");
		engine.execute(DDL);
		seedRows();
		port = freePort();
		server = new SqlServer("127.0.0.1", port, engine, "u", "p", 32);
		server.start();
		Class.forName("org.genfork.grid.jdbc.GridDriver");
		url = "jdbc:grid://u:p@127.0.0.1:" + port + "/public";
		waitReady(url);
	}

	@AfterEach
	void tearDown() {
		if (server != null) {
			server.close();
			server = null;
		}
	}

	@Test
	void deleteRowAutocommitPartialPkRemovesAllServerIdRows() throws Exception {
		try (Connection editor = DriverManager.getConnection(url);
				Connection observer = DriverManager.getConnection(url)) {
			assertTrue(editor.getAutoCommit());
			try (Statement st = editor.createStatement();
					ResultSet rs = st.executeQuery(SELECT_ALL)) {
				assertEquals(ResultSet.CONCUR_UPDATABLE, rs.getConcurrency());
				assertTrue(rs.next());
				assertEquals(SERVER_TARGET, rs.getInt(1));
				rs.deleteRow();
			}
			assertFalse(serverIdExists(observer, SERVER_TARGET),
					"autocommit deleteRow partial WHERE must remove all rows for server_id");
			assertTrue(rowExists(observer, SERVER_KEEP, "ITEMS"),
					"unrelated server_id must remain");
		}
	}

	@Test
	void deleteRowCommitPartialPkRemovesAllServerIdRows() throws Exception {
		try (Connection editor = DriverManager.getConnection(url);
				Connection observer = DriverManager.getConnection(url)) {
			editor.setAutoCommit(false);
			try (Statement st = editor.createStatement();
					ResultSet rs = st.executeQuery(SELECT_ALL)) {
				assertEquals(ResultSet.CONCUR_UPDATABLE, rs.getConcurrency());
				assertTrue(rs.next());
				assertEquals(SERVER_TARGET, rs.getInt(1));
				rs.deleteRow();
			}
			assertTrue(serverIdExists(observer, SERVER_TARGET),
					"uncommitted delete must stay invisible to other connection");
			editor.commit();
			assertFalse(serverIdExists(observer, SERVER_TARGET),
					"committed deleteRow partial WHERE must be durable for all matching rows");
			assertTrue(rowExists(observer, SERVER_KEEP, "ITEMS"));
		}
	}

	@Test
	void executeUpdatePartialWhereAutocommitRemovesAllServerIdRows() throws Exception {
		try (Connection editor = DriverManager.getConnection(url);
				Connection observer = DriverManager.getConnection(url);
				Statement st = editor.createStatement()) {
			final int affected = st.executeUpdate(DELETE_BY_SERVER + SERVER_TARGET);
			assertTrue(affected >= 1, "partial DELETE must report affected >= 1");
			assertFalse(serverIdExists(observer, SERVER_TARGET));
			assertTrue(rowExists(observer, SERVER_KEEP, "ITEMS"));
		}
	}

	@Test
	void deleteRowFromAliasedSelectAutocommitRemovesServerIdRows() throws Exception {
		try (Connection editor = DriverManager.getConnection(url);
				Connection observer = DriverManager.getConnection(url)) {
			try (Statement st = editor.createStatement();
					ResultSet rs = st.executeQuery(SELECT_WITH_ALIAS)) {
				assertEquals(ResultSet.CONCUR_UPDATABLE, rs.getConcurrency(),
						"FROM schema.table T must stay updatable for Data Editor Save");
				assertTrue(rs.next());
				assertEquals(SERVER_TARGET, rs.getInt(1));
				rs.deleteRow();
			}
			assertFalse(serverIdExists(observer, SERVER_TARGET));
			assertTrue(rowExists(observer, SERVER_KEEP, "ITEMS"));
		}
	}

	@Test
	void threeAutocommitDeleteRowCyclesStayDurable() throws Exception {
		try (Connection editor = DriverManager.getConnection(url);
				Connection observer = DriverManager.getConnection(url)) {
			for (int cycle = 0; cycle < CYCLE_COUNT; cycle++) {
				if (cycle > 0) {
					seedRowsViaJdbc(editor);
				}
				try (Statement st = editor.createStatement();
						ResultSet rs = st.executeQuery(SELECT_BY_SERVER + SERVER_TARGET)) {
					assertTrue(rs.next(), "cycle " + cycle + " must see target row");
					rs.deleteRow();
				}
				assertFalse(serverIdExists(observer, SERVER_TARGET),
						"cycle " + cycle + ": durable remove after Save-path deleteRow");
				assertTrue(rowExists(observer, SERVER_KEEP, "ITEMS"));
			}
		}
	}

	private void seedRows() {
		engine.execute("INSERT INTO " + QUALIFIED
				+ " (server_id, biset_type, min_value, max_value) VALUES (3, 'ITEMS', 1, 2)");
		engine.execute("INSERT INTO " + QUALIFIED
				+ " (server_id, biset_type, min_value, max_value) VALUES (3, 'OTHER', 3, 4)");
		engine.execute("INSERT INTO " + QUALIFIED
				+ " (server_id, biset_type, min_value, max_value) VALUES (125, 'ITEMS', 5, 6)");
	}

	private static void seedRowsViaJdbc(Connection c) throws Exception {
		try (Statement st = c.createStatement()) {
			st.executeUpdate("DELETE FROM " + QUALIFIED + " WHERE server_id = " + SERVER_KEEP);
			st.executeUpdate("INSERT INTO " + QUALIFIED
					+ " (server_id, biset_type, min_value, max_value) VALUES (3, 'ITEMS', 1, 2)");
			st.executeUpdate("INSERT INTO " + QUALIFIED
					+ " (server_id, biset_type, min_value, max_value) VALUES (3, 'OTHER', 3, 4)");
			st.executeUpdate("INSERT INTO " + QUALIFIED
					+ " (server_id, biset_type, min_value, max_value) VALUES (125, 'ITEMS', 5, 6)");
		}
	}

	private static boolean serverIdExists(Connection c, int serverId) throws Exception {
		try (Statement st = c.createStatement();
				ResultSet rs = st.executeQuery(SELECT_BY_SERVER + serverId)) {
			return rs.next();
		}
	}

	private static boolean rowExists(Connection c, int serverId, String type) throws Exception {
		try (Statement st = c.createStatement();
				ResultSet rs = st.executeQuery(
						SELECT_BY_SERVER_AND_TYPE + serverId + AND_TYPE + type + SQL_CLOSE_QUOTE)) {
			return rs.next();
		}
	}

	private static void waitReady(String jdbcUrl) throws Exception {
		final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		SQLException last = null;
		while (System.nanoTime() < deadline) {
			try (Connection c = DriverManager.getConnection(jdbcUrl)) {
				if (c.isValid(2)) {
					return;
				}
			} catch (SQLException ex) {
				last = ex;
			}
			TimeUnit.MILLISECONDS.sleep(50);
		}
		if (last != null) {
			throw last;
		}
		throw new IllegalStateException("SqlServer not ready: " + jdbcUrl);
	}

	private static int freePort() throws Exception {
		try (ServerSocket ss = new ServerSocket(0)) {
			ss.setReuseAddress(true);
			return ss.getLocalPort();
		}
	}
}
