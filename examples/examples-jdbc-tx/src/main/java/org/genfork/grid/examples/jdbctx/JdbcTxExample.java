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
package org.genfork.grid.examples.jdbctx;

import org.genfork.grid.examples.common.ExampleSupport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * JDBC transactions: {@code setAutoCommit(false)} / commit / rollback.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.0
 */
public final class JdbcTxExample {
	private JdbcTxExample() {
	}

	public static void main(String[] args) throws SQLException {
		ExampleSupport.runJdbc(JdbcTxExample::run);
	}

	private static void run(Connection c) throws SQLException {
		try (Statement st = c.createStatement()) {
			st.executeUpdate(
					"CREATE TABLE IF NOT EXISTS ex_jdbc_tx ("
							+ "id BIGINT PRIMARY KEY, name VARCHAR, balance BIGINT)");
			st.executeUpdate(
					"UPSERT INTO ex_jdbc_tx (id, name, balance) VALUES (1, 'alice', 100)");
			st.executeUpdate(
					"UPSERT INTO ex_jdbc_tx (id, name, balance) VALUES (2, 'bob', 50)");
		}
		printBalances(c, "before transfer");

		c.setAutoCommit(false);
		try {
			try (PreparedStatement debit = c.prepareStatement(
					"UPDATE ex_jdbc_tx SET balance = balance - ? WHERE id = ?");
				 PreparedStatement credit = c.prepareStatement(
						 "UPDATE ex_jdbc_tx SET balance = balance + ? WHERE id = ?")) {
				debit.setLong(1, 10L);
				debit.setLong(2, 1L);
				debit.executeUpdate();
				credit.setLong(1, 10L);
				credit.setLong(2, 2L);
				credit.executeUpdate();
			}
			c.commit();
			ExampleSupport.println("commit OK");
		} catch (SQLException ex) {
			c.rollback();
			throw ex;
		} finally {
			c.setAutoCommit(true);
		}
		printBalances(c, "after commit");

		c.setAutoCommit(false);
		try {
			try (PreparedStatement debit = c.prepareStatement(
					"UPDATE ex_jdbc_tx SET balance = balance - ? WHERE id = ?")) {
				debit.setLong(1, 10L);
				debit.setLong(2, 1L);
				debit.executeUpdate();
			}
			throw new SQLException("forced failure after debit", "25000");
		} catch (SQLException ex) {
			c.rollback();
			ExampleSupport.println("expected rollback: " + ex.getMessage());
		} finally {
			c.setAutoCommit(true);
		}
		printBalances(c, "after rollback");
	}

	private static void printBalances(Connection c, String label) throws SQLException {
		ExampleSupport.println("--- " + label + " ---");
		try (Statement st = c.createStatement();
			 ResultSet rs = st.executeQuery(
					 "SELECT id, name, balance FROM ex_jdbc_tx ORDER BY id")) {
			while (rs.next()) {
				ExampleSupport.println("id=" + rs.getLong("id")
						+ " name=" + rs.getString("name")
						+ " balance=" + rs.getLong("balance"));
			}
		}
	}
}