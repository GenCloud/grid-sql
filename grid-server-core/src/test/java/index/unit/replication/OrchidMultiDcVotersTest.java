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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hierarchical multi-DC: local R + remote digest voters (unit, in-process transport).
 */
public class OrchidMultiDcVotersTest {

	private final Map<String, OrchidNode> nodes = new ConcurrentHashMap<>();
	private final MeshTransport mesh = new MeshTransport(nodes);

	@AfterEach
	void tearDown() {
		nodes.values().forEach(OrchidNode::stop);
		nodes.clear();
	}

	@Test
	void remoteVoterDigestRequiredBeforeCommit() throws Exception {
		final OrchidNode local = start("dc-a-1", java.util.List.of(),
				OrchidMultiDcConfig.of(java.util.List.of("dc-b-1"), false, 2_000L));
		final OrchidNode remote = start("dc-b-1", java.util.List.of(), OrchidMultiDcConfig.NONE);
		local.onPeerAvailable("dc-b-1");
		remote.onPeerAvailable("dc-a-1");

		final ReplicationOp op = OpLogCodec.withChecksum(new ReplicationOp(
				"demo.MultiDc", 0, 1L, ReplicationOpType.UPSERT, new byte[]{1}, new byte[]{2}, 1L, 0L
		));
		final long seq = local.appendAndWaitCommit(op).get(3, TimeUnit.SECONDS);
		assertTrue(seq >= 1);
		final long deadline = System.currentTimeMillis() + 3_000L;
		while (System.currentTimeMillis() < deadline && remote.getLastCommittedSeq() < seq) {
			Thread.sleep(20L);
		}
		assertTrue(remote.getLastCommittedSeq() >= seq);
	}

	@Test
	void remoteVoterPartitionFailClosed() {
		final OrchidNode local = start("dc-a-1", java.util.List.of(),
				OrchidMultiDcConfig.of(java.util.List.of("dc-b-missing"), false, 200L));
		// No remote node registered → propose never ACKed.
		final ReplicationOp op = OpLogCodec.withChecksum(new ReplicationOp(
				"demo.MultiDc", 0, 1L, ReplicationOpType.UPSERT, new byte[]{3}, new byte[]{4}, 1L, 0L
		));
		final var ex = assertThrows(Exception.class, () -> local.appendAndWaitCommit(op).get(3, TimeUnit.SECONDS));
		Throwable cause = ex;
		while (cause.getCause() != null) {
			cause = cause.getCause();
		}
		assertTrue(cause instanceof OrchidNotSyncedException, "expected OrchidNotSyncedException, got " + cause);
		assertTrue(cause.getMessage().contains("remote voter digest timeout"), cause.getMessage());
	}

	@Test
	void delayedRemoteAckStillCommits() throws Exception {
		mesh.setDelayMs("dc-b-1", 80);
		final OrchidNode local = start("dc-a-1", java.util.List.of(),
				OrchidMultiDcConfig.of(java.util.List.of("dc-b-1"), false, 2_000L));
		final OrchidNode remote = start("dc-b-1", java.util.List.of(), OrchidMultiDcConfig.NONE);
		local.onPeerAvailable("dc-b-1");
		remote.onPeerAvailable("dc-a-1");

		final ReplicationOp op = OpLogCodec.withChecksum(new ReplicationOp(
				"demo.MultiDc", 0, 1L, ReplicationOpType.UPSERT, new byte[]{5}, new byte[]{6}, 1L, 0L
		));
		final long started = System.currentTimeMillis();
		final long seq = local.appendAndWaitCommit(op).get(3, TimeUnit.SECONDS);
		assertTrue(seq >= 1);
		assertTrue(System.currentTimeMillis() - started >= 60, "expected WAN-like delay on remote digest");
	}

	@Test
	void phaseCouplingDefaultOffKeepsLocalRSolo() {
		final OrchidNode local = start("dc-a-1", java.util.List.of(),
				OrchidMultiDcConfig.of(java.util.List.of("dc-b-1"), false, 2_000L));
		final OrchidNode remote = start("dc-b-1", java.util.List.of(), OrchidMultiDcConfig.NONE);
		local.onPeerAvailable("dc-b-1");
		remote.onPeerAvailable("dc-a-1");
		// Without phase coupling, local N=1 stays synced even if remote phase is unseen for R.
		assertTrue(local.isSynced());
		assertTrue(local.orderParameterR() >= 0.99);
	}

	@Test
	void applyRemoteVotersUpdatesDigestVotersPreservingPhaseCoupling() {
		final OrchidNode local = start("dc-a-1", java.util.List.of(),
				OrchidMultiDcConfig.of(java.util.List.of("dc-b-1"), true, 1_500L));
		assertEquals(java.util.Set.of("dc-b-1"), local.multiDcConfig().remoteVoterIds());
		assertTrue(local.multiDcConfig().phaseCoupling());
		assertEquals(1_500L, local.multiDcConfig().remoteVoterTimeoutMs());
		assertEquals(1, local.configuredVoterCount(), "local voter count is self-only (no YAML peers)");

		local.applyRemoteVoters(java.util.List.of("dc-c-1", "dc-d-1"));
		assertEquals(java.util.Set.of("dc-c-1", "dc-d-1"), local.multiDcConfig().remoteVoterIds());
		assertTrue(local.multiDcConfig().phaseCoupling(), "phaseCoupling must be preserved");
		assertEquals(1_500L, local.multiDcConfig().remoteVoterTimeoutMs());
		assertEquals(1, local.configuredVoterCount(), "apply must not shrink/expand local Kuramoto voter count");
	}

