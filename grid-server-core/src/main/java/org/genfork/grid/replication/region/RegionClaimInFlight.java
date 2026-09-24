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

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-flight Multi-DC region claim: collect voter ACKs without blocking Netty EL.
 * <p>
 * Self is always counted once; peer ACKs arrive via {@code REGION_CLAIM_ACK}.
 *
 * @author: GenCloud
 * @date: 2026/06
 * @since: 1.0
 */
public final class RegionClaimInFlight {
	private static final AtomicLong NEXT_CLAIM_ID = new AtomicLong(1L);

	private final long claimId;
	private final long proposedEpoch;
	private final String claimantNodeId;
	private final int voterCount;
	private final Set<String> ackNodeIds;
	private final long startedNanos;

	private RegionClaimInFlight(
			long claimId,
			long proposedEpoch,
			String claimantNodeId,
			int voterCount
	) {
		this.claimId = claimId;
		this.proposedEpoch = proposedEpoch;
		this.claimantNodeId = Objects.requireNonNull(claimantNodeId, "claimantNodeId");
		this.voterCount = Math.max(1, voterCount);
		this.ackNodeIds = ConcurrentHashMap.newKeySet();
		this.ackNodeIds.add(claimantNodeId);
		this.startedNanos = System.nanoTime();
	}

	public static RegionClaimInFlight start(String claimantNodeId, long proposedEpoch, int voterCount) {
		return new RegionClaimInFlight(NEXT_CLAIM_ID.getAndIncrement(), proposedEpoch, claimantNodeId, voterCount);
	}

	public long claimId() {
		return claimId;
	}

	public long proposedEpoch() {
		return proposedEpoch;
	}

	public String claimantNodeId() {
		return claimantNodeId;
	}

	public int voterCount() {
		return voterCount;
	}

	public int ackCount() {
		return ackNodeIds.size();
	}

	public long ageMs() {
		return (System.nanoTime() - startedNanos) / 1_000_000L;
	}

	/**
	 * @return true when {@code fromNodeId} is newly recorded
	 */
	public boolean recordAck(String fromNodeId) {
		if (fromNodeId == null || fromNodeId.isBlank()) {
			return false;
		}
		return ackNodeIds.add(fromNodeId);
	}

	public boolean hasQuorum() {
		return RegionClaimQuorum.hasQuorum(ackCount(), voterCount);
	}
}