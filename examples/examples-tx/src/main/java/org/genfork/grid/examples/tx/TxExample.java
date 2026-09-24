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
package org.genfork.grid.examples.tx;

import org.genfork.grid.examples.common.ExampleSupport;
import org.genfork.grid.sql.client.Connection;
import org.genfork.grid.sql.client.TxContext;
import reactor.core.publisher.Mono;

/**
 * Explicit TX via {@code Mono.usingWhen}: successful transfer commit, then forced rollback.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.0
 */
public final class TxExample {
	private static final String DDL =
			"CREATE TABLE IF NOT EXISTS ex_tx ("
					+ "id BIGINT PRIMARY KEY, name VARCHAR, balance BIGINT)";
	private static final String SEED_A =
			"UPSERT INTO ex_tx (id, name, balance) VALUES (1, 'alice', 100)";
	private static final String SEED_B =
			"UPSERT INTO ex_tx (id, name, balance) VALUES (2, 'bob', 50)";
	private static final String DEBIT =
			"UPDATE ex_tx SET balance = balance - ? WHERE id = ?";
	private static final String CREDIT =
			"UPDATE ex_tx SET balance = balance + ? WHERE id = ?";
	private static final String SELECT_ALL =
			"SELECT id, name, balance FROM ex_tx ORDER BY id";
	private static final long TRANSFER = 10L;
	private static final String ERR_FORCE_ROLLBACK = "forced failure after debit";

	private TxExample() {
	}

	public static void main(String[] args) {
		ExampleSupport.run(factory -> factory.obtain().flatMap(TxExample::run));
	}

	private static Mono<Void> run(Connection conn) {
		return conn.executeUpdate(DDL)
				.then(conn.executeUpdate(SEED_A))
				.then(conn.executeUpdate(SEED_B))
				.then(printBalances(conn, "before transfer"))
				.then(Mono.usingWhen(
						conn.begin(),
						tx -> tx.createStatement(DEBIT)
								.bind(0, TRANSFER)
								.bind(1, 1L)
								.executeUpdate()
								.then(tx.createStatement(CREDIT)
										.bind(0, TRANSFER)
										.bind(1, 2L)
										.executeUpdate())
								.then(tx.commit()),
						TxContext::rollback))
				.then(printBalances(conn, "after commit"))
				.then(Mono.usingWhen(
						conn.begin(),
						tx -> tx.createStatement(DEBIT)
								.bind(0, TRANSFER)
								.bind(1, 1L)
								.executeUpdate()
								.then(Mono.error(new IllegalStateException(ERR_FORCE_ROLLBACK)))
								.then(tx.commit()),
						TxContext::rollback)
						.onErrorResume(err -> {
							ExampleSupport.println("expected rollback: " + err.getMessage());
							return Mono.empty();
						}))
				.then(printBalances(conn, "after rollback"))
				.then(conn.close());
	}

	private static Mono<Void> printBalances(Connection conn, String label) {
		ExampleSupport.println("--- " + label + " ---");
		return conn.createStatement(SELECT_ALL)
				.execute()
				.flatMap(r -> r.map(ExampleSupport::formatRow))
				.doOnNext(line -> ExampleSupport.println(line))
				.then();
	}
}
