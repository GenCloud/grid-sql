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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * jOOQ {@code SQLDialect.DEFAULT} {@code insert…returning()} uses {@code executeUpdate}
 * + {@code getGeneratedKeys}; Grid must surface RETURNING rows that way.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
class GridJdbcReturningGeneratedKeysIT {
	private SqlEngine engine;
	private SqlServer server;
	private int port;

	@BeforeEach
	void setUp() throws Exception {
		engine = new SqlEngine(new TableCatalog(Files.createTempDirectory("jdbc-returning-it")), null, 4);
		engine.execute("CREATE TABLE player_data (id SERIAL PRIMARY KEY, character_name VARCHAR)");
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
	void executeUpdateReturningExposesGetGeneratedKeys() throws Exception {
		final String url = "jdbc:grid://u:p@127.0.0.1:" + port + "/public";
		try (GridDataSource ds = new GridDataSource(url);
			 Connection c = ds.getConnection();
			 PreparedStatement ps = c.prepareStatement(
					 "INSERT INTO player_data (character_name) VALUES (?) RETURNING id, character_name")) {
			ps.setString(1, "Hero");
			assertEquals(1, ps.executeUpdate());
			try (ResultSet keys = ps.getGeneratedKeys()) {
				assertTrue(keys.next());
				assertEquals(1, keys.getInt(1));
				assertEquals("Hero", keys.getString(2));
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
