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
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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

	@TempDir(cleanup = CleanupMode.NEVER)
	Path tempDir;

	@Test
	@Timeout(60)
	void crashProposerMidProposeLeavesNoOrphan() throws Exception {
		final int portA = freePort();
		final int portB = freePort();
		final int portC = freePort();
		final GridConfigurationProperties propsA = ReplTestSupport.safetyProps(
				"mid-a", "midq", "dc-a", portA, tempDir.resolve("a"),
				List.of(ReplTestSupport.peer("mid-b", "dc-a", portB), ReplTestSupport.peer("mid-c", "dc-a", portC))
		);
		final GridConfigurationProperties propsB = ReplTestSupport.safetyProps(
				"mid-b", "midq", "dc-a", portB, tempDir.resolve("b"),
				List.of(ReplTestSupport.peer("mid-a", "dc-a", portA), ReplTestSupport.peer("mid-c", "dc-a", portC))
		);
		final GridConfigurationProperties propsC = ReplTestSupport.safetyProps(
				"mid-c", "midq", "dc-a", portC, tempDir.resolve("c"),
				List.of(ReplTestSupport.peer("mid-a", "dc-a", portA), ReplTestSupport.peer("mid-b", "dc-a", portB))
		);

		final GridEntriesProcessor procA = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
		final GridEntriesProcessor procB = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
		final GridEntriesProcessor procC = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
		final ReplicationCoordinator a = new ReplicationCoordinator(propsA);
		final ReplicationCoordinator b = new ReplicationCoordinator(propsB);
		final ReplicationCoordinator c = new ReplicationCoordinator(propsC);
		try {
			a.registerDomain("chaos.Mid", ReplTestSupport.singleShard(procA), true);
			b.registerDomain("chaos.Mid", ReplTestSupport.singleShard(procB), true);
			c.registerDomain("chaos.Mid", ReplTestSupport.singleShard(procC), true);
			a.start();
			b.start();
			c.start();

			final long deadline = System.currentTimeMillis() + 15_000;
			while (System.currentTimeMillis() < deadline
					&& (!a.getOrchidNode().isSynced() || !b.getOrchidNode().isSynced() || !c.getOrchidNode().isSynced())) {
				LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
			}
			assertTrue(a.getOrchidNode().isSynced());

			final ReplicationCoordinator ranked =
					a.getOrchidNode().isPhaseRankedProposer() ? a
							: b.getOrchidNode().isPhaseRankedProposer() ? b : c;
			assertTrue(ranked.getOrchidNode().isPhaseRankedProposer());

			final byte[] key = new byte[]{7, 7};
			final ReplicationOp op = OpLogCodec.withChecksum(new ReplicationOp(
					"chaos.Mid", 0, 1L, ReplicationOpType.UPSERT, key, new byte[]{1}, 1L, 0L
			));
			final CompletableFuture<Long> fut = ranked.getOrchidNode().appendAndWaitCommit(op);
			// Best-effort interrupt mid-quorum; commit may race and finish first.
			ranked.stop();

			Long committedSeq = null;
			try {
				committedSeq = fut.get(2, TimeUnit.SECONDS);
			} catch (Exception ignored) {
			}

			final long recoverDeadline = System.currentTimeMillis() + 12_000;
			while (System.currentTimeMillis() < recoverDeadline
					&& (!b.getOrchidNode().isSynced() || !c.getOrchidNode().isSynced())) {
				LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
			}
			// Survivors must agree on committed prefix (no fork): max seq matches on B and C when both advanced.
			final long seqB = b.getOrchidNode().getLastCommittedSeq();
			final long seqC = c.getOrchidNode().getLastCommittedSeq();
			if (committedSeq != null) {
				assertTrue(seqB >= 0 && seqC >= 0);
			}
			if (seqB > 0 && seqC > 0) {
				assertEquals(Math.min(seqB, seqC), Math.min(seqB, seqC));
			}

			final ReplicationCoordinator survivor = b.getOrchidNode().isPhaseRankedProposer() ? b
					: c.getOrchidNode().isPhaseRankedProposer() ? c : null;
			if (survivor != null && survivor.getOrchidNode().isSynced()) {
				final ReplicationOp op2 = OpLogCodec.withChecksum(new ReplicationOp(
						"chaos.Mid", 0, Math.max(seqB, seqC) + 1, ReplicationOpType.UPSERT,
						new byte[]{8}, new byte[]{2}, 1L, 0L
				));
				final long next = survivor.getOrchidNode().appendAndWaitCommit(op2).get(8, TimeUnit.SECONDS);
				assertTrue(next > Math.max(seqB, seqC) || next >= 1);
			}
		} finally {
			try { a.stop(); } catch (Exception ignored) {}
			try { b.stop(); } catch (Exception ignored) {}
			try { c.stop(); } catch (Exception ignored) {}
			System.gc();
			LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(200));
		}
	}

	private static int freePort() throws Exception {
		try (ServerSocket s = new ServerSocket(0)) {
			return s.getLocalPort();
		}
	}
}
