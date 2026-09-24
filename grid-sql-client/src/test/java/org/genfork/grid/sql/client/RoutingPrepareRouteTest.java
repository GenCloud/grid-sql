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
package org.genfork.grid.sql.client;

import org.genfork.grid.sql.SqlRouteClassifier;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * RoutingConnection auto-caches PREPARE body route for EXECUTE.
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
class RoutingPrepareRouteTest {
	@Test
	void createStatementPrepareCachesRoute() {
		final RoutingConnection routing = new RoutingConnection(
				new NoopConnection(),
				null);
		routing.createStatement("PREPARE q AS SELECT id FROM t WHERE id = 1");
		assertEquals(SqlRouteClassifier.Route.READ, routing.prepareRouteCache().get("q"));
		routing.createStatement("DEALLOCATE q");
		assertNull(routing.prepareRouteCache().get("q"));
	}

	private static final class NoopConnection implements Connection {
		@Override
		public Mono<TxContext> begin() {
			return Mono.error(new UnsupportedOperationException());
		}

		@Override
		public Statement createStatement(String sql) {
			return new AbstractBoundStatement(sql) {
				@Override
				public Flux<Result> execute() {
					return Flux.empty();
				}
			};
		}

		@Override
		public Flux<Result> executeBatch(List<String> sqls) {
			return Flux.empty();
		}

		@Override
		public Mono<Void> close() {
			return Mono.empty();
		}
	}
}