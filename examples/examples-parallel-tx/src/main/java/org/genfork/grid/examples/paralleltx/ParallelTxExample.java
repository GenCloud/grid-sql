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
package org.genfork.grid.examples.paralleltx;

import org.genfork.grid.examples.common.ExampleSupport;
import org.genfork.grid.sql.client.Connection;
import org.genfork.grid.sql.client.TxContext;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Several independent {@link Connection#begin()} calls on one TCP (not N sockets).
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.0
 */
public final class ParallelTxExample {
	private static final String DDL =
			"CREATE TABLE IF NOT EXISTS ex_parallel_tx ("
					+ "id BIGINT PRIMARY KEY, name VARCHAR)";
	private static final String SELECT_ALL =
			"SELECT id, name FROM ex_parallel_tx ORDER BY id";
	private static final int PARALLEL = 4;

	private ParallelTxExample() {
	}

	public static void main(String[] args) {
		ExampleSupport.run(factory -> factory.obtain().flatMap(ParallelTxExample::run));
	}

	private static Mono<Void> run(Connection conn) {
		return conn.executeUpdate(DDL)
				.then(Flux.range(1, PARALLEL)
						.flatMap(i -> Mono.usingWhen(
								conn.begin(),
								tx -> upsertOne(tx, i.longValue())
										.then(tx.commit()),
								TxContext::rollback))
						.then())
				.then(printAll(conn))
				.then(conn.close());
	}

	private static Mono<Long> upsertOne(TxContext tx, long id) {
		ExampleSupport.println("TX upsert id=" + id + " handle=" + tx.prepareHandle());
		return tx.createStatement(
						"UPSERT INTO ex_parallel_tx (id, name) VALUES (?, ?)")
				.bind(0, id)
				.bind(1, "p-" + id)
				.executeUpdate();
	}

	private static Mono<Void> printAll(Connection conn) {
		ExampleSupport.println("--- after parallel commits ---");
		return conn.createStatement(SELECT_ALL)
				.execute()
				.flatMap(r -> r.map(ExampleSupport::formatRow))
				.doOnNext(ExampleSupport::println)
				.then();
	}
}
