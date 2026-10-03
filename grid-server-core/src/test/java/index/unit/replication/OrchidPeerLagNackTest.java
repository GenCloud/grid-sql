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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.orchid.DigestQuorum;
import org.genfork.grid.replication.orchid.OrchidMultiDcConfig;
import org.genfork.grid.replication.orchid.OrchidNackCode;
import org.genfork.grid.replication.orchid.OrchidNode;
import org.genfork.grid.replication.orchid.OrchidTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E: lagging learner (tip behind propose) must buffer, never NACK, while local DC commits.
 * <p>
 * Jepsen multidc-async: learner tip=0 after DC heal used to emit stale string NACKs that
 * failProposeChain'd a healthy writer (Elle G1a / dirty-update / duplicate-elements).
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
class OrchidPeerLagNackTest {

	private static final long AWAIT_MS = 8_000L;
	private static final long AHEAD_PROPOSE_ID = 77L;
	private static final long AHEAD_PREV_OP_SEQ = 21L;
	private static final int MAX_IN_FLIGHT = 8;
	private static final int PIPELINE_COMMITS = 5;

	private final Map<String, OrchidNode> nodes = new ConcurrentHashMap<>();
	private final SelectiveMesh mesh = new SelectiveMesh(nodes);

	@AfterEach
	void tearDown() {
		nodes.values().forEach(OrchidNode::stop);
		nodes.clear();
	}

	@Test
	void laggingPeerBuffersAheadProposeWithoutNack() {
		final OrchidNode lagging = start("b1", List.of());
		lagging.onPeerAvailable("a1");

		final ReplicationOp op = OpLogCodec.withChecksum(new ReplicationOp(
				"demo.PeerLag", 0, 1L, ReplicationOpType.UPSERT,
				new byte[] {1}, new byte[] {2}, 1L, 0L));
		final long digest = OrchidNode.digestOf(op);
		lagging.onPropose(new OrchidTransport.OrchidProposeMessage(
				"a1", AHEAD_PROPOSE_ID, digest, AHEAD_PREV_OP_SEQ, op));

		assertEquals(0, mesh.nackCount("a1"), "lagging peer must not NACK ahead-of-tip propose");
		assertTrue(lagging.testingRemoteInflightContains(AHEAD_PROPOSE_ID),
				"ahead propose buffered for claim-time seal");
		assertTrue(mesh.nacksByCode(OrchidNackCode.STALE_PREV_OP_SEQ).isEmpty());
	}

	@Test
	void localMajorityCommitsWhileLaggingLearnerBuffersProposes() throws Exception {
		final OrchidNode a1 = start("a1", List.of("a2"));
		final OrchidNode a2 = start("a2", List.of("a1"));
		final OrchidNode b1 = start("b1", List.of());
		a1.onPeerAvailable("a2");
		a1.onPeerAvailable("b1");
		a2.onPeerAvailable("a1");
		b1.onPeerAvailable("a1");
		// Learner never receives commits (ASYNC tip stays 0) but still gets proposes.
		mesh.dropCommitsTo("b1");

		for (int i = 0; i < PIPELINE_COMMITS; i++) {
			final ReplicationOp op = OpLogCodec.withChecksum(new ReplicationOp(
					"demo.PeerLagE2E", 0, i + 1L, ReplicationOpType.UPSERT,
					new byte[] {(byte) i}, new byte[] {(byte) (i + 1)}, 1L, 0L));
			final CompletableFuture<Long> future = a1.appendAndWaitCommit(op);
			final long seq = future.get(AWAIT_MS, TimeUnit.MILLISECONDS);
			assertEquals(i + 1L, seq);
		}

		assertEquals(PIPELINE_COMMITS, a1.getLastCommittedSeq());
		final long deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(AWAIT_MS);
		while (a2.getLastCommittedSeq() < PIPELINE_COMMITS && System.nanoTime() < deadlineNs) {
			Thread.sleep(10L);
		}
		assertEquals(PIPELINE_COMMITS, a2.getLastCommittedSeq(), "replica catch-up after async commit apply");
		assertEquals(0L, b1.getLastCommittedSeq(), "learner tip stayed 0 without commits");
		assertEquals(0, mesh.nackCount("a1"), "learner must never NACK during healthy local commits");
		assertTrue(mesh.proposeDeliveriesTo("b1") >= PIPELINE_COMMITS,
				"learner received proposes for claim buffer deliveries=" + mesh.proposeDeliveriesTo("b1"));
	}

