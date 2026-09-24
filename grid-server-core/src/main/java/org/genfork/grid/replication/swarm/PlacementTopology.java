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
package org.genfork.grid.replication.swarm;

import java.util.List;

/**
 * Snapshot of cluster topology for hierarchical placement search (DC → node/shard)
 * and optional remote voter-set fitness.
 *
 * @author: GenCloud
 * @date: 2026/06
 * @since: 1.0
 */
public record PlacementTopology(
		String localNodeId,
		String localDc,
		List<PeerEndpoint> peers,
		List<StreamPlacement> streams
) {
	public record PeerEndpoint(String id, String dc, PeerRole role) {
		public PeerEndpoint(String id, String dc) {
			this(id, dc, PeerRole.LEARNER);
		}
	}

	/**
	 * @param currentOwner null means local ownership
	 * @param affinityPin  non-null pin must be honored (skip migrate away)
	 * @param applyLag     peer apply lag hint for this stream (ops behind)
	 */
	public record StreamPlacement(
			String domainType,
			int shard,
			String currentOwner,
			String affinityPin,
			double applyLag
	) {
		public String streamKey() {
			return domainType + "#" + shard;
		}
	}
}
