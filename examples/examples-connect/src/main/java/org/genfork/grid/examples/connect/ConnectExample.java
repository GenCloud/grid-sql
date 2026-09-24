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
package org.genfork.grid.examples.connect;

import org.genfork.grid.examples.common.ExampleSupport;
import org.genfork.grid.sql.client.Connection;
import org.genfork.grid.sql.client.ConnectionFactory;
import org.genfork.grid.sql.client.RemoteConnectionFactory;
import org.genfork.grid.sql.client.ServerMeta;
import reactor.core.publisher.Mono;

/**
 * Connect via {@link ConnectionFactory#fromUrl(String)} and programmatic
 * {@link RemoteConnectionFactory}; always {@link ConnectionFactory#warmup()} first.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.0
 */
public final class ConnectExample {
	private static final String SELECT_ONE = "SELECT 1 AS one";

	private ConnectExample() {
	}

	public static void main(String[] args) {
		ExampleSupport.run(factory -> demoFromUrl(factory)
				.then(demoProgrammatic()));
	}

	private static Mono<Void> demoFromUrl(ConnectionFactory factory) {
		ExampleSupport.println("fromUrl -> " + ExampleSupport.gridUrl());
		return factory.obtain()
				.flatMap(conn -> printMeta("fromUrl", conn)
						.then(conn.createStatement(SELECT_ONE)
								.fetchOne()
								.doOnNext(row -> ExampleSupport.println(
										"fromUrl SELECT 1 -> " + row.get("one")))
								.then(conn.close())));
	}

	private static Mono<Void> demoProgrammatic() {
		final RemoteConnectionFactory factory = new RemoteConnectionFactory(
				"127.0.0.1", 15432, "", "", 32, 2, "public", true);
		ExampleSupport.println("RemoteConnectionFactory(..., warmup=true)");
		return factory.warmup()
				.then(factory.obtain())
				.flatMap(conn -> printMeta("programmatic", conn)
						.then(conn.createStatement(SELECT_ONE)
								.fetchOne()
								.doOnNext(row -> ExampleSupport.println(
										"programmatic SELECT 1 -> " + row.get("one")))
								.then(conn.close())))
				.doFinally(s -> factory.dispose());
	}

	private static Mono<Void> printMeta(String label, Connection conn) {
		final ServerMeta meta = conn.serverMeta();
		ExampleSupport.println(label + " ServerMeta: " + meta);
		return Mono.empty();
	}
}