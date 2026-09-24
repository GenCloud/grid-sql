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
package org.genfork.grid.examples.dml;

import org.genfork.grid.examples.common.ExampleSupport;
import org.genfork.grid.sql.client.Connection;
import reactor.core.publisher.Mono;

/**
 * DML surface: INSERT, UPSERT, UPDATE, DELETE, ON CONFLICT, MERGE, RETURNING.
 * <p>
 * v1: do not mix RMW ({@code total = total + 10}) and literal SET in one UPDATE —
 * run them as separate statements.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.0
 */
public final class DmlExample {
	private static final String DDL =
			"CREATE TABLE IF NOT EXISTS ex_dml ("
					+ "id BIGINT PRIMARY KEY, customer_id BIGINT, total BIGINT, status VARCHAR)";

	private DmlExample() {
	}

	public static void main(String[] args) {
		ExampleSupport.run(factory -> factory.obtain().flatMap(DmlExample::run));
	}

	private static Mono<Void> run(Connection conn) {
		return conn.executeUpdate(DDL)
				.then(conn.executeUpdate(
						"INSERT INTO ex_dml (id, customer_id, total, status) "
								+ "VALUES (1, 100, 50, 'new')"))
				.doOnNext(n -> ExampleSupport.println("INSERT rowsUpdated=" + n))
				.then(conn.executeUpdate(
						"INSERT INTO ex_dml (id, customer_id, total, status) "
								+ "VALUES (1, 100, 99, 'dup') ON CONFLICT DO NOTHING"))
				.doOnNext(n -> ExampleSupport.println("ON CONFLICT DO NOTHING rowsUpdated=" + n))
				.then(conn.executeUpdate(
						"UPSERT INTO ex_dml (id, customer_id, total, status) "
								+ "VALUES (1, 100, 60, 'upserted')"))
				.doOnNext(n -> ExampleSupport.println("UPSERT rowsUpdated=" + n))
				.then(conn.executeUpdate(
						"UPDATE ex_dml SET total = total + 10 WHERE id = 1"))
				.doOnNext(n -> ExampleSupport.println("UPDATE RMW (total=total+10) rowsUpdated=" + n))
				.then(conn.executeUpdate(
						"UPDATE ex_dml SET status = 'updated' WHERE id = 1"))
				.doOnNext(n -> ExampleSupport.println("UPDATE literal (status) rowsUpdated=" + n))
				.thenMany(conn.createStatement(
								"UPSERT INTO ex_dml (id, customer_id, total, status) "
										+ "VALUES (2, 101, 15, 'ret') RETURNING id, total, status")
						.execute()
						.flatMap(r -> r.map(ExampleSupport::formatRow)))
				.doOnNext(line -> ExampleSupport.println("RETURNING: " + line))
				.then(conn.executeUpdate(
						"MERGE INTO ex_dml "
								+ "USING (VALUES (2, 101, 40, 'merged')) "
								+ "ON id = id "
								+ "WHEN MATCHED THEN UPDATE SET total = 40, status = 'merged' "
								+ "WHEN NOT MATCHED THEN INSERT (id, customer_id, total, status) "
								+ "VALUES (2, 101, 40, 'merged')"))
				.doOnNext(n -> ExampleSupport.println("MERGE rowsUpdated=" + n))
				.then(conn.executeUpdate("DELETE FROM ex_dml WHERE id = 2"))
				.doOnNext(n -> ExampleSupport.println("DELETE rowsUpdated=" + n))
				.thenMany(conn.createStatement(
								"SELECT id, customer_id, total, status FROM ex_dml ORDER BY id")
						.execute()
						.flatMap(r -> r.map(ExampleSupport::formatRow)))
				.doOnNext(line -> ExampleSupport.println("final: " + line))
				.then(conn.close());
	}
}