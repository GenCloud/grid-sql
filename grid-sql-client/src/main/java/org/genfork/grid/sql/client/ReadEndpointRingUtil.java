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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Build the READ_REPLICA least-inflight ring: explicit {@code readEndpoints} plus authority hosts.
 * <p>
 * Prefer listed replicas first (offload writer), then append authority / proposer hosts so
 * fan-out can use every synced voter that admits {@code SessionRole#READ_REPLICA}.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
public final class ReadEndpointRingUtil {
	/** Floor when caller does not impose a JDBC / tooling TCP minimum. */
	public static final int DEFAULT_MIN_CONNECTIONS_FLOOR = 1;

	private ReadEndpointRingUtil() {
	}

	/**
	 * Deduped ring: {@code readEndpoints} order first, then authority hosts not already present.
	 */
	public static List<HostEndpoint> mergeReadRing(
			List<HostEndpoint> readEndpoints,
			List<HostEndpoint> authority
	) {
		final Set<String> seen = new LinkedHashSet<>();
		final List<HostEndpoint> out = new ArrayList<>();
		appendUnique(out, seen, readEndpoints);
		appendUnique(out, seen, authority);
		return List.copyOf(out);
	}

	/**
	 * At least one TCP per ring member so least-inflight can land on every endpoint.
	 */
	public static int readPoolCapacity(int maxReadConnections, int ringSize) {
		final int configured = Math.max(1, maxReadConnections);
		final int ring = Math.max(1, ringSize);
		return Math.max(configured, ring);
	}

	/**
	 * READ_REPLICA {@link ConnectionOptions}: copy product knobs, pin ring, size pool for fan-out.
	 * <p>
	 * {@code minConnectionsFloor} raises the TCP floor (JDBC tooling uses
	 * {@code SyncConnectionFactory.MIN_TCP_CHANNELS}); product {@code createReadFactory} uses
	 * {@link #DEFAULT_MIN_CONNECTIONS_FLOOR}. Always warms the read pool (least-inflight needs
	 * live channels). Propagates {@code fetchWindow} and timeouts from {@code src}.
	 */
	public static ConnectionOptions buildReadReplicaOptions(
			ConnectionOptions src,
			List<HostEndpoint> readEps,
			int minConnectionsFloor
	) {
		Objects.requireNonNull(src, "src");
		Objects.requireNonNull(readEps, "readEps");
		if (readEps.isEmpty()) {
			throw new IllegalArgumentException("readEndpoints required");
		}
		final int ring = readEps.size();
		final int readCap = readPoolCapacity(src.maxReadConnections(), ring);
		final int floor = Math.max(DEFAULT_MIN_CONNECTIONS_FLOOR, minConnectionsFloor);
		final int minConn = Math.max(Math.max(floor, src.minConnections()), Math.min(readCap, ring));
		final int maxConn = Math.max(minConn, readCap);
		return ConnectionOptions.builder()
				.minConnections(minConn)
				.maxConnections(maxConn)
				.maxTxContexts(src.maxTxContexts())
				.warmup(true)
				.execTimeout(src.execTimeout())
				.connectTimeout(src.connectTimeout())
				.readTimeout(src.readTimeout())
				.writeTimeout(src.writeTimeout())
				.maxRetries(src.maxRetries())
				.retryDelay(src.retryDelay())
				.retryMode(src.retryMode())
				.timezone(src.timezone())
				.readPreference(ReadPreference.REPLICA)
				.staleReadPolicy(src.staleReadPolicy())
				.maxReadConnections(maxConn)
				.fetchWindow(src.fetchWindow())
				.readEndpoints(readEps)
				.build();
	}

	private static void appendUnique(
			List<HostEndpoint> out,
			Set<String> seen,
			List<HostEndpoint> source
	) {
		if (source == null || source.isEmpty()) {
			return;
		}
		for (HostEndpoint ep : source) {
			if (ep == null) {
				continue;
			}
			final String key = ep.host() + ":" + ep.port();
			if (seen.add(key)) {
				out.add(ep);
			}
		}
	}
}
