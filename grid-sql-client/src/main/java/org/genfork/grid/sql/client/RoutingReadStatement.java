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
 * <p>
 * Auto-route statements may supply a writer {@link Connection} fallback when the read ring
 * has no complete-data endpoint ({@code applyLagStale} / incomplete tip).
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
final class RoutingReadStatement extends AbstractBoundStatement {
	private final RemoteConnectionFactory readFactory;
	private final Connection writerFallback;

	RoutingReadStatement(RemoteConnectionFactory readFactory, String sql) {
		this(readFactory, null, sql);
	}

	RoutingReadStatement(RemoteConnectionFactory readFactory, Connection writerFallback, String sql) {
		super(sql);
		this.readFactory = readFactory;
		this.writerFallback = writerFallback;
	}

	@Override
	public Flux<Result> execute() {
		final Object[] binds = boundArgs();
		final Flux<Result> onReplica = Flux.usingWhen(
				readFactory.obtain(),
				conn -> execOn(conn, binds),
				Connection::close,
				(conn, _) -> conn.close(),
				Connection::close
		);
		if (writerFallback == null) {
			return onReplica;
		}
		return onReplica.onErrorResume(ex -> {
			if (!ReplicaReadFallbackUtil.isIncompleteReplica(ex)) {
				return Flux.error(ex);
			}
			return execOn(writerFallback, binds);
		});
	}

	private Flux<Result> execOn(Connection conn, Object[] binds) {
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
	}
}
