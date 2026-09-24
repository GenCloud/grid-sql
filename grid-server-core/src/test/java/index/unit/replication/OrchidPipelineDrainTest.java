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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Primary commit drain must stay iterative under pipeline depth (no StackOverflowError).
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
class OrchidPipelineDrainTest {
	private static final int MAX_IN_FLIGHT = 64;
	private static final int PIPELINE_DEPTH = 64;
	private static final long AWAIT_MS = 15_000L;
	private static final int HEAVY_APPLY_SPIN = 2_000;

	private final Map<String, OrchidNode> nodes = new ConcurrentHashMap<>();
	private final MeshTransport mesh = new MeshTransport(nodes);

	@AfterEach
	void tearDown() {
		nodes.values().forEach(OrchidNode::stop);
		nodes.clear();
	}

	@Test
	void soloPipelineCommitsWithoutStackOverflow() throws Exception {
		final OrchidNode solo = start("solo-1", List.of(), MAX_IN_FLIGHT);
		final AtomicInteger applied = new AtomicInteger();
		solo.addApplyListener(op -> {
			int x = 0;
			for (int i = 0; i < HEAVY_APPLY_SPIN; i++) {
				x += i;
			}
			if (x >= 0) {
				applied.incrementAndGet();
			}
		});

		final List<CompletableFuture<Long>> futures = new ArrayList<>(PIPELINE_DEPTH);
		for (int i = 0; i < PIPELINE_DEPTH; i++) {
			final ReplicationOp op = OpLogCodec.withChecksum(new ReplicationOp(
					"pipeline.drain", 0, i + 1L, ReplicationOpType.UPSERT,
					new byte[]{(byte) i}, new byte[]{(byte) (i + 1)}, 1L, 0L));
			futures.add(solo.appendAndWaitCommit(op));
		}

		long prev = 0L;
		for (CompletableFuture<Long> future : futures) {
			final long seq = future.get(AWAIT_MS, TimeUnit.MILLISECONDS);
			assertTrue(seq == prev + 1L, "contiguous opSeq expected prev=" + prev + " got=" + seq);
			prev = seq;
		}
		assertEquals(PIPELINE_DEPTH, applied.get());
		assertEquals(PIPELINE_DEPTH, solo.getLastCommittedSeq());
	}

	@Test
	void twoNodePipelineCommitsWithoutStackOverflow() throws Exception {
		final OrchidNode primary = start("p1", List.of("r1"), MAX_IN_FLIGHT);
		final OrchidNode replica = start("r1", List.of("p1"), MAX_IN_FLIGHT);
		primary.onPeerAvailable("r1");
		replica.onPeerAvailable("p1");

		final AtomicInteger appliedPrimary = new AtomicInteger();
		primary.addApplyListener(op -> appliedPrimary.incrementAndGet());

		final int batch = MAX_IN_FLIGHT;
		final List<CompletableFuture<Long>> futures = new ArrayList<>(batch);
		for (int i = 0; i < batch; i++) {
			final ReplicationOp op = OpLogCodec.withChecksum(new ReplicationOp(
					"pipeline.ha", 0, i + 1L, ReplicationOpType.UPSERT,
					new byte[]{(byte) i}, new byte[]{(byte) (i + 3)}, 1L, 0L));
			futures.add(primary.appendAndWaitCommit(op));
		}
		long maxSeq = 0L;
		for (CompletableFuture<Long> future : futures) {
			maxSeq = Math.max(maxSeq, future.get(AWAIT_MS, TimeUnit.MILLISECONDS));
		}
		assertEquals(batch, maxSeq);
		assertEquals(batch, appliedPrimary.get());
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
			for (Map.Entry<String, OrchidNode> e : nodes.entrySet()) {
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