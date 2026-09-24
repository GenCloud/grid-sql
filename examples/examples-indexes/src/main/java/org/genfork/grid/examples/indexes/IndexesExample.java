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
package org.genfork.grid.examples.indexes;

import org.genfork.grid.examples.common.ExampleSupport;
import org.genfork.grid.sql.client.Connection;
import reactor.core.publisher.Mono;

/**
 * DDL indexes: plain, UNIQUE, BITMAP (explicit only), then filtered SELECT.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.0
 */
public final class IndexesExample {
	private IndexesExample() {
	}

	public static void main(String[] args) {
		ExampleSupport.run(factory -> factory.obtain().flatMap(IndexesExample::run));
	}

	private static Mono<Void> createIndexIgnoreExists(Connection conn, String ddl) {
		return conn.executeUpdate(ddl).then().onErrorResume(err -> {
			ExampleSupport.println("index DDL skip: " + err.getMessage());
			return Mono.empty();
		});
	}

	private static Mono<Void> run(Connection conn) {
		return conn.executeUpdate(
						"CREATE TABLE IF NOT EXISTS ex_indexes ("
								+ "id BIGINT PRIMARY KEY, email VARCHAR, status VARCHAR, flag BIGINT)")
				.then(createIndexIgnoreExists(conn,
						"CREATE INDEX idx_ex_indexes_status ON ex_indexes (status)"))
				.then(createIndexIgnoreExists(conn,
						"CREATE UNIQUE INDEX idx_ex_indexes_email ON ex_indexes (email)"))
				.then(createIndexIgnoreExists(conn,
						"CREATE BITMAP INDEX idx_ex_indexes_flag ON ex_indexes (flag)"))
				.doOnSuccess(v -> ExampleSupport.println("indexes DDL ok"))
				.then(conn.executeUpdate(
						"UPSERT INTO ex_indexes (id, email, status, flag) VALUES (1, 'a@x', 'new', 1)"))
				.then(conn.executeUpdate(
						"UPSERT INTO ex_indexes (id, email, status, flag) VALUES (2, 'b@x', 'new', 0)"))
				.then(conn.executeUpdate(
						"UPSERT INTO ex_indexes (id, email, status, flag) VALUES (3, 'c@x', 'done', 1)"))
				.thenMany(conn.createStatement(
								"SELECT id, email, status, flag FROM ex_indexes "
										+ "WHERE status = ? AND flag = ? ORDER BY id")
						.bind(0, "new")
						.bind(1, 1L)
						.execute()
						.flatMap(r -> r.map(ExampleSupport::formatRow)))
				.doOnNext(line -> ExampleSupport.println("indexed filter: " + line))
				.then(conn.close());
	}
}