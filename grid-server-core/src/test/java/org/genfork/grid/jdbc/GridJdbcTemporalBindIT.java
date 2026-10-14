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
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.netty.SqlServer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JDBC temporal binds: {@code setTimestamp} / {@code LocalDateTime} / {@code Instant} /
 * DATE / TIME against {@link SqlServer} (pledge_game_season ON CONFLICT shape).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
class GridJdbcTemporalBindIT {
	private static final String PLEDGE_UPSERT_SQL =
			"INSERT INTO pledge_game_season (id, season, step, prev_winner_id, prev_winner_got_item, updated_at) "
					+ "VALUES (?, ?, ?, ?, ?, ?) "
					+ "ON CONFLICT (id) DO UPDATE SET season = ?, step = ?, prev_winner_id = ?, "
					+ "prev_winner_got_item = ?, updated_at = ?";

	private SqlEngine engine;
	private SqlServer server;
	private int port;

	@BeforeEach
	void setUp() throws Exception {
		engine = new SqlEngine(new TableCatalog(Files.createTempDirectory("jdbc-temporal-it")), null, 4);
		engine.execute(
				"CREATE TABLE pledge_game_season ("
						+ "id INT PRIMARY KEY, season INT, step INT, prev_winner_id INT, "
						+ "prev_winner_got_item BOOLEAN, updated_at TIMESTAMP)");
		engine.execute(
				"CREATE TABLE ev (id INT PRIMARY KEY, d DATE, t TIME, ts TIMESTAMP, tsz TIMESTAMPTZ)");
		engine.execute("CREATE TABLE nums (id INT PRIMARY KEY, n INT)");
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
	void setTimestampOnConflictPledgeShapeRoundTrip() throws Exception {
		final LocalDateTime wall = LocalDateTime.of(2026, 10, 10, 11, 28, 8);
		final Timestamp ts = Timestamp.valueOf(wall);
		final String url = jdbcUrl();
		try (Connection c = DriverManager.getConnection(url);
			 PreparedStatement ps = c.prepareStatement(PLEDGE_UPSERT_SQL)) {
			bindPledgeRow(ps, 1, 2, 3, 4, true, ts);
			assertEquals(1, ps.executeUpdate());
			bindPledgeRow(ps, 1, 5, 6, 7, false, ts);
			assertEquals(1, ps.executeUpdate());
		}
		try (Connection c = DriverManager.getConnection(url);
			 Statement st = c.createStatement();
			 ResultSet rs = st.executeQuery(
					 "SELECT season, step, prev_winner_id, prev_winner_got_item, updated_at "
							 + "FROM pledge_game_season WHERE id = 1")) {
			assertTrue(rs.next());
			assertEquals(5, rs.getInt(1));
			assertEquals(6, rs.getInt(2));
			assertEquals(7, rs.getInt(3));
			assertFalse(rs.getBoolean(4));
			assertEquals(ts, rs.getTimestamp(5));
		}
	}

	@Test
	void setObjectLocalDateTimeToTimestamp() throws Exception {
		final LocalDateTime wall = LocalDateTime.of(2026, 9, 17, 13, 45, 30);
		final String url = jdbcUrl();
		try (Connection c = DriverManager.getConnection(url);
			 PreparedStatement ps = c.prepareStatement(
					 "INSERT INTO ev (id, d, t, ts, tsz) VALUES (1, DATE '2026-09-17', "
							 + "TIME '00:00:00', ?, TIMESTAMPTZ '2026-09-17T10:00:00Z')")) {
			ps.setObject(1, wall);
			assertEquals(1, ps.executeUpdate());
		}
		try (Connection c = DriverManager.getConnection(url);
			 Statement st = c.createStatement();
			 ResultSet rs = st.executeQuery("SELECT ts FROM ev WHERE id = 1")) {
			assertTrue(rs.next());
			assertEquals(Timestamp.valueOf(wall), rs.getTimestamp(1));
			assertEquals(wall, rs.getObject(1, LocalDateTime.class));
		}
	}

	/** ISO storage may omit seconds ({@code …T12:00}); {@code getTimestamp} / LDT typed get must still work. */
	@Test
	void localDateTimeWithoutSecondsRoundTrip() throws Exception {
		final LocalDateTime wall = LocalDateTime.of(2026, 10, 12, 12, 0, 0);
		final String url = jdbcUrl();
		try (Connection c = DriverManager.getConnection(url);
			 PreparedStatement ps = c.prepareStatement(
					 "INSERT INTO ev (id, d, t, ts, tsz) VALUES (10, DATE '2026-10-12', "
							 + "TIME '00:00:00', ?, TIMESTAMPTZ '2026-10-12T12:00:00Z')")) {
			ps.setObject(1, wall);
			assertEquals(1, ps.executeUpdate());
		}
		try (Connection c = DriverManager.getConnection(url);
			 Statement st = c.createStatement();
			 ResultSet rs = st.executeQuery("SELECT ts FROM ev WHERE id = 10")) {
			assertTrue(rs.next());
			assertEquals(wall, rs.getObject(1, LocalDateTime.class));
			assertEquals(Timestamp.valueOf(wall), rs.getTimestamp(1));
		}
	}

	@Test
	void setObjectInstantToTimestamptz() throws Exception {
		final Instant instant = Instant.parse("2026-09-17T10:45:30Z");
		final String url = jdbcUrl();
		try (Connection c = DriverManager.getConnection(url);
			 PreparedStatement ps = c.prepareStatement(
					 "INSERT INTO ev (id, d, t, ts, tsz) VALUES (2, DATE '2026-09-17', "
							 + "TIME '00:00:00', TIMESTAMP '2026-09-17T00:00:00', ?)")) {
			ps.setObject(1, instant, Types.TIMESTAMP_WITH_TIMEZONE);
			assertEquals(1, ps.executeUpdate());
		}
		try (Connection c = DriverManager.getConnection(url);
			 Statement st = c.createStatement();
			 ResultSet rs = st.executeQuery("SELECT tsz FROM ev WHERE id = 2")) {
			assertTrue(rs.next());
			assertEquals(Timestamp.from(instant), rs.getTimestamp(1));
		}
	}

	@Test
	void setDateAndSetTimeRoundTrip() throws Exception {
		final Date date = Date.valueOf(LocalDate.of(2026, 9, 17));
		final Time time = Time.valueOf(LocalTime.of(13, 45, 30));
		final String url = jdbcUrl();
		try (Connection c = DriverManager.getConnection(url);
			 PreparedStatement ps = c.prepareStatement(
					 "INSERT INTO ev (id, d, t, ts, tsz) VALUES (3, ?, ?, "
							 + "TIMESTAMP '2026-09-17T00:00:00', TIMESTAMPTZ '2026-09-17T10:00:00Z')")) {
			ps.setDate(1, date);
			ps.setTime(2, time);
			assertEquals(1, ps.executeUpdate());
		}
		try (Connection c = DriverManager.getConnection(url);
			 Statement st = c.createStatement();
			 ResultSet rs = st.executeQuery("SELECT d, t FROM ev WHERE id = 3")) {
			assertTrue(rs.next());
			assertEquals(date, rs.getDate(1));
			assertEquals(time, rs.getTime(2));
		}
	}

	@Test
	void instantBoundToIntFailsClosed() throws Exception {
		final String url = jdbcUrl();
		try (Connection c = DriverManager.getConnection(url);
			 PreparedStatement ps = c.prepareStatement("INSERT INTO nums (id, n) VALUES (?, ?)")) {
			ps.setInt(1, 1);
			ps.setObject(2, Instant.parse("2026-10-10T08:28:08.410874800Z"));
			final SQLException ex = assertThrows(SQLException.class, ps::executeUpdate);
			final String msg = ex.getMessage() == null ? "" : ex.getMessage();
			assertTrue(
					msg.contains("cannot coerce") || msg.contains("temporal") || msg.contains("INT"),
					() -> "expected typed coerce error, got: " + msg);
			assertFalse(msg.contains("For input string"), () -> "must not be NumberFormatException: " + msg);
		}
	}

	private static void bindPledgeRow(
			PreparedStatement ps,
			int id,
			int season,
			int step,
			int prevWinnerId,
			boolean prevWinnerGotItem,
			Timestamp updatedAt
	) throws SQLException {
		ps.setInt(1, id);
		ps.setInt(2, season);
		ps.setInt(3, step);
		ps.setInt(4, prevWinnerId);
		ps.setBoolean(5, prevWinnerGotItem);
		ps.setTimestamp(6, updatedAt);
		ps.setInt(7, season);
		ps.setInt(8, step);
		ps.setInt(9, prevWinnerId);
		ps.setBoolean(10, prevWinnerGotItem);
		ps.setTimestamp(11, updatedAt);
	}

	private String jdbcUrl() {
		return "jdbc:grid://u:p@127.0.0.1:" + port + "/public";
	}

	private static int freePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			socket.setReuseAddress(true);
			return socket.getLocalPort();
		}
	}
}
