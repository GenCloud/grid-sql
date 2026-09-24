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
package org.genfork.grid.examples.jdbcconnect;

import org.genfork.grid.examples.common.ExampleSupport;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * JDBC tooling connect: {@code DriverManager} + {@code jdbc:grid://}, {@code isValid}, metadata.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.0
 */
public final class JdbcConnectExample {
	private JdbcConnectExample() {
	}

	public static void main(String[] args) throws SQLException {
		ExampleSupport.runJdbc(JdbcConnectExample::run);
	}

	private static void run(Connection c) throws SQLException {
		ExampleSupport.println("isValid(5)=" + c.isValid(5));
		ExampleSupport.println("autoCommit=" + c.getAutoCommit());
		final DatabaseMetaData md = c.getMetaData();
		ExampleSupport.println("product=" + md.getDatabaseProductName()
				+ " " + md.getDatabaseProductVersion());
		ExampleSupport.println("driver=" + md.getDriverName() + " " + md.getDriverVersion());
		int tables = 0;
		try (ResultSet rs = md.getTables(null, "public", "%", new String[]{"TABLE"})) {
			while (rs.next()) {
				tables++;
				if (tables <= 5) {
					ExampleSupport.println("table: " + rs.getString("TABLE_NAME"));
				}
			}
		}
		ExampleSupport.println("public tables (count)=" + tables);
		try (Statement st = c.createStatement();
			 ResultSet rs = st.executeQuery("SELECT 1 AS one")) {
			if (rs.next()) {
				ExampleSupport.println("SELECT 1 -> " + rs.getInt("one"));
			}
		}
	}
}