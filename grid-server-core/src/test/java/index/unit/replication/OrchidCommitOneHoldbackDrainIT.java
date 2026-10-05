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

import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.orchid.DigestQuorum;
import org.genfork.grid.replication.orchid.OrchidMultiDcConfig;
import org.genfork.grid.replication.orchid.OrchidNode;
import org.genfork.grid.replication.orchid.OrchidTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E for GHA 37323133691 cell D (multidc-async-chaos) harness writer-eligible-timeout.
 * <p>
 * Evidence: a1 buffered peer commits 18–20 ({@code waitingFor=17}), then local tip advanced
 * to 17 via {@code commitOne} without {@code drainCommitHoldback} → tip stuck at 17 while
 * peers stayed at 20 → permanent tip-behind / no writerEligible.
 * <p>
 * Contiguous peer-commit holdback already drains in {@code onCommitSerial}; this IT asserts
 * the same drain after a local {@code commitOne} tip step (GHA a1 class).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
public class OrchidCommitOneHoldbackDrainIT {
	private static final int MAX_IN_FLIGHT = 32;
	private static final long AWAIT_MS = 5_000L;
	private static final long POLL_MS = 20L;
	/** GHA shape: tip missing N, holdback N+1..N+3 (17 vs 18–20). */
	private static final long TIP_BEFORE = 16L;
	private static final long LOCAL_COMMIT_SEQ = 17L;
	private static final long AHEAD_TIP = 20L;

	private final Map<String, OrchidNode> nodes = new ConcurrentHashMap<>();
	private final MeshTransport mesh = new MeshTransport(nodes);

	@AfterEach
	void tearDown() {
		nodes.values().forEach(OrchidNode::stop);
		nodes.clear();
	}

	@Test
	@Timeout(30)
	void localCommitOne_mustDrainPeerHoldback_ghaD_a1Class() throws Exception {
		final OrchidNode proposer = start("a1", List.of("a2"), MAX_IN_FLIGHT);
		final OrchidNode peer = start("a2", List.of("a1"), MAX_IN_FLIGHT);
		proposer.onPeerAvailable("a2");
		peer.onPeerAvailable("a1");
		assertTrue(await(() -> !proposer.awaitsPeerTipAdvertisement()
						&& !peer.awaitsPeerTipAdvertisement()),
				"tip advertisement before admit");

		// Seed contiguous tip to TIP_BEFORE via local proposes (solo-style on proposer mesh).
		for (long seq = 1L; seq <= TIP_BEFORE; seq++) {
			final ReplicationOp op = upsert("gha.d.holdback", seq);
			final CompletableFuture<Long> fut = proposer.appendAndWaitCommit(op);
			assertEquals(seq, fut.get(AWAIT_MS, TimeUnit.MILLISECONDS));
		}
		assertEquals(TIP_BEFORE, proposer.getLastCommittedSeq());

		// Peer-sealed / reordered commits ahead of local tip (GHA: 18–20 while waiting for 17).
		for (long seq = LOCAL_COMMIT_SEQ + 1L; seq <= AHEAD_TIP; seq++) {
			final ReplicationOp op = upsert("gha.d.holdback", seq);
			proposer.onCommit(new OrchidTransport.OrchidCommitMessage(
					"a2",
					1_000L + seq,
					OrchidNode.digestOf(op),
					seq - 1L,
					seq,
					op));
		}
		assertTrue(await(() -> true));
		Thread.sleep(POLL_MS * 2L);
		assertEquals(TIP_BEFORE, proposer.getLastCommittedSeq(),
				"ahead commits must stay in holdback until tip step " + LOCAL_COMMIT_SEQ);

		// Local commitOne for the missing tip step — must drain holdback to AHEAD_TIP.
		final ReplicationOp local = upsert("gha.d.holdback", LOCAL_COMMIT_SEQ);
		final long committed = proposer.appendAndWaitCommit(local)
				.get(AWAIT_MS, TimeUnit.MILLISECONDS);
		assertEquals(LOCAL_COMMIT_SEQ, committed, "local propose must commit at tip+1");

		assertTrue(await(() -> proposer.getLastCommittedSeq() == AHEAD_TIP),
				() -> "GHA-D a1 class: commitOne must drain holdback tip="
						+ proposer.getLastCommittedSeq() + " expected=" + AHEAD_TIP
						+ " (stuck tip-behind / writer-eligible-timeout)");
		assertEquals(AHEAD_TIP, proposer.getLastCommittedSeq());
	}

	private static ReplicationOp upsert(String domain, long seq) {
		return OpLogCodec.withChecksum(new ReplicationOp(
				domain,
				0,
				seq,
				ReplicationOpType.UPSERT,
				new byte[]{(byte) seq},
				new byte[]{(byte) (seq + 10L)},
				1L,
				0L));
	}

	private static boolean await(BooleanSupplier condition) {
		final long deadline = System.currentTimeMillis() + AWAIT_MS;
		while (System.currentTimeMillis() < deadline) {
			if (condition.getAsBoolean()) {
				return true;
			}
			try {
				Thread.sleep(POLL_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
		return condition.getAsBoolean();
	}

	private OrchidNode start(String id, List<String> peers, int maxInFlight) {
		final OrchidNode node = new OrchidNode(
				id, 15.0, 1.0, 0.0, 10L, DigestQuorum.MAJORITY, mesh, peers,
				null, false, OrchidMultiDcConfig.NONE, maxInFlight);
		nodes.put(id, node);
		node.start();
		return node;
	}

	private static final class MeshTransport implements OrchidTransport {
		private final Map<String, OrchidNode> nodes;

		MeshTransport(Map<String, OrchidNode> nodes) {
			this.nodes = nodes;
		}

		@Override
		public void sendPropose(String toNodeId, OrchidProposeMessage message) {
			deliver(toNodeId, () -> nodes.get(toNodeId).onPropose(message));
		}

		@Override
		public void broadcastPropose(OrchidProposeMessage message) {
			for (Map.Entry<String, OrchidNode> e : nodes.entrySet()) {
				if (!e.getKey().equals(message.proposerId())) {
					sendPropose(e.getKey(), message);
				}
			}
		}

		@Override
		public void sendCommit(String toNodeId, OrchidCommitMessage message) {
			deliver(toNodeId, () -> nodes.get(toNodeId).onCommit(message));
		}

		@Override
		public void broadcastCommit(OrchidCommitMessage message) {
			final List<Map.Entry<String, OrchidNode>> entries = new ArrayList<>(nodes.entrySet());
			for (int i = 0; i < entries.size(); i++) {
				final Map.Entry<String, OrchidNode> e = entries.get(i);
				if (!e.getKey().equals(message.committerId())) {
					sendCommit(e.getKey(), message);
				}
			}
		}

		@Override
		public void sendPhase(String toNodeId, OrchidPhaseMessage message) {
			deliver(toNodeId, () -> nodes.get(toNodeId).onPhase(message));
		}

		@Override
		public void broadcastPhase(OrchidPhaseMessage message) {
			for (Map.Entry<String, OrchidNode> e : nodes.entrySet()) {
				if (!e.getKey().equals(message.nodeId())) {
					sendPhase(e.getKey(), message);
				}
			}
		}

		@Override
		public void sendNack(String toNodeId, OrchidNackMessage message) {
			deliver(toNodeId, () -> nodes.get(toNodeId).onNack(message));
		}

		private void deliver(String toNodeId, Runnable action) {
			final OrchidNode target = nodes.get(toNodeId);
			if (target == null) {
				return;
			}
			action.run();
		}
	}
}
