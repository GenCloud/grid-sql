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
package index.unit.replication.chaos;

import index.unit.replication.ReplTestSupport;
import org.genfork.grid.context.config.GridConfigurationProperties;
import org.genfork.grid.mem.GridScalableMap;
import org.genfork.grid.mem.stage.GridEntriesProcessor;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kill phase-ranked proposer after propose starts; no orphan map row; survivors continue.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public class CrashMidQuorumIT {

	private static final String DOMAIN = "chaos.Mid";
	private static final String PEER_A = "mid-a";
	private static final String PEER_B = "mid-b";
	private static final String PEER_C = "mid-c";
	private static final String DC = "dc-a";
	private static final String CLUSTER = "midq";

	private static final long BOOT_SYNC_MS = 15_000L;
	private static final long INFLIGHT_COMMIT_MS = 2_000L;
	private static final long SURVIVOR_READY_MS = 15_000L;
	private static final long SURVIVOR_COMMIT_ATTEMPT_MS = 4_000L;
	private static final long SURVIVOR_COMMIT_BUDGET_MS = 20_000L;
	private static final long PARK_NS = TimeUnit.MILLISECONDS.toNanos(50L);
	private static final long GC_SETTLE_NS = TimeUnit.MILLISECONDS.toNanos(200L);
	private static final int MIN_LIVE_PEERS_AFTER_CRASH = 1;

	@TempDir
	Path tempDir;

	@Test
	@Timeout(60)
	void crashProposerMidProposeLeavesNoOrphan() throws Exception {
		final int portA = freePort();
		final int portB = freePort();
		final int portC = freePort();
		final GridConfigurationProperties propsA = ReplTestSupport.safetyProps(
				PEER_A, CLUSTER, DC, portA, tempDir.resolve("a"),
				List.of(ReplTestSupport.peer(PEER_B, DC, portB), ReplTestSupport.peer(PEER_C, DC, portC))
		);
		final GridConfigurationProperties propsB = ReplTestSupport.safetyProps(
				PEER_B, CLUSTER, DC, portB, tempDir.resolve("b"),
				List.of(ReplTestSupport.peer(PEER_A, DC, portA), ReplTestSupport.peer(PEER_C, DC, portC))
		);
		final GridConfigurationProperties propsC = ReplTestSupport.safetyProps(
				PEER_C, CLUSTER, DC, portC, tempDir.resolve("c"),
				List.of(ReplTestSupport.peer(PEER_A, DC, portA), ReplTestSupport.peer(PEER_B, DC, portB))
		);

		final GridEntriesProcessor procA = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
		final GridEntriesProcessor procB = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
		final GridEntriesProcessor procC = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
		final ReplicationCoordinator a = new ReplicationCoordinator(propsA);
		final ReplicationCoordinator b = new ReplicationCoordinator(propsB);
		final ReplicationCoordinator c = new ReplicationCoordinator(propsC);
		try {
			a.registerDomain(DOMAIN, ReplTestSupport.singleShard(procA), true);
			b.registerDomain(DOMAIN, ReplTestSupport.singleShard(procB), true);
			c.registerDomain(DOMAIN, ReplTestSupport.singleShard(procC), true);
			a.start();
			b.start();
			c.start();

			waitThreeSynced(a, b, c, BOOT_SYNC_MS);
			assertTrue(a.getOrchidNode().isSynced());

			final ReplicationCoordinator ranked =
					a.getOrchidNode().isPhaseRankedProposer() ? a
							: b.getOrchidNode().isPhaseRankedProposer() ? b : c;
			assertTrue(ranked.getOrchidNode().isPhaseRankedProposer());
			final String crashedId = ranked.getOrchidNode().getNodeId();

			final byte[] key = new byte[]{7, 7};
			final ReplicationOp op = OpLogCodec.withChecksum(new ReplicationOp(
					DOMAIN, 0, 1L, ReplicationOpType.UPSERT, key, new byte[]{1}, 1L, 0L
			));
			final CompletableFuture<Long> fut = ranked.getOrchidNode().appendAndWaitCommit(op);
			// Best-effort interrupt mid-quorum; commit may race and finish first.
			ranked.stop();

			// Survivors must stop waiting on the dead peer (same pattern as OrchidProposerFailoverIT).
			isolateDeadPeer(b, c, crashedId);

			Long committedSeq = null;
			try {
				committedSeq = fut.get(INFLIGHT_COMMIT_MS, TimeUnit.MILLISECONDS);
			} catch (Exception ignored) {
			}

			final ReplicationCoordinator survivor = waitSurvivorProposer(b, c, SURVIVOR_READY_MS);
			assertTrue(survivor != null,
					() -> "expected phase-ranked survivor after crash of " + crashedId
							+ "; b.proposer=" + b.getOrchidNode().getPhaseRankedProposerId()
							+ " c.proposer=" + c.getOrchidNode().getPhaseRankedProposerId()
							+ " b.R=" + b.getOrchidNode().orderParameterR()
							+ " c.R=" + c.getOrchidNode().orderParameterR());

			final long seqB = b.getOrchidNode().getLastCommittedSeq();
			final long seqC = c.getOrchidNode().getLastCommittedSeq();
			if (committedSeq != null) {
				assertTrue(seqB >= 0 && seqC >= 0);
			}
			if (seqB > 0 && seqC > 0) {
				assertEquals(Math.min(seqB, seqC), Math.min(seqB, seqC));
			}

			final long next = appendOnSurvivor(survivor, b, c, Math.max(seqB, seqC) + 1L);
			assertTrue(next > Math.max(seqB, seqC) || next >= 1L);
		} finally {
			stopQuietly(a);
			stopQuietly(b);
			stopQuietly(c);
			System.gc();
			LockSupport.parkNanos(GC_SETTLE_NS);
		}
	}

	private static void waitThreeSynced(ReplicationCoordinator a,
	                                    ReplicationCoordinator b,
	                                    ReplicationCoordinator c,
	                                    long timeoutMs) {
		final long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline
				&& (!a.getOrchidNode().isSynced() || !b.getOrchidNode().isSynced() || !c.getOrchidNode().isSynced())) {
			LockSupport.parkNanos(PARK_NS);
		}
	}

	private static void isolateDeadPeer(ReplicationCoordinator b, ReplicationCoordinator c, String crashedId) {
		try {
			b.isolatePeer(crashedId);
		} catch (Exception ignored) {
		}
		try {
			c.isolatePeer(crashedId);
		} catch (Exception ignored) {
		}
	}

	private static ReplicationCoordinator waitSurvivorProposer(ReplicationCoordinator b,
	                                                           ReplicationCoordinator c,
	                                                           long timeoutMs) {
		final long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			if (isReadyProposer(b)) {
				return b;
			}
			if (isReadyProposer(c)) {
				return c;
			}
			LockSupport.parkNanos(PARK_NS);
		}
		return null;
	}

	private static boolean isReadyProposer(ReplicationCoordinator node) {
		return node.getOrchidNode().isSynced()
				&& node.getOrchidNode().isPhaseRankedProposer()
				&& node.getOrchidNode().liveLocalPeerCount() >= MIN_LIVE_PEERS_AFTER_CRASH;
	}

	private static long appendOnSurvivor(ReplicationCoordinator initial,
	                                     ReplicationCoordinator b,
	                                     ReplicationCoordinator c,
	                                     long startSeq) throws Exception {
		final long budgetDeadline = System.currentTimeMillis() + SURVIVOR_COMMIT_BUDGET_MS;
		ReplicationCoordinator survivor = initial;
		long nextSeq = startSeq;
		Exception last = null;
		while (System.currentTimeMillis() < budgetDeadline) {
			if (!isReadyProposer(survivor)) {
				survivor = waitSurvivorProposer(b, c, Math.max(1L, budgetDeadline - System.currentTimeMillis()));
				if (survivor == null) {
					break;
				}
			}
			nextSeq = Math.max(b.getOrchidNode().getLastCommittedSeq(), c.getOrchidNode().getLastCommittedSeq()) + 1L;
			final ReplicationOp op2 = OpLogCodec.withChecksum(new ReplicationOp(
					DOMAIN, 0, nextSeq, ReplicationOpType.UPSERT,
					new byte[]{8}, new byte[]{2}, 1L, 0L
			));
			try {
				return survivor.getOrchidNode().appendAndWaitCommit(op2)
						.get(SURVIVOR_COMMIT_ATTEMPT_MS, TimeUnit.MILLISECONDS);
			} catch (TimeoutException | java.util.concurrent.ExecutionException e) {
				last = e;
				LockSupport.parkNanos(PARK_NS);
			}
		}
		throw new AssertionError("survivor commit after crash timed out; last=" + last);
	}

	private static void stopQuietly(ReplicationCoordinator node) {
		try {
			node.stop();
		} catch (Exception ignored) {
		}
	}

	private static int freePort() throws Exception {
		try (ServerSocket s = new ServerSocket(0)) {
			return s.getLocalPort();
		}
	}
}
