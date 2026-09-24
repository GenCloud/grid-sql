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
package org.genfork.grid.examples.warmup;

import org.genfork.grid.examples.common.ExampleSupport;
import org.genfork.grid.sql.client.ConnectionFactory;
import org.genfork.grid.sql.client.RemoteConnectionFactory;
import reactor.core.publisher.Mono;

/**
 * Optional preheat: when {@code warmup=true}, {@link ConnectionFactory#warmup()} opens
 * {@code minConnections} into the idle pool (fail-fast connect/AUTH). The first
 * {@link ConnectionFactory#obtain()} also fills the min pool if needed — no gate without warmup.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.0
 */
public final class WarmupExample {
	private static final String URL_WARM =
			"grid://@127.0.0.1:15432/public?warmup=true&minConnections=1&maxConnections=2";
	private static final String SELECT_ONE = "SELECT 1 AS one";

	private WarmupExample() {
	}

	public static void main(String[] args) {
		ExampleSupport.println("=== 1) CORRECT: warmup=true + factory.warmup() then obtain ===");
		demoWithWarmup();

		ExampleSupport.println("=== 2) Programmatic RemoteConnectionFactory(warmup=true) ===");
		demoProgrammatic();

		ExampleSupport.println("=== 3) warmup=true but obtain() without prior warmup() — still OK ===");
		demoObtainWithoutPriorWarmup();

		ExampleSupport.println("=== 4) warmup=false: obtain fills min lazily ===");
		demoColdObtainAllowed();
	}

	private static void demoWithWarmup() {
		final ConnectionFactory factory = ConnectionFactory.fromUrl(URL_WARM);
		try {
			factory.warmup()
					.doOnSuccess(v -> ExampleSupport.println("warmup OK (min TCP pool open)"))
					.then(factory.obtain())
					.flatMap(conn -> conn.createStatement(SELECT_ONE)
							.fetchOne()
							.doOnNext(row -> ExampleSupport.println(
									"after warmup SELECT 1 -> " + row.get("one")))
							.then(conn.close()))
					.block();
		} finally {
			factory.dispose();
		}
	}

	private static void demoProgrammatic() {
		final RemoteConnectionFactory factory = new RemoteConnectionFactory(
				"127.0.0.1", 15432, "", "", 32, 2, "public", true);
		try {
			factory.warmup()
					.doOnSuccess(v -> ExampleSupport.println(
							"RemoteConnectionFactory.warmup() OK"))
					.then(factory.obtain())
					.flatMap(conn -> conn.createStatement(SELECT_ONE)
							.fetchOne()
							.doOnNext(row -> ExampleSupport.println(
									"programmatic SELECT 1 -> " + row.get("one")))
							.then(conn.close()))
					.block();
		} finally {
			factory.dispose();
		}
	}

	private static void demoObtainWithoutPriorWarmup() {
		ExampleSupport.runWithoutWarmup(URL_WARM, factory -> factory.obtain()
				.flatMap(conn -> conn.createStatement(SELECT_ONE)
						.fetchOne()
						.doOnNext(row -> ExampleSupport.println(
								"obtain without prior warmup OK -> " + row.get("one")))
						.then(conn.close()))
				.onErrorResume(err -> {
					ExampleSupport.println(
							"obtain failed (server down?): "
									+ err.getClass().getSimpleName()
									+ ": " + err.getMessage());
					return Mono.empty();
				}));
	}

	private static void demoColdObtainAllowed() {
		ExampleSupport.runWithoutWarmup(
				"grid://@127.0.0.1:15432/public?warmup=false&maxConnections=1",
				factory -> factory.obtain()
						.flatMap(conn -> conn.createStatement(SELECT_ONE)
								.fetchOne()
								.doOnNext(row -> ExampleSupport.println(
										"warmup=false lazy obtain OK -> " + row.get("one")))
								.then(conn.close()))
						.onErrorResume(err -> {
							ExampleSupport.println(
									"lazy obtain failed (server down?): "
											+ err.getClass().getSimpleName()
											+ ": " + err.getMessage());
							return Mono.empty();
						}));
	}
}
