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
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.concurrent.TimeUnit;

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.netty.SqlServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JDBC DatabaseMetaData readiness for jOOQ {@code JDBCDatabase} codegen.
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
class GridJdbcMetaIT {
	private SqlEngine engine;
	private SqlServer server;
	private int port;

	@BeforeEach
	void setUp() throws Exception {
		engine = new SqlEngine(new TableCatalog(Files.createTempDirectory("jdbc-meta-it")), null, 4);
		engine.execute("CREATE TABLE parent (id INT PRIMARY KEY, name VARCHAR)");
		engine.execute("CREATE TABLE child (id INT PRIMARY KEY, parent_id INT, "
				+ "FOREIGN KEY (parent_id) REFERENCES parent (id) ON DELETE RESTRICT)");
		engine.execute("CREATE INDEX idx_child_parent ON child (parent_id)");
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
	void primaryKeysExcludeForeignKeyColumns() throws Exception {
		final String url = "jdbc:grid://u:p@127.0.0.1:" + port + "/public";
		try (Connection c = DriverManager.getConnection(url)) {
			final DatabaseMetaData md = c.getMetaData();
			int pkCount = 0;
			try (ResultSet rs = md.getPrimaryKeys(null, "public", "child")) {
				while (rs.next()) {
					pkCount++;
					assertEquals("id", rs.getString("COLUMN_NAME").toLowerCase());
					assertFalse("parent_id".equalsIgnoreCase(rs.getString("COLUMN_NAME")));
				}
			}
			assertEquals(1, pkCount);
		}
	}

	@Test
	void importedKeysExposeParentChild() throws Exception {
		final String url = "jdbc:grid://u:p@127.0.0.1:" + port + "/public";
		try (Connection c = DriverManager.getConnection(url)) {
			final DatabaseMetaData md = c.getMetaData();
			boolean found = false;
			try (ResultSet rs = md.getImportedKeys(null, "public", "child")) {
				while (rs.next()) {
					found = true;
					assertEquals("parent", rs.getString("PKTABLE_NAME").toLowerCase());
					assertEquals("id", rs.getString("PKCOLUMN_NAME").toLowerCase());
					assertEquals("child", rs.getString("FKTABLE_NAME").toLowerCase());
					assertEquals("parent_id", rs.getString("FKCOLUMN_NAME").toLowerCase());
				}
			}
			assertTrue(found);
		}
	}

	@Test
	void columnsReportNullableAndSize() throws Exception {
		final String url = "jdbc:grid://u:p@127.0.0.1:" + port + "/public";
		try (Connection c = DriverManager.getConnection(url)) {
			final DatabaseMetaData md = c.getMetaData();
			boolean foundName = false;
			try (ResultSet rs = md.getColumns(null, "public", "parent", "name")) {
				while (rs.next()) {
					foundName = true;
					assertTrue(rs.getInt("COLUMN_SIZE") > 0);
					assertEquals("YES", rs.getString("IS_NULLABLE"));
				}
			}
			assertTrue(foundName);
		}
	}

	@Test
	void indexInfoListsSecondaryIndex() throws Exception {
		final String url = "jdbc:grid://u:p@127.0.0.1:" + port + "/public";
		try (Connection c = DriverManager.getConnection(url)) {
			final DatabaseMetaData md = c.getMetaData();
			boolean found = false;
			try (ResultSet rs = md.getIndexInfo(null, "public", "child", false, false)) {
				while (rs.next()) {
					final String name = rs.getString("INDEX_NAME");
					if (name != null && name.toLowerCase().contains("idx_child_parent")) {
						found = true;
						assertEquals("parent_id", rs.getString("COLUMN_NAME").toLowerCase());
					}
				}
			}
			assertTrue(found);
		}
	}

	private static int freePort() throws Exception {
		try (ServerSocket ss = new ServerSocket(0)) {
			ss.setReuseAddress(true);
			return ss.getLocalPort();
		}
	}
}
