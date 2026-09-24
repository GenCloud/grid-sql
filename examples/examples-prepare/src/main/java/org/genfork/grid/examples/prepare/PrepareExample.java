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
package org.genfork.grid.examples.prepare;

import org.genfork.grid.examples.common.ExampleSupport;
import org.genfork.grid.sql.client.Connection;
import org.genfork.grid.sql.client.PreparedHandle;
import reactor.core.publisher.Mono;

/**
 * Server PREPARE via {@link Connection#prepare}, bind + execute, then deallocate.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.0
 */
public final class PrepareExample {
	private static final String DDL =
			"CREATE TABLE IF NOT EXISTS ex_prepare ("
					+ "id BIGINT PRIMARY KEY, name VARCHAR)";
	private static final String PREP_NAME = "ex_prep_by_id";
	private static final String PREP_BODY =
			"SELECT id, name FROM ex_prepare WHERE id = ?";
	private static final String SEED =
			"UPSERT INTO ex_prepare (id, name) VALUES (1, 'prepared')";

	private PrepareExample() {
	}

	public static void main(String[] args) {
		ExampleSupport.run(factory -> factory.obtain().flatMap(PrepareExample::run));
	}

	private static Mono<Void> run(Connection conn) {
		return conn.executeUpdate(DDL)
				.then(conn.executeUpdate(SEED))
				.then(conn.prepare(PREP_NAME, PREP_BODY))
				.flatMap(handle -> executeOnce(handle, 1L)
						.then(conn.deallocate(PREP_NAME))
						.doOnSuccess(v -> ExampleSupport.println(
								"deallocated " + handle.name())))
				.then(conn.close());
	}

	private static Mono<Void> executeOnce(PreparedHandle handle, long id) {
		ExampleSupport.println("EXECUTE " + handle.name() + " id=" + id);
		return handle.bind(0, id)
				.execute()
				.flatMap(r -> r.map(ExampleSupport::formatRow))
				.doOnNext(line -> ExampleSupport.println("row: " + line))
				.then();
	}
}
