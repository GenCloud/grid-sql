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
package org.genfork.grid.examples.pin;

import org.genfork.grid.examples.common.ExampleSupport;
import org.genfork.grid.sql.client.Connection;
import reactor.core.publisher.Mono;

/**
 * Overlay PIN / UNPIN via {@link Connection#pin} / {@link Connection#unpin}.
 * <p>
 * Requires {@code grid.overlay.enabled=true} on the server. Default {@code capacity} /
 * compose labs ship overlay off — then the server returns
 * {@code overlay is disabled}; enable overlay to see a successful pin.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.0
 */
public final class PinExample {
	private static final long PIN_TTL_MS = 60_000L;
	private static final String PIN_QOS = "examples-pin";

	private PinExample() {
	}

	public static void main(String[] args) {
		ExampleSupport.run(factory -> factory.obtain().flatMap(PinExample::run));
	}

	private static Mono<Void> run(Connection conn) {
		return conn.executeUpdate(
						"CREATE TABLE IF NOT EXISTS ex_pin ("
								+ "id BIGINT PRIMARY KEY, name VARCHAR)")
				.then(conn.executeUpdate(
						"UPSERT INTO ex_pin (id, name) VALUES (9001, 'vip')"))
				.then(conn.pin("ex_pin", 9001L, PIN_TTL_MS, PIN_QOS)
						.doOnSuccess(v -> ExampleSupport.println(
								"PIN KEY ex_pin 9001 OK (overlay enabled)"))
						.onErrorResume(err -> {
							ExampleSupport.println(
									"PIN failed (enable grid.overlay.enabled): "
											+ err.getMessage());
							return Mono.empty();
						}))
				.then(conn.unpin("ex_pin", 9001L)
						.doOnSuccess(v -> ExampleSupport.println("UNPIN OK"))
						.onErrorResume(err -> {
							ExampleSupport.println(
									"UNPIN failed: " + err.getMessage());
							return Mono.empty();
						}))
				.then(conn.close());
	}
}