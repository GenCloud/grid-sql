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
 * ResultSet.deleteRow visibility under JDBC autoCommit (DBeaver data-editor path).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.1
 */
class GridJdbcDeleteRowTxIT {
	private static final String TABLE = "edit_rows";
	private static final String SELECT_ALL = "SELECT id, name FROM " + TABLE + " ORDER BY id";
	private static final String SELECT_BY_ID = "SELECT id FROM " + TABLE + " WHERE id = ";

	private SqlEngine engine;
	private SqlServer server;
	private int port;
	private String url;

	@BeforeEach
	void setUp() throws Exception {
		engine = new SqlEngine(new TableCatalog(Files.createTempDirectory("jdbc-delete-tx")), null, 4);
		engine.execute("CREATE TABLE " + TABLE + " (id INT PRIMARY KEY, name VARCHAR)");
		engine.execute("INSERT INTO " + TABLE + " (id, name) VALUES (1, 'a')");
		engine.execute("INSERT INTO " + TABLE + " (id, name) VALUES (2, 'b')");
		port = freePort();
		server = new SqlServer("127.0.0.1", port, engine, "u", "p", 32);
		server.start();
		Class.forName("org.genfork.grid.jdbc.GridDriver");
		url = "jdbc:grid://u:p@127.0.0.1:" + port + "/public";
		waitReady(url);
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

	@AfterEach
	void tearDown() {
		if (server != null) {
			server.close();
			server = null;
		}
	}

	@Test
	void deleteRowWithoutCommitNotVisibleOnOtherConnection() throws Exception {
		try (Connection editor = DriverManager.getConnection(url);
				Connection observer = DriverManager.getConnection(url)) {
			editor.setAutoCommit(false);
			try (Statement st = editor.createStatement();
					ResultSet rs = st.executeQuery(SELECT_ALL)) {
				assertEquals(ResultSet.CONCUR_UPDATABLE, rs.getConcurrency());
				assertEquals(ResultSet.CONCUR_UPDATABLE, st.getResultSetConcurrency());
				assertEquals(ResultSet.TYPE_FORWARD_ONLY, st.getResultSetType());
				assertTrue(rs.next());
				assertEquals(1, rs.getInt(1));
				rs.deleteRow();
			}
			assertTrue(rowExists(observer, 1), "uncommitted delete must stay invisible to other connection");
			editor.commit();
			assertFalse(rowExists(observer, 1), "committed delete must be durable");
		}
	}

	@Test
	void deleteRowAutocommitVisibleImmediately() throws Exception {
		try (Connection editor = DriverManager.getConnection(url);
				Connection observer = DriverManager.getConnection(url)) {
			assertTrue(editor.getAutoCommit());
			try (Statement st = editor.createStatement();
					ResultSet rs = st.executeQuery(SELECT_BY_ID + "1")) {
				assertEquals(ResultSet.CONCUR_UPDATABLE, rs.getConcurrency());
				assertTrue(rs.next());
				rs.deleteRow();
			}
			assertFalse(rowExists(editor, 1), "autocommit delete must apply on same connection");
			assertFalse(rowExists(observer, 1), "autocommit delete must be visible to other connection");
		}
	}

	private static boolean rowExists(Connection c, int id) throws Exception {
		try (Statement st = c.createStatement();
				ResultSet rs = st.executeQuery(SELECT_BY_ID + id)) {
			return rs.next();
		}
	}

	private static int freePort() throws Exception {
		try (ServerSocket ss = new ServerSocket(0)) {
			ss.setReuseAddress(true);
			return ss.getLocalPort();
		}
	}
}
