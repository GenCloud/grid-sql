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
package org.genfork.grid.examples.explain;

import org.genfork.grid.examples.common.ExampleSupport;
import org.genfork.grid.sql.client.Connection;
import reactor.core.publisher.Mono;

/**
 * {@code EXPLAIN} / {@code EXPLAIN ANALYZE} plan text via the product SQL path.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.0
 */
public final class ExplainExample {
	private ExplainExample() {
	}

	public static void main(String[] args) {
		ExampleSupport.run(factory -> factory.obtain().flatMap(ExplainExample::run));
	}

	private static Mono<Void> run(Connection conn) {
		return conn.executeUpdate(
						"CREATE TABLE IF NOT EXISTS ex_explain ("
								+ "id BIGINT PRIMARY KEY, name VARCHAR, flag BIGINT)")
				.then(conn.executeUpdate(
						"CREATE INDEX idx_ex_explain_flag ON ex_explain (flag)")
						.then()
						.onErrorResume(err -> {
							ExampleSupport.println("index DDL skip: " + err.getMessage());
							return Mono.empty();
						}))
				.then(conn.executeUpdate(
						"UPSERT INTO ex_explain (id, name, flag) VALUES (1, 'a', 1)"))
				.then(conn.executeUpdate(
						"UPSERT INTO ex_explain (id, name, flag) VALUES (2, 'b', 0)"))
				.thenMany(conn.createStatement(
								"EXPLAIN SELECT id, name FROM ex_explain WHERE flag = 1 ORDER BY id")
						.execute()
						.flatMap(r -> r.map(ExampleSupport::formatRow)))
				.doOnNext(line -> ExampleSupport.println("EXPLAIN: " + line))
				.thenMany(conn.createStatement(
								"EXPLAIN ANALYZE SELECT id FROM ex_explain WHERE flag = 1")
						.execute()
						.flatMap(r -> r.map(ExampleSupport::formatRow)))
				.doOnNext(line -> ExampleSupport.println("EXPLAIN ANALYZE: " + line))
				.then(conn.close());
	}
}