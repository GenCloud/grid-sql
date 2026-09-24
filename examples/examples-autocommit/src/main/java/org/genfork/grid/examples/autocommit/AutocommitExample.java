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
package org.genfork.grid.examples.autocommit;

import org.genfork.grid.examples.common.ExampleSupport;
import org.genfork.grid.sql.client.Connection;
import reactor.core.publisher.Mono;

/**
 * Autocommit path: DDL, UPSERT with binds, SELECT with binds.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.0
 */
public final class AutocommitExample {
	private static final String DDL =
			"CREATE TABLE IF NOT EXISTS ex_autocommit ("
					+ "id BIGINT PRIMARY KEY, name VARCHAR, balance BIGINT)";
	private static final String UPSERT =
			"UPSERT INTO ex_autocommit (id, name, balance) VALUES (?, ?, ?)";
	private static final String SELECT =
			"SELECT id, name, balance FROM ex_autocommit WHERE id = ?";

	private AutocommitExample() {
	}

	public static void main(String[] args) {
		ExampleSupport.run(factory -> factory.obtain().flatMap(AutocommitExample::run));
	}

	private static Mono<Void> run(Connection conn) {
		return conn.executeUpdate(DDL)
				.doOnNext(n -> ExampleSupport.println("DDL rowsUpdated=" + n))
				.then(conn.createStatement(UPSERT)
						.bind(0, 1L)
						.bind(1, "alice")
						.bind(2, 100L)
						.executeUpdate())
				.doOnNext(n -> ExampleSupport.println("UPSERT rowsUpdated=" + n))
				.thenMany(conn.createStatement(SELECT)
						.bind(0, 1L)
						.execute()
						.flatMap(r -> r.map(ExampleSupport::formatRow)))
				.doOnNext(line -> ExampleSupport.println("row: " + line))
				.then(conn.close());
	}
}
