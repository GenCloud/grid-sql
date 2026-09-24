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
package org.genfork.grid.examples.jdbcdml;

import org.genfork.grid.examples.common.ExampleSupport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * JDBC DML: Statement + PreparedStatement + ResultSet (tooling path, not capacity).
 * <p>
 * UPDATE RMW and literal SET are separate statements (v1 rule).
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.0
 */
public final class JdbcDmlExample {
	private JdbcDmlExample() {
	}

	public static void main(String[] args) throws SQLException {
		ExampleSupport.runJdbc(JdbcDmlExample::run);
	}

	private static void run(Connection c) throws SQLException {
		try (Statement st = c.createStatement()) {
			st.executeUpdate(
					"CREATE TABLE IF NOT EXISTS ex_jdbc_dml ("
							+ "id BIGINT PRIMARY KEY, total BIGINT, status VARCHAR)");
			ExampleSupport.println("DDL ok");
			st.executeUpdate(
					"UPSERT INTO ex_jdbc_dml (id, total, status) VALUES (1, 50, 'new')");
		}
		try (PreparedStatement ps = c.prepareStatement(
				"UPSERT INTO ex_jdbc_dml (id, total, status) VALUES (?, ?, ?)")) {
			ps.setLong(1, 1L);
			ps.setLong(2, 60L);
			ps.setString(3, "upserted");
			ExampleSupport.println("Prepared UPSERT rows=" + ps.executeUpdate());
		}
		try (Statement st = c.createStatement()) {
			ExampleSupport.println("UPDATE RMW rows=" + st.executeUpdate(
					"UPDATE ex_jdbc_dml SET total = total + 10 WHERE id = 1"));
			ExampleSupport.println("UPDATE literal rows=" + st.executeUpdate(
					"UPDATE ex_jdbc_dml SET status = 'updated' WHERE id = 1"));
		}
		try (PreparedStatement ps = c.prepareStatement(
				"SELECT id, total, status FROM ex_jdbc_dml WHERE id = ?")) {
			ps.setLong(1, 1L);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					ExampleSupport.println("row id=" + rs.getLong("id")
							+ " total=" + rs.getLong("total")
							+ " status=" + rs.getString("status"));
				}
			}
		}
		try (Statement st = c.createStatement()) {
			ExampleSupport.println("DELETE rows=" + st.executeUpdate(
					"DELETE FROM ex_jdbc_dml WHERE id = 1"));
		}
	}
}