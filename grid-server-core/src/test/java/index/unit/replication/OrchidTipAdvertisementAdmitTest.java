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
import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.orchid.DigestQuorum;
import org.genfork.grid.replication.orchid.OrchidMultiDcConfig;
import org.genfork.grid.replication.orchid.OrchidNode;
import org.genfork.grid.replication.orchid.OrchidTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Race-free characterization of admit fail-closed gates used by ThreeNodeClusterLatencyIT.
 * <p>
 * Evidence (regress stack): {@code OrchidNotSyncedException: ORCHID R below threshold or configured
 * peers unseen; cluster not phase-synced} at {@code OrchidNode.appendAndAdmit} when
 * {@code !isSynced()}. Separate tip-await gate refuses admit after HELLO until phase tip.
 * <p>
 * No second live OrchidNode during tip-await / unseen asserts — peer Kuramoto ticks deliver
 * {@code onPhase} and clear the fence before admit (prior flaky harness).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
class OrchidTipAdvertisementAdmitTest {

	private static final String DOMAIN = "demo.TipAdvert";
	private static final String PRIMARY = "tip-a";
	private static final String PEER = "tip-b";
	private static final String MSG_R_BELOW =
			"ORCHID R below threshold or configured peers unseen; cluster not phase-synced";
	private static final String MSG_TIP_AWAIT =
			"awaiting peer tip advertisement after reconnect; refuse admit until phase tip";

	private final Map<String, OrchidNode> nodes = new ConcurrentHashMap<>();
	private final NoopMesh mesh = new NoopMesh();

	@AfterEach
	void tearDown() {
		nodes.values().forEach(OrchidNode::stop);
		nodes.clear();
	}

	/**
	 * Exact message from ThreeNodeClusterLatencyIT regress stack when live local peers are gone.
	 */
	@Test
	void appendAndAdmitFailsWithRBelowMessageWhenLiveLocalPeersUnseen() {
		final OrchidNode primary = start(PRIMARY, List.of(PEER));
		primary.onPeerAvailable(PEER);
		primary.testingNotePeerCommittedSeq(PEER, 0L);
		assertTrue(primary.isSynced());
		assertTrue(primary.isPhaseRankedProposer());
		assertFalse(primary.awaitsPeerTipAdvertisement());

		primary.testingMarkPeerUnseen(PEER);
		assertFalse(primary.isSynced());
		assertAdmitFailsWith(primary, MSG_R_BELOW);
	}

	@Test
	void appendAndAdmitFailsClosedUntilPeerTipAdvertisedAfterHello() {
		final OrchidNode primary = start(PRIMARY, List.of(PEER));
		primary.onPeerAvailable(PEER);
		assertTrue(primary.awaitsPeerTipAdvertisement());
		assertTrue(primary.isSynced());
		assertTrue(primary.isPhaseRankedProposer());
		assertAdmitFailsWith(primary, MSG_TIP_AWAIT);

		primary.testingNotePeerCommittedSeq(PEER, 0L);
		assertFalse(primary.awaitsPeerTipAdvertisement());
	}

	@Test
	void forgetPeerThenHelloRearmsTipAwait() {
		final OrchidNode primary = start(PRIMARY, List.of(PEER));
		primary.onPeerAvailable(PEER);
		primary.testingNotePeerCommittedSeq(PEER, 0L);
		assertFalse(primary.awaitsPeerTipAdvertisement());

		primary.forgetPeer(PEER);
		primary.onPeerAvailable(PEER);
		assertTrue(primary.awaitsPeerTipAdvertisement());
		assertAdmitFailsWith(primary, MSG_TIP_AWAIT);
	}

	@Test
	void onPeerAvailableWhileSeenClearsTipAdvertisedAgain() {
		// Product contract (tipAdvertised fence): every HELLO clears tip until phase tip returns.
		final OrchidNode primary = start(PRIMARY, List.of(PEER));
		primary.onPeerAvailable(PEER);
		primary.testingNotePeerCommittedSeq(PEER, 0L);
		assertFalse(primary.awaitsPeerTipAdvertisement());

		primary.onPeerAvailable(PEER);
		assertTrue(primary.awaitsPeerTipAdvertisement(),
				"HELLO while still seen must re-clear tipAdvertised (unclean/reconnect fence)");
		assertAdmitFailsWith(primary, MSG_TIP_AWAIT);
	}

	/**
	 * PR13-M / 1630: region-claim await clear must ignore same-DC Hold tips and only count
	 * live tip-advertised remote-DC peers.
	 */
	@Test
	void maxSeenLiveRemoteDcTipIgnoresLocalPeerTips() {
		final String remote = "tip-remote";
		final OrchidNode hold = start(PRIMARY, List.of(PEER));
		hold.onPeerAvailable(PEER);
		hold.testingNotePeerCommittedSeq(PEER, 881L);
		assertEquals(881L, hold.maxSeenPeerCommittedSeq());
		assertEquals(0L, hold.maxSeenLiveRemoteDcPeerCommittedSeq(),
				"local peer tip must not count as remote-DC tip");

		hold.onPeerAvailable(remote);
		hold.testingNotePeerCommittedSeq(remote, 900L);
		assertEquals(900L, hold.maxSeenLiveRemoteDcPeerCommittedSeq(),
				"remote peer tip must count once tipAdvertised");
	}

	private static void assertAdmitFailsWith(OrchidNode orchid, String messageFragment) {
		final ReplicationOp op = OpLogCodec.withChecksum(new ReplicationOp(
				DOMAIN, 0, orchid.getLastCommittedSeq() + 1L, ReplicationOpType.UPSERT,
				new byte[]{9}, new byte[]{9}, 1L, 0L));
		final OrchidNode.AdmittedPropose admitted = orchid.appendAndAdmit(op);
		try {
			admitted.future().join();
			fail("expected OrchidNotSyncedException containing: " + messageFragment);
		} catch (CompletionException ex) {
			assertTrue(rootMessageContains(ex, messageFragment),
					() -> "expected message fragment '" + messageFragment + "', got " + rootMessage(ex));
		}
	}

	private static boolean rootMessageContains(Throwable thrown, String fragment) {
		Throwable cur = thrown;
		while (cur != null) {
			if (cur instanceof OrchidNotSyncedException) {
				final String msg = cur.getMessage();
				return msg != null && msg.contains(fragment);
			}
			cur = cur.getCause();
		}
		return false;
	}

	private static String rootMessage(Throwable thrown) {
		Throwable cur = thrown;
		while (cur.getCause() != null) {
			cur = cur.getCause();
		}
		return cur.getClass().getSimpleName() + ": " + cur.getMessage();
	}

	private OrchidNode start(String id, List<String> peers) {
		final OrchidNode node = new OrchidNode(
				id, 15.0, 1.0, 0.0, 10L, DigestQuorum.MAJORITY, mesh, peers,
				null, false, OrchidMultiDcConfig.NONE, 8);
		nodes.put(id, node);
		node.start();
		return node;
	}

	/**
	 * Drop-all transport: no peer onPhase can race tipAdvertised / seen during asserts.
	 */
	private static final class NoopMesh implements OrchidTransport {
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
	}
}