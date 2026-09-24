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
package org.genfork.grid.examples.batch;

import org.genfork.grid.examples.common.ExampleSupport;
import org.genfork.grid.sql.client.Connection;
import org.genfork.grid.sql.client.TxContext;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * {@link Connection#executeBatch} (each stmt commits alone) vs
 * {@link TxContext#executeBatch} (shared dirty buffer, one commit).
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.0
 */
public final class BatchExample {
	private static final String DDL =
			"CREATE TABLE IF NOT EXISTS ex_batch ("
					+ "id BIGINT PRIMARY KEY, name VARCHAR)";
	private static final String SELECT_ALL =
			"SELECT id, name FROM ex_batch ORDER BY id";

	private BatchExample() {
	}

	public static void main(String[] args) {
		ExampleSupport.run(factory -> factory.obtain().flatMap(BatchExample::run));
	}

	private static Mono<Void> run(Connection conn) {
		return conn.executeUpdate(DDL)
				.then(conn.executeBatch(List.of(
						"UPSERT INTO ex_batch (id, name) VALUES (1, 'ac-1')",
						"UPSERT INTO ex_batch (id, name) VALUES (2, 'ac-2')"))
						.concatMap(result -> result.getRowsUpdated().defaultIfEmpty(0L))
						.index()
						.doOnNext(t -> ExampleSupport.println(
								"AC batch[" + t.getT1() + "] rowsUpdated=" + t.getT2()))
						.then())
				.then(printAll(conn, "after AC batch"))
				.then(Mono.usingWhen(
						conn.begin(),
						tx -> tx.executeBatch(List.of(
										"UPSERT INTO ex_batch (id, name) VALUES (3, 'tx-3')",
										"UPSERT INTO ex_batch (id, name) VALUES (4, 'tx-4')"))
								.concatMap(result -> result.getRowsUpdated().defaultIfEmpty(0L))
								.index()
								.doOnNext(t -> ExampleSupport.println(
										"TX batch[" + t.getT1() + "] rowsUpdated=" + t.getT2()))
								.then(tx.commit()),
						TxContext::rollback))
				.then(printAll(conn, "after TX batch commit"))
				.then(conn.close());
	}

	private static Mono<Void> printAll(Connection conn, String label) {
		ExampleSupport.println("--- " + label + " ---");
		return conn.createStatement(SELECT_ALL)
				.execute()
				.flatMap(r -> r.map(ExampleSupport::formatRow))
				.doOnNext(ExampleSupport::println)
				.then();
	}
}
