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

import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.orchid.DigestQuorum;
import org.genfork.grid.replication.orchid.OrchidNode;
import org.genfork.grid.replication.orchid.OrchidTransport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Peer tip high-water must survive forgetPeer (ASYNC Active DC loss / Hold fence).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
class OrchidPeerTipHighWaterTest {
	private static final long ACTIVE_TIP = 42L;

	private static final OrchidTransport NOOP = new OrchidTransport() {
		@Override
		public void sendPhase(String toNodeId, OrchidTransport.OrchidPhaseMessage message) {
		}

		@Override
		public void broadcastPhase(OrchidTransport.OrchidPhaseMessage message) {
		}

		@Override
		public void sendPropose(String toNodeId, OrchidTransport.OrchidProposeMessage message) {
		}

		@Override
		public void broadcastPropose(OrchidTransport.OrchidProposeMessage message) {
		}

		@Override
		public void sendCommit(String toNodeId, OrchidTransport.OrchidCommitMessage message) {
		}

		@Override
		public void broadcastCommit(OrchidTransport.OrchidCommitMessage message) {
		}

		@Override
		public void sendNack(String toNodeId, OrchidTransport.OrchidNackMessage message) {
		}
	};

	@Test
	void maxSeenPeerCommittedSeqSurvivesForgetPeer() {
		final OrchidNode node = new OrchidNode(
				"hold-b1",
				15.0,
				1.0,
				0.85,
				10L,
				DigestQuorum.MAJORITY,
				NOOP,
				List.of("a1"));
		node.testingNotePeerCommittedSeq("a1", ACTIVE_TIP);
		assertEquals(ACTIVE_TIP, node.maxSeenPeerCommittedSeq());
		node.forgetPeer("a1");
		assertEquals(ACTIVE_TIP, node.maxSeenPeerCommittedSeq(),
				"durable high-water must retain Active tip after disconnect");
	}
}