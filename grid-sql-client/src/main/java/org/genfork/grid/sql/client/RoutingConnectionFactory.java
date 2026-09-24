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

import reactor.core.publisher.Mono;

/**
 * Internal two-pool facade: writer sticky PRIMARY + READ_REPLICA factory.
 * <p>
 * Apps use {@link ConnectionFactory#fromUrl(String)} — when the URL has {@code readEndpoints},
 * that entry returns this type. Do not construct manually unless testing.
 * <p>
 * {@link RoutingConnection#createStatement} auto-routes read-only SELECT/EXPLAIN via ANTLR
 * {@link org.genfork.grid.sql.SqlRouteClassifier}; DML/DDL/TX/FOR UPDATE stay on the writer.
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public record RoutingConnectionFactory(RemoteConnectionFactory writerFactory,
                                       RemoteConnectionFactory readFactory) implements ConnectionFactory {
	private static final String ERR_NO_READ_POOL =
			"readEndpoints required for RoutingConnection read API";

	public RoutingConnectionFactory(RemoteConnectionFactory writerFactory, RemoteConnectionFactory readFactory) {
		this.writerFactory = writerFactory;
		this.readFactory = readFactory;
		if (this.writerFactory == null) {
			throw new IllegalArgumentException("writerFactory required");
		}
	}

	/**
	 * Writer + read pool for a URL that already has {@code readEndpoints}.
	 * Prefer {@link ConnectionFactory#fromUrl(String)}.
	 */
	public static RoutingConnectionFactory fromUrl(String url) {
		final GridSqlUri u = GridSqlUri.parse(url);
		final RemoteConnectionFactory writer = RemoteConnectionFactory.fromUrl(url);
		final RemoteConnectionFactory reader = u.options().readEndpoints().isEmpty()
				? null
				: RemoteConnectionFactory.createReadFactory(url);
		return new RoutingConnectionFactory(writer, reader);
	}

	@Override
	public Mono<Connection> obtain() {
		return writerFactory.obtain()
				.map(writer -> new RoutingConnection(writer, readFactory));
	}

	@Override
	public Mono<Void> warmup() {
		final Mono<Void> writerWarm = writerFactory.warmup();
		if (readFactory == null) {
			return writerWarm;
		}
		return Mono.when(writerWarm, readFactory.warmup());
	}

	@Override
	public void dispose() {
		writerFactory.dispose();
		if (readFactory != null) {
			readFactory.dispose();
		}
	}

	static void requireReadPool(RemoteConnectionFactory readFactory) {
		if (readFactory == null) {
			throw new IllegalStateException(ERR_NO_READ_POOL);
		}
	}
}
