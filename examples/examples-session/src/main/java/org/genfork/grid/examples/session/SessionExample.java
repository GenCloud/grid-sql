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
package org.genfork.grid.examples.session;

import org.genfork.grid.examples.common.ExampleSupport;
import org.genfork.grid.sql.client.Connection;
import reactor.core.publisher.Mono;

/**
 * Session helpers: {@code setSchema}, {@code setTimezone}, URL timeouts / fetchWindow.
 * <p>
 * Named {@code bind(String, …)} is not supported on the product client — use positional {@code ?}.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.0
 */
public final class SessionExample {
	private static final String URL =
			"grid://@127.0.0.1:15432/public"
					+ "?warmup=true&maxConnections=1&maxTxContexts=32"
					+ "&connectTimeoutMs=2000&fetchWindow=32&timezone=UTC";

	private SessionExample() {
	}

	public static void main(String[] args) {
		ExampleSupport.println("URL: " + URL);
		ExampleSupport.run(URL, factory -> factory.obtain().flatMap(SessionExample::run));
	}

	private static Mono<Void> run(Connection conn) {
		return conn.setTimezone("UTC")
				.doOnSuccess(v -> ExampleSupport.println("setTimezone(UTC) OK"))
				.then(conn.setSchema("public"))
				.doOnSuccess(v -> ExampleSupport.println("setSchema(public) OK"))
				.then(conn.executeUpdate(
						"CREATE TABLE IF NOT EXISTS ex_session ("
								+ "id BIGINT PRIMARY KEY, name VARCHAR)"))
				.then(conn.createStatement(
								"UPSERT INTO ex_session (id, name) VALUES (?, ?)")
						.bind(0, 1L)
						.bind(1, "positional")
						.executeUpdate())
				.doOnNext(n -> ExampleSupport.println("positional UPSERT rowsUpdated=" + n))
				.then(Mono.defer(() -> {
					try {
						conn.createStatement("SELECT 1").bind("id", 1L);
						ExampleSupport.println("UNEXPECTED: named bind accepted");
						return Mono.<Void>empty();
					} catch (UnsupportedOperationException ex) {
						ExampleSupport.println(
								"named bind rejected (expected): " + ex.getMessage());
						return Mono.empty();
					}
				}))
				.thenMany(conn.createStatement(
								"SELECT id, name FROM ex_session WHERE id = ?")
						.bind(0, 1L)
						.fetchWindow(16)
						.execute()
						.flatMap(r -> r.map(ExampleSupport::formatRow)))
				.doOnNext(line -> ExampleSupport.println("row: " + line))
				.then(conn.close());
	}
}