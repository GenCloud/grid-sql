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
package org.genfork.grid.examples.haurl;

import org.genfork.grid.examples.common.ExampleSupport;
import org.genfork.grid.sql.client.Connection;
import reactor.core.publisher.Mono;

/**
 * Multi-host authority is a sticky writer candidate list (not write load balancing).
 * Prefer {@code examples/compose/1dc-n2} or primary+replica profiles.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.0
 */
public final class HaUrlExample {
	private HaUrlExample() {
	}

	public static void main(String[] args) {
		ExampleSupport.println("HA URL: " + ExampleSupport.DEFAULT_HA_URL);
		ExampleSupport.run(ExampleSupport.DEFAULT_HA_URL,
				factory -> factory.obtain().flatMap(HaUrlExample::run));
	}

	private static Mono<Void> run(Connection conn) {
		ExampleSupport.println("ServerMeta: " + conn.serverMeta());
		return conn.executeUpdate(
						"CREATE TABLE IF NOT EXISTS ex_ha ("
								+ "id BIGINT PRIMARY KEY, name VARCHAR)")
				.then(conn.createStatement(
								"UPSERT INTO ex_ha (id, name) VALUES (?, ?)")
						.bind(0, 1L)
						.bind(1, "sticky-writer")
						.executeUpdate())
				.doOnNext(n -> ExampleSupport.println("UPSERT on sticky writer rowsUpdated=" + n))
				.thenMany(conn.createStatement(
								"SELECT id, name FROM ex_ha WHERE id = ?")
						.bind(0, 1L)
						.execute()
						.flatMap(r -> r.map(ExampleSupport::formatRow)))
				.doOnNext(line -> ExampleSupport.println("row: " + line))
				.doOnComplete(() -> ExampleSupport.println(
						"ServerMeta after work: " + conn.serverMeta()))
				.then(conn.close());
	}
}