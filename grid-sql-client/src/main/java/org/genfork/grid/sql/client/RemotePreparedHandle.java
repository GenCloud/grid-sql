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

import org.genfork.grid.sql.SqlBinds;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link PreparedHandle} backed by SQL PREPARE / EXECUTE / DEALLOCATE on a {@link Connection}.
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
final class RemotePreparedHandle implements PreparedHandle {
	private final Connection connection;
	private final String name;
	private final TreeMap<Integer, Object> binds = new TreeMap<>();
	private final AtomicBoolean deallocated = new AtomicBoolean();

	RemotePreparedHandle(Connection connection, String name) {
		this.connection = connection;
		this.name = SqlClientSql.requireIdent(name);
	}

	@Override
	public String name() {
		return name;
	}

	@Override
	public PreparedHandle bind(int index, Object value) {
		if (index < 0) {
			throw new IllegalArgumentException("bind index must be >= 0");
		}
		binds.put(index, value);
		return this;
	}

	@Override
	public Flux<Result> execute() {
		if (deallocated.get()) {
			return Flux.error(new IllegalStateException("prepared statement deallocated: " + name));
		}
		final Statement stmt = connection.createStatement(SqlClientSql.executeSql(name));
		final Object[] args = SqlBinds.dense(binds);
		for (int i = 0; i < args.length; i++) {
			stmt.bind(i, args[i]);
		}
		return stmt.execute();
	}

	@Override
	public Mono<Void> deallocate() {
		if (!deallocated.compareAndSet(false, true)) {
			return Mono.empty();
		}
		return connection.executeUpdate(SqlClientSql.deallocateSql(name)).then();
	}
}