	@Test
	void applyVoterSetHintsGateOnlyAppliesWhenEnabled() {
		final OrchidNode local = start("dc-a-1", java.util.List.of(),
				OrchidMultiDcConfig.of(java.util.List.of("dc-b-1"), false, 2_000L));
		final java.util.List<String> recommended = java.util.List.of("dc-c-1");

		// Mirror ReplicationCoordinator.swarmTick gate (apply-voter-set-hints=false default).
		final boolean applyOff = false;
		if (applyOff) {
			local.applyRemoteVoters(recommended);
		}
		assertEquals(java.util.Set.of("dc-b-1"), local.multiDcConfig().remoteVoterIds());

		final boolean applyOn = true;
		if (applyOn) {
			local.applyRemoteVoters(recommended);
		}
		assertEquals(java.util.Set.of("dc-c-1"), local.multiDcConfig().remoteVoterIds());
	}

	@Test
	void sealBufferedCrossDcProposesAfterMissedCommit() throws Exception {
		mesh.setDropCommits(true);
		final OrchidNode local = start("dc-a-1", java.util.List.of(),
				OrchidMultiDcConfig.of(java.util.List.of("dc-b-1"), false, 2_000L));
		final OrchidNode remote = start("dc-b-1", java.util.List.of(), OrchidMultiDcConfig.NONE);
		local.onPeerAvailable("dc-b-1");
		remote.onPeerAvailable("dc-a-1");

		final AtomicInteger applied = new AtomicInteger();
		remote.addApplyListener(op -> applied.incrementAndGet());

		final ReplicationOp op = OpLogCodec.withChecksum(new ReplicationOp(
				"demo.Seal", 0, 1L, ReplicationOpType.UPSERT, new byte[]{7}, new byte[]{8}, 1L, 0L
		));
		final long seq = local.appendAndWaitCommit(op).get(3, TimeUnit.SECONDS);
		assertTrue(seq >= 1L);
		assertEquals(0L, remote.getLastCommittedSeq(), "commit dropped — tip must stay behind");
		assertEquals(0, applied.get());

		final int sealed = remote.sealBufferedCrossDcProposes();
		assertTrue(sealed >= 1, "claim-time seal must apply digested propose");
		assertTrue(remote.getLastCommittedSeq() >= seq);
		assertTrue(applied.get() >= 1);
	}

	@Test
	void refuseProposeWhenPeerTipAhead() throws Exception {
		final OrchidNode local = start("dc-a-1", java.util.List.of(), OrchidMultiDcConfig.NONE);
		local.onPeerAvailable("peer-ahead");
		local.onPhase(new OrchidTransport.OrchidPhaseMessage(
				"peer-ahead", 0.0, 1.0, 50L, 0L, 0L));
		final long tipDeadline = System.currentTimeMillis() + 2_000L;
		while (System.currentTimeMillis() < tipDeadline && local.maxSeenPeerCommittedSeq() < 50L) {
			Thread.sleep(10L);
		}
		assertEquals(50L, local.maxSeenPeerCommittedSeq());

		final ReplicationOp op = OpLogCodec.withChecksum(new ReplicationOp(
				"demo.Tip", 0, 1L, ReplicationOpType.UPSERT, new byte[]{1}, new byte[]{2}, 1L, 0L
		));
		final Exception ex = assertThrows(Exception.class,
				() -> local.appendAndWaitCommit(op).get(2, TimeUnit.SECONDS));
		Throwable cause = ex;
		while (cause.getCause() != null) {
			cause = cause.getCause();
		}
		assertTrue(cause instanceof OrchidNotSyncedException, "got " + cause);
		assertTrue(cause.getMessage().contains("tip behind peers"), cause.getMessage());
	}

	private OrchidNode start(String id, java.util.List<String> localPeers, OrchidMultiDcConfig multiDc) {
		final OrchidNode node = new OrchidNode(
				id, 15.0, 1.0, 0.0, 10, DigestQuorum.MAJORITY, mesh, localPeers, null, false, multiDc
		);
		nodes.put(id, node);
		node.start();
		return node;
	}

	/** In-process mesh with optional per-destination delay (simulates WAN RTT). */
	static final class MeshTransport implements OrchidTransport {
		private final Map<String, OrchidNode> nodes;
		private final Map<String, Integer> delayMs = new ConcurrentHashMap<>();
		private final AtomicInteger delayed = new AtomicInteger();
		private volatile boolean dropCommits;

		MeshTransport(Map<String, OrchidNode> nodes) {
			this.nodes = nodes;
		}

		void setDelayMs(String toNodeId, int ms) {
			delayMs.put(toNodeId, ms);
		}

		void setDropCommits(boolean drop) {
			dropCommits = drop;
		}

		private void deliver(String toNodeId, Runnable action) {
			final OrchidNode target = nodes.get(toNodeId);
			if (target == null) {
				return;
			}
			final int delay = delayMs.getOrDefault(toNodeId, 0);
			if (delay <= 0) {
				action.run();
				return;
			}
			delayed.incrementAndGet();
			Thread.ofVirtual().start(() -> {
				try {
					Thread.sleep(delay);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
				action.run();
			});
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
			if (dropCommits) {
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
			if (dropCommits) {
				return;
			}
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
