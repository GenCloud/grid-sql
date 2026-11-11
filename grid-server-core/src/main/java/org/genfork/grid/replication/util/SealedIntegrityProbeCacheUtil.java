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
package org.genfork.grid.replication.util;

/**
 * Fast-path gates for sealed integrity sensor (skip DirectoryStream when safe).
 * <p>
 * Heal-set streams often have {@code peerFp==0} (peer HELLO lists only non-empty sealed
 * shards). Probing disk in that case wastes CPU with no pull — skip unless slow structural
 * cadence. Matched non-zero FPs also skip. Same-size truncation safety net =
 * {@code REPAIR_FULL_RECONCILE_EVERY} full probes.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
public final class SealedIntegrityProbeCacheUtil {
	private SealedIntegrityProbeCacheUtil() {
	}

	/**
	 * {@code true} when sensor may skip {@code listShardFiles} / header probe for this peer stream.
	 * <p>
	 * {@code cachedStructuralHoleOrNull} is accepted for call-site stability but does not gate
	 * the skip (global hole bit must not amplify into all-shard FS scans).
	 *
	 * @param slowStructuralCadence {@code true} on {@code REPAIR_FULL_RECONCILE_EVERY} ticks
	 * @param cachedStructuralHoleOrNull unused for skip (admit fence uses hole cache separately)
	 * @param localInventoryFp memory HELLO inventory FP ({@code 0} = unknown / empty)
	 * @param peerInventoryFp peer HELLO shard FP ({@code 0} = peer does not advertise)
	 */
	public static boolean canSkipStructuralDiskProbe(
			boolean slowStructuralCadence,
			Boolean cachedStructuralHoleOrNull,
			long localInventoryFp,
			long peerInventoryFp
	) {
		if (slowStructuralCadence) {
			return false;
		}
		// Peer does not advertise this stream — nothing to pull; do not DirectoryStream.
		if (peerInventoryFp == 0L) {
			return true;
		}
		if (localInventoryFp == 0L) {
			return false;
		}
		return localInventoryFp == peerInventoryFp;
	}
}
