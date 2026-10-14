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

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
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
 * Bound BATCH_EXEC over JDBC: {@code byte[]} binds round-trip via one wire batch.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
class SqlBatchExecBoundIT {
	private static final byte[] PAYLOAD_A = new byte[]{1, 2, 3, 4};
	private static final byte[] PAYLOAD_B = new byte[]{9, 8, 7};

	private SqlEngine engine;
	private SqlServer server;
	private int port;

	@BeforeEach
	void setUp() throws Exception {
		engine = new SqlEngine(new TableCatalog(Files.createTempDirectory("batch-bound")), null, 4);
		engine.execute("CREATE TABLE bin_batch (id INT PRIMARY KEY, payload BYTEA)");
		port = 26110 + (int) (Math.abs(System.nanoTime()) % 300);
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
	void jdbcPreparedStatementBatchInsertsBytea() throws Exception {
		final String url = "jdbc:grid://u:p@127.0.0.1:" + port + "/public";
		try (Connection c = DriverManager.getConnection(url);
			 PreparedStatement insert = c.prepareStatement(
					 "INSERT INTO bin_batch (id, payload) VALUES (?, ?)")) {
			insert.setInt(1, 1);
			insert.setBytes(2, PAYLOAD_A);
			insert.addBatch();
			insert.setInt(1, 2);
			insert.setBytes(2, PAYLOAD_B);
			insert.addBatch();
			final int[] counts = insert.executeBatch();
			assertEquals(2, counts.length);
			assertEquals(1, counts[0]);
			assertEquals(1, counts[1]);
		}

		final Object a = engine.execute("SELECT payload FROM bin_batch WHERE id = 1").rows().getFirst()[0];
		final Object b = engine.execute("SELECT payload FROM bin_batch WHERE id = 2").rows().getFirst()[0];
		assertTrue(a instanceof byte[], "expected byte[] got " + (a == null ? "null" : a.getClass()));
		assertTrue(b instanceof byte[], "expected byte[] got " + (b == null ? "null" : b.getClass()));
		assertArrayEquals(PAYLOAD_A, (byte[]) a);
		assertArrayEquals(PAYLOAD_B, (byte[]) b);
	}

	@Test
	void jdbcPreparedStatementBatchUpdatesBytea() throws Exception {
		final byte[] updated = new byte[]{0x0a, 0x0b, 0x0c};
		final byte[] seed = new byte[]{1, 2};

		final String url = "jdbc:grid://u:p@127.0.0.1:" + port + "/public";
		try (Connection c = DriverManager.getConnection(url);
			 PreparedStatement seedInsert = c.prepareStatement(
					 "INSERT INTO bin_batch (id, payload) VALUES (?, ?)")) {
			seedInsert.setInt(1, 10);
			seedInsert.setBytes(2, seed);
			assertEquals(1, seedInsert.executeUpdate());
		}

		try (Connection c = DriverManager.getConnection(url);
			 PreparedStatement update = c.prepareStatement(
					 "UPDATE bin_batch SET payload = ? WHERE id = ?")) {
			update.setBytes(1, updated);
			update.setInt(2, 10);
			update.addBatch();
			update.setBytes(1, PAYLOAD_A);
			update.setInt(2, 10);
			update.addBatch();
			final int[] counts = update.executeBatch();
			assertEquals(2, counts.length);
			assertEquals(1, counts[1]);
		}

		final Object cell = engine.execute("SELECT payload FROM bin_batch WHERE id = 10").rows().getFirst()[0];
		assertTrue(cell instanceof byte[], "expected byte[] got " + (cell == null ? "null" : cell.getClass()));
		assertArrayEquals(PAYLOAD_A, (byte[]) cell);
	}
}
