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
package org.genfork.grid.examples.jdbcsavepoints;

import org.genfork.grid.examples.common.ExampleSupport;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;

/**
 * JDBC savepoints inside {@code autoCommit=false}.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.0
 */
public final class JdbcSavepointsExample {
	private JdbcSavepointsExample() {
	}

	public static void main(String[] args) throws SQLException {
		ExampleSupport.runJdbc(JdbcSavepointsExample::run);
	}

	private static void run(Connection c) throws SQLException {
		try (Statement st = c.createStatement()) {
			st.executeUpdate(
					"CREATE TABLE IF NOT EXISTS ex_jdbc_sp ("
							+ "id BIGINT PRIMARY KEY, v BIGINT)");
			st.executeUpdate("UPSERT INTO ex_jdbc_sp (id, v) VALUES (1, 0)");
			st.executeUpdate("UPSERT INTO ex_jdbc_sp (id, v) VALUES (2, 0)");
		}
		c.setAutoCommit(false);
		try (Statement st = c.createStatement()) {
			st.executeUpdate("UPDATE ex_jdbc_sp SET v = 1 WHERE id = 1");
			final Savepoint sp = c.setSavepoint("s1");
			ExampleSupport.println("savepoint s1");
			st.executeUpdate("UPDATE ex_jdbc_sp SET v = 2 WHERE id = 2");
			c.rollback(sp);
			ExampleSupport.println("rollback to s1");
			c.releaseSavepoint(sp);
			ExampleSupport.println("released s1");
			c.commit();
		} catch (SQLException ex) {
			c.rollback();
			throw ex;
		} finally {
			c.setAutoCommit(true);
		}
		try (Statement st = c.createStatement();
			 ResultSet rs = st.executeQuery("SELECT id, v FROM ex_jdbc_sp ORDER BY id")) {
			while (rs.next()) {
				ExampleSupport.println("id=" + rs.getLong("id") + " v=" + rs.getLong("v"));
			}
		}
	}
}