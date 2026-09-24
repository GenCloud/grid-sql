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
package index.unit.replication;

import org.genfork.grid.replication.OrchidNotSyncedException;
import org.genfork.grid.replication.ReplicaAccessGate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Learner write/read denial, proposer-only linearizable reads, and opt-in replica reads.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public class ReplicaAccessGateTest {

	@Test
	void writeDeniedWhenWriteAdmissionFalse() {
		assertThrows(OrchidNotSyncedException.class,
				() -> ReplicaAccessGate.ensureWriteState(false, true));
	}

	@Test
	void writeOkWhenEligible() {
		assertDoesNotThrow(() -> ReplicaAccessGate.ensureWriteState(true, true));
	}

	@Test
	void writeDeniedWhenNotEligible() {
		assertThrows(OrchidNotSyncedException.class,
				() -> ReplicaAccessGate.ensureWriteState(true, false));
	}

	@Test
	void writeDeniedWhenRegionFenced() {
		assertThrows(OrchidNotSyncedException.class,
				() -> ReplicaAccessGate.ensureWriteState(true, true, false, false));
	}

	@Test
	void readDeniedOnLearnerAlways() {
		assertThrows(OrchidNotSyncedException.class,
				() -> ReplicaAccessGate.ensureReadState(false, false, true));
	}

	@Test
	void readOkOnProposer() {
		assertDoesNotThrow(() -> ReplicaAccessGate.ensureReadState(true, false, true));
	}

	@Test
	void readDeniedWhenStale() {
		assertThrows(OrchidNotSyncedException.class,
				() -> ReplicaAccessGate.ensureReadState(true, true, true));
	}

	@Test
	void readDeniedOnFollower() {
		assertThrows(OrchidNotSyncedException.class,
				() -> ReplicaAccessGate.ensureReadState(true, false, false));
	}

	@Test
	void replicaReadOkWhenEnabledSyncedVoter() {
		assertDoesNotThrow(() -> ReplicaAccessGate.ensureReplicaReadState(
				true, false, true, true));
	}

	@Test
	void replicaReadDeniedWhenDisabled() {
		assertThrows(OrchidNotSyncedException.class,
				() -> ReplicaAccessGate.ensureReplicaReadState(true, false, false, true));
	}

	@Test
	void replicaReadDeniedWhenStale() {
		assertThrows(OrchidNotSyncedException.class,
				() -> ReplicaAccessGate.ensureReplicaReadState(true, true, true, true));
	}

	@Test
	void replicaReadDeniedOnLearner() {
		assertThrows(OrchidNotSyncedException.class,
				() -> ReplicaAccessGate.ensureReplicaReadState(false, false, true, true));
	}

	@Test
	void replicaReadDeniedWhenRegionWitness() {
		assertThrows(OrchidNotSyncedException.class,
				() -> ReplicaAccessGate.ensureReplicaReadState(true, false, true, false));
	}

	@Test
	void replicaReadOkOnHoldRegion() {
		assertDoesNotThrow(() -> ReplicaAccessGate.ensureReplicaReadState(
				true, false, true, true));
	}
}
