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
package org.genfork.grid.examples.jdbcbatch;

import org.genfork.grid.examples.common.ExampleSupport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;

/**
 * JDBC batch: string {@link Statement#addBatch} vs PreparedStatement bind-batch.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.0
 */
public final class JdbcBatchExample {
	private JdbcBatchExample() {
	}

	public static void main(String[] args) throws SQLException {
		ExampleSupport.runJdbc(JdbcBatchExample::run);
	}

	private static void run(Connection c) throws SQLException {
		try (Statement st = c.createStatement()) {
			st.executeUpdate(
					"CREATE TABLE IF NOT EXISTS ex_jdbc_batch ("
							+ "id BIGINT PRIMARY KEY, name VARCHAR)");
			st.addBatch("UPSERT INTO ex_jdbc_batch (id, name) VALUES (1, 'ac-1')");
			st.addBatch("UPSERT INTO ex_jdbc_batch (id, name) VALUES (2, 'ac-2')");
			final int[] ac = st.executeBatch();
			ExampleSupport.println("Statement batch counts=" + Arrays.toString(ac));
		}
		c.setAutoCommit(false);
		try (PreparedStatement ps = c.prepareStatement(
				"UPSERT INTO ex_jdbc_batch (id, name) VALUES (?, ?)")) {
			ps.setLong(1, 3L);
			ps.setString(2, "tx-3");
			ps.addBatch();
			ps.setLong(1, 4L);
			ps.setString(2, "tx-4");
			ps.addBatch();
			final int[] tx = ps.executeBatch();
			ExampleSupport.println("PreparedStatement batch counts=" + Arrays.toString(tx));
			c.commit();
		} catch (SQLException ex) {
			c.rollback();
			throw ex;
		} finally {
			c.setAutoCommit(true);
		}
		try (Statement st = c.createStatement();
			 ResultSet rs = st.executeQuery(
					 "SELECT id, name FROM ex_jdbc_batch ORDER BY id")) {
			while (rs.next()) {
				ExampleSupport.println("id=" + rs.getLong("id")
						+ " name=" + rs.getString("name"));
			}
		}
	}
}