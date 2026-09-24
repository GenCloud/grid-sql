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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ConnectionFactory#fromUrl} routing selection, {@code minConnections} URL parse,
 * and obtain without prior warmup (no must-warmup gate).
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
class ConnectionFactoryFromUrlTest {
	@Test
	void writerOnlyWhenNoReadEndpoints() {
		final ConnectionFactory factory = ConnectionFactory.fromUrl("grid://u:p@127.0.0.1:15432/public");
		assertInstanceOf(RemoteConnectionFactory.class, factory);
		factory.dispose();
	}

	@Test
	void routingWhenReadEndpointsPresent() {
		final ConnectionFactory factory = ConnectionFactory.fromUrl(
				"grid://u:p@127.0.0.1:15432/public?readEndpoints=127.0.0.1:15433");
		assertInstanceOf(RoutingConnectionFactory.class, factory);
		final RoutingConnectionFactory routing = (RoutingConnectionFactory) factory;
		assertTrue(routing.readFactory() != null);
		factory.dispose();
	}

	@Test
	void minConnectionsParsedAndClampedToMax() {
		final GridSqlUri u = GridSqlUri.parse(
				"grid://u:p@127.0.0.1:15432/public?minConnections=3&maxConnections=2");
		assertEquals(2, u.options().maxConnections());
		assertEquals(2, u.options().minConnections());
	}

	@Test
	void minConnectionsDefaultIsOne() {
		final ConnectionOptions opts = ConnectionOptions.defaults();
		assertEquals(1, opts.minConnections());
		assertEquals(1, opts.maxConnections());
	}

	@Test
	void obtainWithoutPriorWarmupDoesNotThrowWarmupGate() {
		final ConnectionFactory factory = ConnectionFactory.fromUrl(
				"grid://u:p@127.0.0.1:15432/public?warmup=true&minConnections=1&maxConnections=2");
		try {
			try {
				factory.obtain().block();
			} catch (IllegalStateException ex) {
				assertTrue(!ex.getMessage().contains("warmup() required"),
						() -> "unexpected warmup gate: " + ex.getMessage());
			} catch (RuntimeException ignored) {
				// connect refused / timeout — acceptable without a live node
			}
		} finally {
			factory.dispose();
		}
	}

	@Test
	void obtainWithoutWarmupOkWhenWarmupDisabled() {
		final RemoteConnectionFactory factory = new RemoteConnectionFactory(
				"127.0.0.1", 15432, "u", "p", 8, 1, "public", false);
		try {
			try {
				factory.obtain().block();
			} catch (IllegalStateException ex) {
				assertTrue(!ex.getMessage().contains("warmup() required"),
						() -> "unexpected warmup gate: " + ex.getMessage());
			} catch (RuntimeException ignored) {
				// connect refused / timeout — acceptable without a live node
			}
		} finally {
			factory.dispose();
		}
	}
}
