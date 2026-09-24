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

import reactor.core.publisher.Flux;

/**
 * Acquires a READ_REPLICA connection per execute, then parks it (statement boundary rotate).
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
final class RoutingReadStatement extends AbstractBoundStatement {
	private final RemoteConnectionFactory readFactory;

	RoutingReadStatement(RemoteConnectionFactory readFactory, String sql) {
		super(sql);
		this.readFactory = readFactory;
	}

	@Override
	public Flux<Result> execute() {
		final Object[] binds = boundArgs();
		return Flux.usingWhen(
				readFactory.obtain(),
				conn -> {
					if (conn instanceof RemoteConnection remote) {
						return remote.execAutocommit(sql, binds, fetchWindowOrZero());
					}
					final Statement stmt = conn.createStatement(sql);
					if (binds != null) {
						for (int i = 0; i < binds.length; i++) {
							stmt.bind(i, binds[i]);
						}
					}
					final int fw = fetchWindowOrZero();
					if (fw > 0) {
						stmt.fetchWindow(fw);
					}
					return stmt.execute();
				},
				Connection::close,
				(conn, _) -> conn.close(),
				Connection::close
		);
	}
}
