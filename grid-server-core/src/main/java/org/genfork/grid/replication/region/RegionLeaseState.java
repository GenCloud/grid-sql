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
package org.genfork.grid.replication.region;

/**
 * Local durable view of region role + epoch.
 *
 * @author: GenCloud
 * @date: 2026/06
 * @since: 1.0
 */
public record RegionLeaseState(
		RegionRole role,
		long epoch,
		String claimedByNodeId,
		String claimedByDc
) {
	public static final long INITIAL_EPOCH = 1L;

	public RegionLeaseState {
		if (role == null) {
			role = RegionRole.HOLD;
		}
		if (epoch < INITIAL_EPOCH) {
			epoch = INITIAL_EPOCH;
		}
		claimedByNodeId = claimedByNodeId == null ? "" : claimedByNodeId;
		claimedByDc = claimedByDc == null ? "" : claimedByDc;
	}

	public static RegionLeaseState bootstrap(RegionRole role, long epoch, String nodeId, String dc) {
		return new RegionLeaseState(role, epoch, nodeId == null ? "" : nodeId, dc == null ? "" : dc);
	}

	public RegionLeaseState withRole(RegionRole next) {
		return new RegionLeaseState(next, epoch, claimedByNodeId, claimedByDc);
	}

	public RegionLeaseState fenceToHold() {
		return new RegionLeaseState(RegionRole.HOLD, epoch, claimedByNodeId, claimedByDc);
	}

	public RegionLeaseState claimActive(long nextEpoch, String nodeId, String dc) {
		return new RegionLeaseState(RegionRole.ACTIVE, nextEpoch, nodeId, dc);
	}
}