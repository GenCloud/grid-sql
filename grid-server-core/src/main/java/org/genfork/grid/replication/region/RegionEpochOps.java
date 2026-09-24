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
 * Pure helpers for region epoch / fencing decisions (no I/O).
 *
 * @author: GenCloud
 * @date: 2026/06
 * @since: 1.0
 */
public final class RegionEpochOps {
	private RegionEpochOps() {
	}

	/**
	 * True when observed peer epoch is strictly greater — local Active must fence.
	 */
	public static boolean mustFenceOnHigherEpoch(long localEpoch, long observedEpoch) {
		return observedEpoch > localEpoch;
	}

	/**
	 * Two Actives in different DCs at the same epoch — local must fence (never dual-write).
	 */
	public static boolean mustFenceDualActive(
			RegionRole localRole,
			long localEpoch,
			byte peerRoleWire,
			long peerEpoch,
			boolean peerRemoteDc
	) {
		if (!peerRemoteDc || localRole != RegionRole.ACTIVE) {
			return false;
		}
		if (peerRoleWire != RegionRole.ACTIVE.wireCode()) {
			return false;
		}
		return peerEpoch > 0L && peerEpoch == localEpoch;
	}

	/**
	 * Remote-DC peer at or behind local epoch — safe to confirm Active after revive.
	 */
	public static boolean canConfirmEpochAfterRemotePeer(long localEpoch, long peerEpoch) {
		return peerEpoch > 0L && peerEpoch <= localEpoch;
	}

	/**
	 * Next epoch for a successful claim.
	 */
	public static long nextClaimEpoch(long localEpoch) {
		return localEpoch + 1L;
	}

	/**
	 * Writer eligibility requires Active role at matching epoch.
	 */
	public static boolean regionAllowsWrites(RegionLeaseState local, long clusterEpochView) {
		if (local == null || !local.role().admitsWrites()) {
			return false;
		}
		return local.epoch() == clusterEpochView;
	}
}