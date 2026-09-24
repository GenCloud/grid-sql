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
package org.genfork.grid.replication;

import com.google.common.annotations.VisibleForTesting;

/**
 * Shared write / read admission for SQL paths (no duplicated checks).
 * <p>
 * Primary / Active region nodes write when ORCHID writer-eligible, region epoch matches,
 * and apply lag is caught up. Learners / Hold / Witness never admit client SQL writes.
 * Linearizable SELECT is served only by the phase-ranked proposer with caught-up apply lag.
 * Opt-in replica reads ({@link #ensureReplicaRead}) allow synced voters / Hold when
 * {@code replicaReadsEnabled} and lag is within {@code maxStaleLag}.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public final class ReplicaAccessGate {
	private static final String WRITE_REQUIRES_PROPOSER = "write requires phase-ranked proposer";
	private static final String WRITE_LEARNER_DENIED = "write denied on cross-dc learner (write-admission=false)";
	private static final String WRITE_REGION_FENCED = "write denied: region fenced (not Active or epoch mismatch)";
	private static final String WRITE_STALE = "write denied until apply lag caught up";
	private static final String READ_LEARNER_DENIED = "read denied on cross-dc learner (write-admission=false)";
	private static final String READ_STALE = "stale replica read (apply lag exceeded)";
	private static final String READ_REQUIRES_PROPOSER = "read requires phase-ranked proposer";
	private static final String REPLICA_READ_DISABLED = "replica reads disabled on this node";
	private static final String REPLICA_READ_REGION_DENIED = "replica read denied: region role Witness or fenced";

	private ReplicaAccessGate() {
	}

	/**
	 * Mutating SQL / flush path.
	 *
	 * @throws OrchidNotSyncedException when replication enabled and node cannot admit writes
	 */
	public static void ensureWrite(ReplicationCoordinator replication) {
		if (replication == null || !replication.isEnabled()) {
			return;
		}
		ensureWriteState(
				replication.isWriteAdmission(),
				replication.isWriterEligible(),
				replication.isApplyLagStale(),
				replication.regionAllowsWrites());
	}

	/**
	 * Linearizable SELECT path (default / PRIMARY session): proposer only, fail-closed on lag.
	 *
	 * @throws OrchidNotSyncedException when this node must not serve a successful read
	 */
	public static void ensureRead(ReplicationCoordinator replication) {
		ensureLinearizableRead(replication);
	}

	/**
	 * Linearizable SELECT: learners never serve; followers / stale lag fail-closed
	 * so multi-host clients rotate to the phase-ranked proposer.
	 */
	public static void ensureLinearizableRead(ReplicationCoordinator replication) {
		if (replication == null || !replication.isEnabled()) {
			return;
		}
		ensureLinearizableReadState(
				replication.isWriteAdmission(),
				replication.isApplyLagStale(),
				replication.isWriterEligible());
	}

	/**
	 * Opt-in replica SELECT (READ_REPLICA session): synced voter/Hold, fail-closed on lag,
	 * never learners / Witness; requires {@link ReplicationCoordinator#isReplicaReadsEnabled()}.
	 */
	public static void ensureReplicaRead(ReplicationCoordinator replication) {
		if (replication == null || !replication.isEnabled()) {
			return;
		}
		ensureReplicaReadState(
				replication.isWriteAdmission(),
				replication.isApplyLagStale(),
				replication.isReplicaReadsEnabled(),
				replication.regionAllowsReplicaReads());
	}

	@VisibleForTesting
	public static void ensureWriteState(boolean writeAdmission, boolean writerEligible) {
		ensureWriteState(writeAdmission, writerEligible, false, true);
	}

	@VisibleForTesting
	public static void ensureWriteState(
			boolean writeAdmission,
			boolean writerEligible,
			boolean applyLagStale) {
		ensureWriteState(writeAdmission, writerEligible, applyLagStale, true);
	}

	@VisibleForTesting
	public static void ensureWriteState(
			boolean writeAdmission,
			boolean writerEligible,
			boolean applyLagStale,
			boolean regionAllowsWrites) {
		if (!regionAllowsWrites) {
			throw new OrchidNotSyncedException(WRITE_REGION_FENCED);
		}
		if (!writeAdmission) {
			throw new OrchidNotSyncedException(WRITE_LEARNER_DENIED);
		}
		if (!writerEligible) {
			throw new OrchidNotSyncedException(WRITE_REQUIRES_PROPOSER);
		}
		if (applyLagStale) {
			throw new OrchidNotSyncedException(WRITE_STALE);
		}
	}

	/**
	 * @param writeAdmission {@code false} → Cross-DC learner
	 * @param applyLagStale  apply lag exceeded {@code ha.max-stale-lag}
	 * @param writerEligible phase-ranked proposer with write-admission
	 */
	@VisibleForTesting
	public static void ensureReadState(
			boolean writeAdmission,
			boolean applyLagStale,
			boolean writerEligible) {
		ensureLinearizableReadState(writeAdmission, applyLagStale, writerEligible);
	}

	@VisibleForTesting
	public static void ensureLinearizableReadState(
			boolean writeAdmission,
			boolean applyLagStale,
			boolean writerEligible) {
		denyIfLearner(writeAdmission);
		denyIfStale(applyLagStale);
		if (!writerEligible) {
			throw new OrchidNotSyncedException(READ_REQUIRES_PROPOSER);
		}
	}

	@VisibleForTesting
	public static void ensureReplicaReadState(
			boolean writeAdmission,
			boolean applyLagStale,
			boolean replicaReadsEnabled,
			boolean regionAllowsReplicaRead) {
		if (!replicaReadsEnabled) {
			throw new OrchidNotSyncedException(REPLICA_READ_DISABLED);
		}
		denyIfLearner(writeAdmission);
		denyIfStale(applyLagStale);
		if (!regionAllowsReplicaRead) {
			throw new OrchidNotSyncedException(REPLICA_READ_REGION_DENIED);
		}
	}

	private static void denyIfLearner(boolean writeAdmission) {
		if (!writeAdmission) {
			throw new OrchidNotSyncedException(READ_LEARNER_DENIED);
		}
	}

	private static void denyIfStale(boolean applyLagStale) {
		if (applyLagStale) {
			throw new OrchidNotSyncedException(READ_STALE);
		}
	}
}
