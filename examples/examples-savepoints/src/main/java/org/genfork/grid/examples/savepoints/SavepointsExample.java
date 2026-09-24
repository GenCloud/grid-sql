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
package org.genfork.grid.examples.savepoints;

import org.genfork.grid.examples.common.ExampleSupport;
import org.genfork.grid.sql.client.Connection;
import org.genfork.grid.sql.client.TxContext;
import reactor.core.publisher.Mono;

/**
 * In-TX savepoints: keep first mutation, roll second back to savepoint, then commit.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.0
 */
public final class SavepointsExample {
	private static final String DDL =
			"CREATE TABLE IF NOT EXISTS ex_savepoints ("
					+ "id BIGINT PRIMARY KEY, v BIGINT)";
	private static final String SEED_1 =
			"UPSERT INTO ex_savepoints (id, v) VALUES (1, 0)";
	private static final String SEED_2 =
			"UPSERT INTO ex_savepoints (id, v) VALUES (2, 0)";
	private static final String UPDATE_1 =
			"UPDATE ex_savepoints SET v = 1 WHERE id = 1";
	private static final String UPDATE_2 =
			"UPDATE ex_savepoints SET v = 2 WHERE id = 2";
	private static final String SELECT_ALL =
			"SELECT id, v FROM ex_savepoints ORDER BY id";
	private static final String SP_NAME = "s1";

	private SavepointsExample() {
	}

	public static void main(String[] args) {
		ExampleSupport.run(factory -> factory.obtain().flatMap(SavepointsExample::run));
	}

	private static Mono<Void> run(Connection conn) {
		return conn.executeUpdate(DDL)
				.then(conn.executeUpdate(SEED_1))
				.then(conn.executeUpdate(SEED_2))
				.then(Mono.usingWhen(
						conn.begin(),
						tx -> tx.createStatement(UPDATE_1).executeUpdate()
								.then(tx.savepoint(SP_NAME))
								.flatMap(sp -> tx.createStatement(UPDATE_2).executeUpdate()
										.then(tx.rollbackTo(sp))
										.doOnSuccess(v -> ExampleSupport.println(
												"rolled back to " + SP_NAME))
										.then(tx.release(sp))
										.doOnSuccess(v -> ExampleSupport.println(
												"released " + SP_NAME)))
								.then(tx.commit()),
						TxContext::rollback))
				.then(printAll(conn))
				.then(conn.close());
	}

	private static Mono<Void> printAll(Connection conn) {
		ExampleSupport.println("--- after savepoint rollback + commit ---");
		return conn.createStatement(SELECT_ALL)
				.execute()
				.flatMap(r -> r.map(ExampleSupport::formatRow))
				.doOnNext(ExampleSupport::println)
				.then();
	}
}