	private OrchidNode start(String id, List<String> peers) {
		final OrchidNode node = new OrchidNode(
				id, 15.0, 1.0, 0.0, 10L, DigestQuorum.MAJORITY, mesh, peers,
				null, false, OrchidMultiDcConfig.NONE, MAX_IN_FLIGHT);
		nodes.put(id, node);
		node.start();
		return node;
	}

	private static final class SelectiveMesh implements OrchidTransport {
		private final Map<String, OrchidNode> nodes;
		private final Set<String> dropCommitTargets = ConcurrentHashMap.newKeySet();
		private final Map<String, List<OrchidNackMessage>> nacks = new ConcurrentHashMap<>();
		private final Map<String, Integer> proposeDeliveries = new ConcurrentHashMap<>();

		SelectiveMesh(Map<String, OrchidNode> nodes) {
			this.nodes = nodes;
		}

		void dropCommitsTo(String nodeId) {
			dropCommitTargets.add(nodeId);
		}

		int nackCount(String toNodeId) {
			final List<OrchidNackMessage> list = nacks.get(toNodeId);
			return list == null ? 0 : list.size();
		}

		List<OrchidNackMessage> nacksByCode(OrchidNackCode code) {
			final List<OrchidNackMessage> out = new ArrayList<>();
			for (List<OrchidNackMessage> list : nacks.values()) {
				for (OrchidNackMessage nack : list) {
					if (nack.code() == code) {
						out.add(nack);
					}
				}
			}
			return out;
		}

		int proposeDeliveriesTo(String nodeId) {
			return proposeDeliveries.getOrDefault(nodeId, 0);
		}

		private void deliver(String toNodeId, Runnable action) {
			final OrchidNode target = nodes.get(toNodeId);
			if (target == null) {
				return;
			}
			action.run();
		}

		@Override
		public void sendPhase(String toNodeId, OrchidPhaseMessage message) {
			deliver(toNodeId, () -> {
				final OrchidNode n = nodes.get(toNodeId);
				if (n != null) {
					n.onPhase(message);
				}
			});
		}

		@Override
		public void broadcastPhase(OrchidPhaseMessage message) {
			for (String id : nodes.keySet()) {
				if (!id.equals(message.nodeId())) {
					sendPhase(id, message);
				}
			}
		}

		@Override
		public void sendPropose(String toNodeId, OrchidProposeMessage message) {
			deliver(toNodeId, () -> {
				final OrchidNode n = nodes.get(toNodeId);
				if (n != null) {
					proposeDeliveries.merge(toNodeId, 1, Integer::sum);
					n.onPropose(message);
				}
			});
		}

		@Override
		public void broadcastPropose(OrchidProposeMessage message) {
			for (String id : nodes.keySet()) {
				if (!id.equals(message.proposerId())) {
					sendPropose(id, message);
				}
			}
		}

		@Override
		public void sendCommit(String toNodeId, OrchidCommitMessage message) {
			if (dropCommitTargets.contains(toNodeId)) {
				return;
			}
			deliver(toNodeId, () -> {
				final OrchidNode n = nodes.get(toNodeId);
				if (n != null) {
					n.onCommit(message);
				}
			});
		}

		@Override
		public void broadcastCommit(OrchidCommitMessage message) {
			for (String id : nodes.keySet()) {
				if (!id.equals(message.committerId())) {
					sendCommit(id, message);
				}
			}
		}

		@Override
		public void sendNack(String toNodeId, OrchidNackMessage message) {
			nacks.computeIfAbsent(toNodeId, k -> new CopyOnWriteArrayList<>()).add(message);
			deliver(toNodeId, () -> {
				final OrchidNode n = nodes.get(toNodeId);
				if (n != null) {
					n.onNack(message);
				}
			});
		}
	}
}