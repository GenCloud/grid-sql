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
 * Factory for SQL {@link Connection}s (embedded or remote).
 * Remote implementations reuse a shared Netty channel; prefer one factory per peer.
 * <p>
 * Canonical URL entry: {@link #fromUrl(String)}. When the URL has {@code readEndpoints},
 * returns a routing factory (writer + READ_REPLICA pool); otherwise a writer-only pool.
 * Apps do not choose {@code RoutingConnectionFactory} explicitly.
 * <p>
 * Use {@link #obtain()} to borrow a pooled TCP channel. Optional {@link #warmup()} preheats
 * {@link ConnectionOptions#minConnections()} when {@code warmup=true}; the first
 * {@code obtain()} also fills the min pool if needed.
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public interface ConnectionFactory {
	/**
	 * Borrow a live {@link Connection} from the pool (warm min fill + idle / least-loaded /
	 * lazy open up to {@link ConnectionOptions#maxConnections()}).
	 */
	Mono<Connection> obtain();

	/**
	 * Optional connect preheat (no-op by default).
	 * <p>
	 * When {@link ConnectionOptions#warmup()} is {@code true} on a
	 * {@link RemoteConnectionFactory}, opens {@link ConnectionOptions#minConnections()}
	 * channels into the idle pool. Does not gate {@link #obtain()}.
	 */
	default Mono<Void> warmup() {
		return Mono.empty();
	}

	/**
	 * Release pools / ELG (no-op by default).
	 */
	default void dispose() {
	}

	/**
	 * Parse {@code grid://…} and open the right factory: routing when {@code readEndpoints}
	 * is present, else writer-only {@link RemoteConnectionFactory}.
	 */
	static ConnectionFactory fromUrl(String url) {
		final GridSqlUri parsed = GridSqlUri.parse(url);
		if (parsed.options().readEndpoints().isEmpty()) {
			return RemoteConnectionFactory.fromUrl(url);
		}
		return RoutingConnectionFactory.fromUrl(url);
	}
}
