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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Post-admit commit drain must not re-gate on isSynced().
 * <p>
 * MIX/QG load evidence: brief R dip after digest quorum wedged contiguous tip into
 * local propose digest timeout. Admit stays fail-closed on sync; drain does not.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
class OrchidCommitDrainUnsyncedTest {
	private static final long AWAIT_MS = 5_000L;
	private static final long POLL_MS = 20L;
	private static final long FIRST_PROPOSE_ID = 1L;

	private final Map<String, OrchidNode> nodes = new ConcurrentHashMap<>();
	private final HoldPhaseMesh mesh = new HoldPhaseMesh(nodes);

	@AfterEach
	void tearDown() {
		nodes.values().forEach(OrchidNode::stop);
		nodes.clear();
		mesh.releaseHeldPhases();
	}

	@Test
	void quorumAckedProposeCommitsAfterLiveViewDrop() throws Exception {
		final OrchidNode primary = start("drain-a", List.of("drain-b"));
		final OrchidNode replica = start("drain-b", List.of("drain-a"));
		primary.onPeerAvailable("drain-b");
		replica.onPeerAvailable("drain-a");
		assertTrue(await(() -> !primary.awaitsPeerTipAdvertisement()
						&& !replica.awaitsPeerTipAdvertisement()),
				"mesh tip advertisement after HELLO before holding phase ACKs");

		mesh.holdPhasesTo("drain-a");
		final ReplicationOp op = OpLogCodec.withChecksum(new ReplicationOp(
				"demo.DrainUnsynced", 0, 1L, ReplicationOpType.UPSERT,
				new byte[]{1}, new byte[]{2}, 1L, 0L));
		final long digest = primary.testingDigestOf(op);
		final CompletableFuture<Long> future = primary.appendAndWaitCommit(op);
		Thread.sleep(50L);
		assertFalse(future.isDone(), "phase ACKs held — tip must not commit yet");

		assertTrue(primary.testingPutDigestAck("drain-b", FIRST_PROPOSE_ID, digest),
				"peer digest must be injectable on pending propose");
		primary.testingMarkPeerUnseen("drain-b");
		assertFalse(primary.isSynced(), "empty live local view must fail-close admission sync");

		primary.testingRequestCommitDrain();

		final long seq = future.get(AWAIT_MS, TimeUnit.MILLISECONDS);
		assertTrue(seq >= 1L, "quorum-acked propose must commit despite post-admit unsynced view");
		assertTrue(primary.getLastCommittedSeq() >= seq);
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

	private OrchidNode start(String id, List<String> peers) {
		final OrchidNode node = new OrchidNode(
				id, 15.0, 1.0, 0.0, 10L, DigestQuorum.MAJORITY, mesh, peers,
				null, false, OrchidMultiDcConfig.NONE, 8);
		nodes.put(id, node);
		node.start();
		return node;
	}

	private static final class HoldPhaseMesh implements OrchidTransport {
		private final Map<String, OrchidNode> nodes;
		private final Map<String, List<Runnable>> held = new ConcurrentHashMap<>();

		HoldPhaseMesh(Map<String, OrchidNode> nodes) {
			this.nodes = nodes;
		}

		void holdPhasesTo(String toNodeId) {
			held.computeIfAbsent(toNodeId, k -> new CopyOnWriteArrayList<>());
		}

		void releaseHeldPhases() {
			for (List<Runnable> queue : held.values()) {
				final List<Runnable> copy = new ArrayList<>(queue);
				queue.clear();
				for (Runnable action : copy) {
					action.run();
				}
			}
			held.clear();
		}

		private void deliver(String toNodeId, Runnable action) {
			final OrchidNode target = nodes.get(toNodeId);
			if (target == null) {
				return;
			}
			final List<Runnable> queue = held.get(toNodeId);
			if (queue != null) {
				queue.add(action);
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
			deliver(toNodeId, () -> {
				final OrchidNode n = nodes.get(toNodeId);
				if (n != null) {
					n.onNack(message);
				}
			});
		}
	}
}