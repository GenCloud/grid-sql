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
package org.genfork.grid.sql.client.sync;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.genfork.grid.sql.client.HostEndpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SyncConnectionFactory} TCP floor + timeout without a live peer.
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
class SyncConnectionFactoryTest {
	@Test
	void tcpFloorAppliedOnSharedOptions() {
		final SyncConnectionFactory factory = SyncConnectionFactory.shared(
				List.of(new HostEndpoint("127.0.0.1", 15432)),
				"u",
				"p",
				org.genfork.grid.sql.client.ConnectionOptions.builder()
						.minConnections(1)
						.maxConnections(1)
						.build(),
				"public");
		assertTrue(factory.minConnections() >= SyncConnectionFactory.MIN_TCP_CHANNELS);
		assertTrue(factory.maxConnections() >= SyncConnectionFactory.MIN_TCP_CHANNELS);
		assertEquals(Duration.ofSeconds(30), factory.timeout());
	}
}
