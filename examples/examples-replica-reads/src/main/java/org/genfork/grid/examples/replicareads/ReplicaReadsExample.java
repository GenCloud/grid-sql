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
package org.genfork.grid.examples.replicareads;

import org.genfork.grid.examples.common.ExampleSupport;
import org.genfork.grid.sql.client.Connection;
import reactor.core.publisher.Mono;

/**
 * Routing URL: DDL/UPSERT stay on the writer; SELECT can use {@code readPreference=REPLICA}.
 * <p>
 * Requires {@code examples/compose/1dc-n2} (or primary+replica profiles). Override with
 * {@link ExampleSupport#ENV_GRID_URL}.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.0
 */
public final class ReplicaReadsExample {
	private static final String DDL =
			"CREATE TABLE IF NOT EXISTS ex_replica_reads ("
					+ "id BIGINT PRIMARY KEY, name VARCHAR)";
	private static final String UPSERT =
			"UPSERT INTO ex_replica_reads (id, name) VALUES (?, ?)";
	private static final String SELECT =
			"SELECT id, name FROM ex_replica_reads WHERE id = ?";

	private ReplicaReadsExample() {
	}

	public static void main(String[] args) {
		ExampleSupport.println("GRID_URL default for this example: "
				+ ExampleSupport.DEFAULT_REPLICA_URL);
		ExampleSupport.run(ExampleSupport.DEFAULT_REPLICA_URL,
				factory -> factory.obtain().flatMap(ReplicaReadsExample::run));
	}

	private static Mono<Void> run(Connection conn) {
		ExampleSupport.println("ServerMeta after connect: " + conn.serverMeta());
		return conn.executeUpdate(DDL)
				.doOnNext(n -> ExampleSupport.println("DDL (writer) rowsUpdated=" + n))
				.then(conn.createStatement(UPSERT)
						.bind(0, 1L)
						.bind(1, "via-writer")
						.executeUpdate())
				.doOnNext(n -> ExampleSupport.println("UPSERT (writer) rowsUpdated=" + n))
				.thenMany(conn.createStatement(SELECT)
						.bind(0, 1L)
						.execute()
						.flatMap(r -> r.map(ExampleSupport::formatRow)))
				.doOnNext(line -> ExampleSupport.println("SELECT (may be replica): " + line))
				.then(conn.close());
	}
}
