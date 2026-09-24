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
package org.genfork.grid.examples.joinagg;

import org.genfork.grid.examples.common.ExampleSupport;
import org.genfork.grid.sql.client.Connection;
import reactor.core.publisher.Mono;

/**
 * Analytical SELECT: INNER JOIN, GROUP BY, HAVING, COUNT/SUM.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.0
 */
public final class JoinAggExample {
	private JoinAggExample() {
	}

	public static void main(String[] args) {
		ExampleSupport.run(factory -> factory.obtain().flatMap(JoinAggExample::run));
	}

	private static Mono<Void> run(Connection conn) {
		return conn.executeUpdate(
						"CREATE TABLE IF NOT EXISTS ex_ja_customers ("
								+ "id BIGINT PRIMARY KEY, name VARCHAR)")
				.then(conn.executeUpdate(
						"CREATE TABLE IF NOT EXISTS ex_ja_orders ("
								+ "id BIGINT PRIMARY KEY, customer_id BIGINT, total BIGINT)"))
				.then(conn.executeUpdate(
						"UPSERT INTO ex_ja_customers (id, name) VALUES (1, 'alice')"))
				.then(conn.executeUpdate(
						"UPSERT INTO ex_ja_customers (id, name) VALUES (2, 'bob')"))
				.then(conn.executeUpdate(
						"UPSERT INTO ex_ja_orders (id, customer_id, total) VALUES (10, 1, 40)"))
				.then(conn.executeUpdate(
						"UPSERT INTO ex_ja_orders (id, customer_id, total) VALUES (11, 1, 60)"))
				.then(conn.executeUpdate(
						"UPSERT INTO ex_ja_orders (id, customer_id, total) VALUES (12, 2, 15)"))
				.thenMany(conn.createStatement(
								"SELECT c.name, COUNT(*) AS cnt, SUM(o.total) AS sum_total "
										+ "FROM ex_ja_orders o "
										+ "JOIN ex_ja_customers c ON o.customer_id = c.id "
										+ "GROUP BY c.name "
										+ "HAVING SUM(o.total) >= 50 "
										+ "ORDER BY c.name")
						.execute()
						.flatMap(r -> r.map(ExampleSupport::formatRow)))
				.doOnNext(line -> ExampleSupport.println("agg: " + line))
				.then(conn.close());
	}
}