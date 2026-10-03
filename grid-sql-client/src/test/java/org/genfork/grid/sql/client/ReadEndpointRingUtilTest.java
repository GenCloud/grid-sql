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

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Authority ∪ readEndpoints ring for READ_REPLICA fan-out.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
public class ReadEndpointRingUtilTest {

	@Test
	void mergesAuthorityAfterReadEndpoints() {
		final List<HostEndpoint> ring = ReadEndpointRingUtil.mergeReadRing(
				List.of(new HostEndpoint("127.0.0.1", 15433)),
				List.of(new HostEndpoint("127.0.0.1", 15432), new HostEndpoint("127.0.0.1", 15433)));
		assertEquals(2, ring.size());
		assertEquals(15433, ring.get(0).port());
		assertEquals(15432, ring.get(1).port());
	}

	@Test
	void poolCapacityAtLeastRingSize() {
		assertEquals(2, ReadEndpointRingUtil.readPoolCapacity(1, 2));
		assertEquals(4, ReadEndpointRingUtil.readPoolCapacity(4, 2));
	}

	@Test
	void createReadFactoryIncludesAuthorityInRing() {
		final RemoteConnectionFactory f = RemoteConnectionFactory.createReadFactory(
				"grid://u:p@127.0.0.1:15432/public?readPreference=REPLICA&readEndpoints=127.0.0.1:15433");
		try {
			assertEquals(2, f.endpoints().size());
			assertEquals(15433, f.endpoints().get(0).port());
			assertEquals(15432, f.endpoints().get(1).port());
			assertEquals(2, f.maxConnections());
		} finally {
			f.dispose();
		}
	}

	@Test
	void buildReadReplicaOptionsPropagatesFetchWindow() {
		final ConnectionOptions src = ConnectionOptions.builder()
				.minConnections(1)
				.maxConnections(1)
				.maxReadConnections(1)
				.fetchWindow(64)
				.readEndpoints(List.of(new HostEndpoint("127.0.0.1", 15433)))
				.build();
		final List<HostEndpoint> ring = ReadEndpointRingUtil.mergeReadRing(
				src.readEndpoints(), List.of(new HostEndpoint("127.0.0.1", 15432)));
		final ConnectionOptions read = ReadEndpointRingUtil.buildReadReplicaOptions(
				src, ring, ReadEndpointRingUtil.DEFAULT_MIN_CONNECTIONS_FLOOR);
		assertEquals(64, read.fetchWindow());
		assertEquals(2, read.maxConnections());
		assertEquals(ReadPreference.REPLICA, read.readPreference());
	}
}