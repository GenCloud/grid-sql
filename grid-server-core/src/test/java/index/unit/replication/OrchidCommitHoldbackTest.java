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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pipelined commits may arrive out of order; peer holdback must apply contiguous.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
class OrchidCommitHoldbackTest {
	private static final int MAX_IN_FLIGHT = 32;
	private static final long AWAIT_MS = 5_000L;
	private static final long POLL_MS = 20L;

	private final Map<String, OrchidNode> nodes = new ConcurrentHashMap<>();
	private final MeshTransport mesh = new MeshTransport(nodes);

	@AfterEach
	void tearDown() {
		nodes.values().forEach(OrchidNode::stop);
		nodes.clear();
	}

	@Test
	void outOfOrderCommitsAreBufferedAndAppliedContiguous() throws Exception {
		final OrchidNode primary = start("p1", List.of("r1"), MAX_IN_FLIGHT);
		final OrchidNode replica = start("r1", List.of("p1"), MAX_IN_FLIGHT);
		primary.onPeerAvailable("r1");
		replica.onPeerAvailable("p1");

		final List<Long> appliedOnReplica = new CopyOnWriteArrayList<>();
		replica.addApplyListener(op -> appliedOnReplica.add(op.opSeq()));

		final int batch = 16;
		final List<java.util.concurrent.CompletableFuture<Long>> futures = new ArrayList<>(batch);
		for (int i = 0; i < batch; i++) {
			final ReplicationOp op = OpLogCodec.withChecksum(new ReplicationOp(
					"holdback.demo", 0, i + 1L, ReplicationOpType.UPSERT,
					new byte[]{(byte) i}, new byte[]{(byte) (i + 10)}, 1L, 0L));
			futures.add(primary.appendAndWaitCommit(op));
		}
		long maxSeq = 0L;
		for (java.util.concurrent.CompletableFuture<Long> future : futures) {
			maxSeq = Math.max(maxSeq, future.get(AWAIT_MS, TimeUnit.MILLISECONDS));
		}

		final long expected = maxSeq;
		assertTrue(await(() -> replica.getLastCommittedSeq() >= expected),
				"replica must catch contiguous commits despite pipeline reorder");
		assertTrue(appliedOnReplica.size() >= batch);
		for (int i = 1; i < appliedOnReplica.size(); i++) {
			assertTrue(appliedOnReplica.get(i) >= appliedOnReplica.get(i - 1));
		}
	}

	@Test
	void directOutOfOrderOnCommitDrainsHoldback() throws Exception {
		final OrchidNode replica = start("r1", List.of(), MAX_IN_FLIGHT);
		final AtomicLong applied = new AtomicLong();
		replica.addApplyListener(op -> applied.set(op.opSeq()));

		final ReplicationOp op2 = OpLogCodec.withChecksum(new ReplicationOp(
				"holdback.direct", 0, 2L, ReplicationOpType.UPSERT,
				new byte[]{2}, new byte[]{20}, 1L, 0L));
		final ReplicationOp op1 = OpLogCodec.withChecksum(new ReplicationOp(
				"holdback.direct", 0, 1L, ReplicationOpType.UPSERT,
				new byte[]{1}, new byte[]{10}, 1L, 0L));

		replica.onCommit(new OrchidTransport.OrchidCommitMessage(
				"p1", 2L, OrchidNode.digestOf(op2), 1L, 2L, op2));
		assertTrue(await(() -> true));
		Thread.sleep(POLL_MS * 2L);
		assertEquals(0L, replica.getLastCommittedSeq(), "opSeq=2 must wait for 1");
		assertEquals(0L, applied.get());

		replica.onCommit(new OrchidTransport.OrchidCommitMessage(
				"p1", 1L, OrchidNode.digestOf(op1), 0L, 1L, op1));
		assertTrue(await(() -> replica.getLastCommittedSeq() == 2L),
				"holdback must drain 1 then 2");
		assertEquals(2L, applied.get());
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
			// Reverse peer iteration to stress holdback under reorder.
			final List<Map.Entry<String, OrchidNode>> entries = new ArrayList<>(nodes.entrySet());
			for (int i = entries.size() - 1; i >= 0; i--) {
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