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

import java.util.ArrayList;
import java.util.List;

/**
 * Shared filters for placement search / migrate emit (avoid GA over already-shed streams).
 *
 * @author: GenCloud
 * @date: 2026/06
 * @since: 1.0
 */
public final class PlacementStreamFilters {
	private PlacementStreamFilters() {
	}

	/**
	 * Streams that may still need migrate search: not affinity-pinned to local, and not already
	 * owned by a remote peer with a satisfied pin (settled shed).
	 */
	public static List<PlacementTopology.StreamPlacement> needingMigrateSearch(PlacementTopology topology) {
		final List<PlacementTopology.StreamPlacement> out = new ArrayList<>();
		if (topology == null || topology.streams() == null) {
			return out;
		}
		final String local = topology.localNodeId();
		for (PlacementTopology.StreamPlacement stream : topology.streams()) {
			if (stream == null || stream.domainType() == null) {
				continue;
			}
			if (isSettledOnPeer(stream, local)) {
				continue;
			}
			out.add(stream);
		}
		return out;
	}

	/**
	 * {@code true} when ownership already sits on a remote peer and affinity (if any) is satisfied.
	 */
	public static boolean isSettledOnPeer(PlacementTopology.StreamPlacement stream, String localNodeId) {
		if (stream == null) {
			return true;
		}
		final String pin = stream.affinityPin();
		if (pin != null && pin.equals(localNodeId)) {
			// Local pin — never migrate away; treat as non-movable settled.
			return true;
		}
		final String owner = stream.currentOwner() == null ? localNodeId : stream.currentOwner();
		if (owner == null || owner.equals(localNodeId)) {
			return false;
		}
		return pin == null || pin.equals(owner);
	}
}