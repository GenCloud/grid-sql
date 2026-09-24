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
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * After the phase-ranked proposer is removed from the live view, another majority node
 * becomes proposer and commits a single slot (no fork).
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public class OrchidProposerFailoverIT {

	private static final String DOMAIN = "chaos.Pf";

	@TempDir(cleanup = CleanupMode.NEVER)
	Path tempDir;

	@Test
	void afterKillingPhaseRankedProposerAnotherCommitsWithoutFork() throws Exception {
		final int portA = freePort();
		final int portB = freePort();
		final int portC = freePort();
		// Lexicographic ids: pf-a is phase-ranked when all three are seen.
		final GridConfigurationProperties propsA = ReplTestSupport.safetyProps(
				"pf-a", "pf", "dc-a", portA, tempDir.resolve("a"),
				List.of(ReplTestSupport.peer("pf-b", "dc-a", portB), ReplTestSupport.peer("pf-c", "dc-a", portC))
		);
		final GridConfigurationProperties propsB = ReplTestSupport.safetyProps(
				"pf-b", "pf", "dc-a", portB, tempDir.resolve("b"),
				List.of(ReplTestSupport.peer("pf-a", "dc-a", portA), ReplTestSupport.peer("pf-c", "dc-a", portC))
		);
		final GridConfigurationProperties propsC = ReplTestSupport.safetyProps(
				"pf-c", "pf", "dc-a", portC, tempDir.resolve("c"),
				List.of(ReplTestSupport.peer("pf-a", "dc-a", portA), ReplTestSupport.peer("pf-b", "dc-a", portB))
		);

		final ReplicationCoordinator a = new ReplicationCoordinator(propsA);
		final ReplicationCoordinator b = new ReplicationCoordinator(propsB);
		final ReplicationCoordinator c = new ReplicationCoordinator(propsC);
		try {
			final GridEntriesProcessor procA = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
			final GridEntriesProcessor procB = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
			final GridEntriesProcessor procC = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
			a.registerDomain(DOMAIN, ReplTestSupport.singleShard(procA), true);
			b.registerDomain(DOMAIN, ReplTestSupport.singleShard(procB), true);
			c.registerDomain(DOMAIN, ReplTestSupport.singleShard(procC), true);
			a.start();
			b.start();
			c.start();

			waitThreeSyncedWithProposer(a, b, c, "pf-a", 15_000);
			assertEquals("pf-a", a.getOrchidNode().getPhaseRankedProposerId());
			assertEquals("pf-a", b.getOrchidNode().getPhaseRankedProposerId());

			// Kill current proposer from majority view (configured N stays 3 on survivors).
			b.isolatePeer("pf-a");
			c.isolatePeer("pf-a");
			a.isolatePeer("pf-b");
			a.isolatePeer("pf-c");
			try {
				a.stop();
			} catch (Exception ignored) {
			}

			final long deadline = System.currentTimeMillis() + 12_000;
			boolean majorityReady = false;
			while (System.currentTimeMillis() < deadline) {
				if (b.getOrchidNode().isSynced() && c.getOrchidNode().isSynced()
						&& b.getOrchidNode().liveLocalPeerCount() >= 1
						&& "pf-b".equals(b.getOrchidNode().getPhaseRankedProposerId())
						&& b.getOrchidNode().isPhaseRankedProposer()) {
					majorityReady = true;
					break;
				}
				LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
			}
			assertTrue(majorityReady,
					() -> "expected pf-b proposer after pf-a kill; b.proposer="
							+ b.getOrchidNode().getPhaseRankedProposerId()
							+ " b.R=" + b.getOrchidNode().orderParameterR()
							+ " c.R=" + c.getOrchidNode().orderParameterR());
			assertNotEquals("pf-a", b.getOrchidNode().getPhaseRankedProposerId());
			assertEquals(0L, b.getOrchidNode().getLastCommittedSeq(), "clean log before failover write");
			assertEquals(0L, c.getOrchidNode().getLastCommittedSeq(), "clean log before failover write");

			final ReplicationOp steal = OpLogCodec.withChecksum(new ReplicationOp(
					DOMAIN, 0, 1L, ReplicationOpType.UPSERT, new byte[]{1}, new byte[]{1}, 1L, 0L
			));
			assertThrows(CompletionException.class, () -> c.getOrchidNode().appendAndWaitCommit(steal).join());
			// Non-proposer must not leave a committed slot on the majority.
			assertEquals(0L, b.getOrchidNode().getLastCommittedSeq(), "steal must not commit on majority");
			assertEquals(0L, c.getOrchidNode().getLastCommittedSeq(), "steal must not commit on follower");

			final ReplicationOp win = OpLogCodec.withChecksum(new ReplicationOp(
					DOMAIN, 0, 1L, ReplicationOpType.UPSERT, new byte[]{7}, new byte[]{8}, 1L, 0L
			));
			final long seq = b.getOrchidNode().appendAndWaitCommit(win).get(8, TimeUnit.SECONDS);
			assertTrue(seq >= 1L, "proposer commit must mint opSeq");

			final long syncDeadline = System.currentTimeMillis() + 8_000;
			while (System.currentTimeMillis() < syncDeadline
					&& c.getOrchidNode().getLastCommittedSeq() < seq) {
				LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
			}
			assertEquals(seq, b.getOrchidNode().getLastCommittedSeq());
			assertEquals(seq, c.getOrchidNode().getLastCommittedSeq(), "no forked commit on survivors");
			assertEquals(0L, b.getOrchidNode().pendingProposeAgeMs());
		} finally {
			stopQuietly(a);
			stopQuietly(b);
			stopQuietly(c);
		}
	}

	private static void waitThreeSyncedWithProposer(ReplicationCoordinator a,
	                                               ReplicationCoordinator b,
	                                               ReplicationCoordinator c,
	                                               String expectedProposer,
	                                               long timeoutMs) {
		final long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			if (a.getOrchidNode().isSynced() && b.getOrchidNode().isSynced() && c.getOrchidNode().isSynced()
					&& expectedProposer.equals(a.getOrchidNode().getPhaseRankedProposerId())
					&& a.getOrchidNode().isPhaseRankedProposer()) {
				return;
			}
			LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
		}
		assertTrue(a.getOrchidNode().isPhaseRankedProposer(),
				() -> "expected proposer " + expectedProposer
						+ " got " + a.getOrchidNode().getPhaseRankedProposerId()
						+ " a.R=" + a.getOrchidNode().orderParameterR());
	}

	private static void stopQuietly(ReplicationCoordinator coord) {
		try {
			coord.stop();
		} catch (Exception ignored) {
		}
	}

	private static int freePort() throws Exception {
		try (ServerSocket s = new ServerSocket(0)) {
			return s.getLocalPort();
		}
	}
}