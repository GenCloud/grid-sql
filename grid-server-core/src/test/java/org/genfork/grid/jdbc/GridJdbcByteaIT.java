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
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.netty.SqlServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BYTEA / {@code byte[]} JDBC paths sized like fork-plus-0 player_data blobs.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
class GridJdbcByteaIT {
	/** {@code PlayerDomain#getInteractRejectTypes} default length. */
	private static final int SIZE_INTERACT = 2;
	/** {@code PlayerDomain#getOneTimeQuestBitmask} default length. */
	private static final int SIZE_QUEST_BITMASK = 2048;
	/** {@code PlayerDomain#getKeyBinding} default length. */
	private static final int SIZE_KEY_BINDING = 8000;

	private SqlEngine engine;
	private SqlServer server;
	private int port;

	@BeforeEach
	void setUp() throws Exception {
		engine = new SqlEngine(new TableCatalog(Files.createTempDirectory("jdbc-bytea-it")), null, 4);
		engine.execute("CREATE TABLE bin_t (id INT PRIMARY KEY, payload BYTEA)");
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
	void setBytesRoundTripForkSizesAndEdgeBytes() throws Exception {
		final byte[] small = filled(SIZE_INTERACT, (byte) 0x01);
		small[0] = 0x00;
		small[1] = (byte) 0xff;
		final byte[] mid = filled(SIZE_QUEST_BITMASK, (byte) 0x7f);
		final byte[] large = filled(SIZE_KEY_BINDING, (byte) 0xa5);

		final String url = "jdbc:grid://u:p@127.0.0.1:" + port + "/public";
		try (Connection c = DriverManager.getConnection(url);
			 PreparedStatement ps = c.prepareStatement("INSERT INTO bin_t (id, payload) VALUES (?, ?)")) {
			insert(ps, 1, small);
			insert(ps, 2, mid);
			insert(ps, 3, large);
		}

		assertPayload(url, 1, small);
		assertPayload(url, 2, mid);
		assertPayload(url, 3, large);
	}

	@Test
	void setObjectByteArrayPath() throws Exception {
		final byte[] payload = new byte[]{0x00, (byte) 0xff, 0x10};
		final String url = "jdbc:grid://u:p@127.0.0.1:" + port + "/public";
		try (Connection c = DriverManager.getConnection(url);
			 PreparedStatement ps = c.prepareStatement("INSERT INTO bin_t (id, payload) VALUES (?, ?)")) {
			ps.setInt(1, 10);
			ps.setObject(2, payload);
			assertEquals(1, ps.executeUpdate());
		}
		assertPayload(url, 10, payload);
	}

	@Test
	void returningExposesGeneratedKeysBytes() throws Exception {
		final byte[] payload = new byte[]{9, 8, 7};
		final String url = "jdbc:grid://u:p@127.0.0.1:" + port + "/public";
		try (Connection c = DriverManager.getConnection(url);
			 PreparedStatement ps = c.prepareStatement(
					 "INSERT INTO bin_t (id, payload) VALUES (?, ?) RETURNING id, payload")) {
			ps.setInt(1, 20);
			ps.setBytes(2, payload);
			assertEquals(1, ps.executeUpdate());
			try (ResultSet keys = ps.getGeneratedKeys()) {
				assertTrue(keys.next());
				assertEquals(20, keys.getInt(1));
				assertArrayEquals(payload, keys.getBytes(2));
			}
		}
	}

	@Test
	void updateSelectAndWhereBind() throws Exception {
		final byte[] seed = new byte[]{1, 2, 3};
		final byte[] updated = new byte[]{4, 5, 6};
		final String url = "jdbc:grid://u:p@127.0.0.1:" + port + "/public";
		try (Connection c = DriverManager.getConnection(url);
			 PreparedStatement ins = c.prepareStatement("INSERT INTO bin_t (id, payload) VALUES (?, ?)")) {
			ins.setInt(1, 30);
			ins.setBytes(2, seed);
			assertEquals(1, ins.executeUpdate());
		}
		try (Connection c = DriverManager.getConnection(url);
			 PreparedStatement upd = c.prepareStatement("UPDATE bin_t SET payload = ? WHERE id = ?")) {
			upd.setBytes(1, updated);
			upd.setInt(2, 30);
			assertEquals(1, upd.executeUpdate());
		}
		assertPayload(url, 30, updated);

		try (Connection c = DriverManager.getConnection(url);
			 PreparedStatement sel = c.prepareStatement("SELECT id FROM bin_t WHERE payload = ?")) {
			sel.setBytes(1, updated);
			try (ResultSet rs = sel.executeQuery()) {
				assertTrue(rs.next());
				assertEquals(30, rs.getInt(1));
			}
		}
	}

	@Test
	void hexLiteralAndCastRoundTrip() throws Exception {
		final String url = "jdbc:grid://u:p@127.0.0.1:" + port + "/public";
		try (Connection c = DriverManager.getConnection(url);
			 Statement st = c.createStatement()) {
			assertEquals(1, st.executeUpdate("INSERT INTO bin_t (id, payload) VALUES (40, X'0102FF')"));
			assertEquals(1, st.executeUpdate(
					"INSERT INTO bin_t (id, payload) VALUES (41, CAST('AABB' AS BYTEA))"));
		}
		assertPayload(url, 40, new byte[]{0x01, 0x02, (byte) 0xff});
		assertPayload(url, 41, new byte[]{(byte) 0xaa, (byte) 0xbb});
	}

	private static void insert(PreparedStatement ps, int id, byte[] payload) throws Exception {
		ps.setInt(1, id);
		ps.setBytes(2, payload);
		assertEquals(1, ps.executeUpdate());
	}

	private static void assertPayload(String url, int id, byte[] expected) throws Exception {
		try (Connection c = DriverManager.getConnection(url);
			 PreparedStatement ps = c.prepareStatement("SELECT payload FROM bin_t WHERE id = ?")) {
			ps.setInt(1, id);
			try (ResultSet rs = ps.executeQuery()) {
				assertTrue(rs.next());
				assertArrayEquals(expected, rs.getBytes(1));
				final Object obj = rs.getObject(1);
				assertTrue(obj instanceof byte[], "expected byte[] got " + (obj == null ? "null" : obj.getClass()));
				assertArrayEquals(expected, (byte[]) obj);
			}
		}
	}

	private static byte[] filled(int size, byte value) {
		final byte[] out = new byte[size];
		Arrays.fill(out, value);
		return out;
	}

	private static int freePort() throws Exception {
		try (ServerSocket ss = new ServerSocket(0)) {
			ss.setReuseAddress(true);
			return ss.getLocalPort();
		}
	}
}
