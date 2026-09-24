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
package org.genfork.grid.examples.jdbcsession;

import org.genfork.grid.examples.common.ExampleSupport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * JDBC session helpers: {@code setSchema}, URL options, {@code setFetchSize}.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.0
 */
public final class JdbcSessionExample {
	private static final String GRID_URL =
			"grid://@127.0.0.1:15432/public"
					+ "?warmup=true&maxConnections=1&fetchWindow=32&connectTimeoutMs=2000";

	private JdbcSessionExample() {
	}

	public static void main(String[] args) throws SQLException {
		ExampleSupport.println("grid URL default: " + GRID_URL);
		ExampleSupport.runJdbc(GRID_URL, JdbcSessionExample::run);
	}

	private static void run(Connection c) throws SQLException {
		c.setSchema("public");
		ExampleSupport.println("schema=" + c.getSchema());
		try (Statement st = c.createStatement()) {
			st.executeUpdate(
					"CREATE TABLE IF NOT EXISTS ex_jdbc_session ("
							+ "id BIGINT PRIMARY KEY, name VARCHAR)");
			st.executeUpdate(
					"UPSERT INTO ex_jdbc_session (id, name) VALUES (1, 'jdbc-session')");
		}
		try (PreparedStatement ps = c.prepareStatement(
				"SELECT id, name FROM ex_jdbc_session WHERE id = ?")) {
			ps.setFetchSize(16);
			ps.setLong(1, 1L);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					ExampleSupport.println("row id=" + rs.getLong("id")
							+ " name=" + rs.getString("name"));
				}
			}
		}
	}
}