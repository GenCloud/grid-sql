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
package org.genfork.grid.examples.streaming;

import org.genfork.grid.examples.common.ExampleSupport;
import org.genfork.grid.sql.client.Connection;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Stream rows with a small {@code fetchWindow}; cancel mid-stream via {@code take}.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.0
 */
public final class StreamingExample {
	private static final String DDL =
			"CREATE TABLE IF NOT EXISTS ex_streaming ("
					+ "id BIGINT PRIMARY KEY, payload VARCHAR)";
	private static final String SELECT_ALL =
			"SELECT id, payload FROM ex_streaming ORDER BY id";
	private static final int SEED_ROWS = 50;
	private static final int FETCH_WINDOW = 8;
	private static final int TAKE_ROWS = 12;

	private StreamingExample() {
	}

	public static void main(String[] args) {
		ExampleSupport.run(factory -> factory.obtain().flatMap(StreamingExample::run));
	}

	private static Mono<Void> run(Connection conn) {
		return conn.executeUpdate(DDL)
				.then(seed(conn))
				.then(Mono.defer(() -> {
					ExampleSupport.println("streaming take(" + TAKE_ROWS
							+ ") with fetchWindow=" + FETCH_WINDOW);
					return conn.createStatement(SELECT_ALL)
							.fetchWindow(FETCH_WINDOW)
							.execute()
							.flatMap(r -> r.map(ExampleSupport::formatRow))
							.take(TAKE_ROWS)
							.index()
							.doOnNext(t -> ExampleSupport.println(
									"#" + t.getT1() + " " + t.getT2()))
							.then();
				}))
				.doOnSuccess(v -> ExampleSupport.println(
						"cancelled after " + TAKE_ROWS + " rows (wire CANCEL)"))
				.then(conn.close());
	}

	private static Mono<Void> seed(Connection conn) {
		return Flux.range(1, SEED_ROWS)
				.concatMap(i -> conn.createStatement(
								"UPSERT INTO ex_streaming (id, payload) VALUES (?, ?)")
						.bind(0, i.longValue())
						.bind(1, "row-" + i)
						.executeUpdate())
				.then();
	}
}
