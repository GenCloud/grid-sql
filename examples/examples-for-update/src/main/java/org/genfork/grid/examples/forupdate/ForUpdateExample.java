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
package org.genfork.grid.examples.forupdate;

import org.genfork.grid.examples.common.ExampleSupport;
import org.genfork.grid.sql.client.Connection;
import org.genfork.grid.sql.client.TxContext;
import reactor.core.publisher.Mono;

/**
 * Writer-only row locks: {@code FOR UPDATE} then peer {@code FOR UPDATE SKIP LOCKED}.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.0
 */
public final class ForUpdateExample {
	private ForUpdateExample() {
	}

	public static void main(String[] args) {
		ExampleSupport.run(factory -> factory.obtain().flatMap(ForUpdateExample::run));
	}

	private static Mono<Void> run(Connection conn) {
		return conn.executeUpdate(
						"CREATE TABLE IF NOT EXISTS ex_outbox ("
								+ "id BIGINT PRIMARY KEY, state VARCHAR, payload VARCHAR)")
				.then(conn.executeUpdate(
						"CREATE INDEX idx_ex_outbox_state ON ex_outbox (state)")
						.then()
						.onErrorResume(err -> {
							ExampleSupport.println("index DDL skip: " + err.getMessage());
							return Mono.empty();
						}))
				.then(conn.executeUpdate(
						"UPSERT INTO ex_outbox (id, state, payload) VALUES (1, 'new', 'a')"))
				.then(conn.executeUpdate(
						"UPSERT INTO ex_outbox (id, state, payload) VALUES (2, 'new', 'b')"))
				.then(conn.executeUpdate(
						"UPSERT INTO ex_outbox (id, state, payload) VALUES (3, 'done', 'c')"))
				.then(Mono.usingWhen(
						conn.begin(),
						tx1 -> Mono.usingWhen(
								conn.begin(),
								tx2 -> lockThenSkip(tx1, tx2),
								TxContext::rollback),
						TxContext::rollback))
				.then(conn.close());
	}

	private static Mono<Void> lockThenSkip(TxContext tx1, TxContext tx2) {
		return tx1.createStatement(
						"SELECT id, payload FROM ex_outbox WHERE state = 'new' "
								+ "ORDER BY id FOR UPDATE")
				.execute()
				.flatMap(r -> r.map(ExampleSupport::formatRow))
				.doOnNext(line -> ExampleSupport.println("TX1 FOR UPDATE: " + line))
				.thenMany(tx2.createStatement(
								"SELECT id, payload FROM ex_outbox WHERE state = 'new' "
										+ "ORDER BY id FOR UPDATE SKIP LOCKED")
						.execute()
						.flatMap(r -> r.map(ExampleSupport::formatRow)))
				.doOnNext(line -> ExampleSupport.println("TX2 SKIP LOCKED: " + line))
				.switchIfEmpty(Mono.fromRunnable(() -> ExampleSupport.println(
						"TX2 SKIP LOCKED: (no rows — all locked by TX1)")))
				.then(tx1.createStatement(
								"UPDATE ex_outbox SET state = 'done' WHERE state = 'new'")
						.executeUpdate())
				.doOnNext(n -> ExampleSupport.println("TX1 mark done rowsUpdated=" + n))
				.then(tx1.commit())
				.then(tx2.commit());
	}
}