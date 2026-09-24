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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.io.CleanupMode;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dual propose race under safetyProps: only phase-ranked commit wins.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public class DualProposeRaceIT {

	@TempDir(cleanup = CleanupMode.NEVER)
	Path tempDir;

	@Test
	void raceSamePrevSeqOneDigest() throws Exception {
		final int portA = freePort();
		final int portB = freePort();
		final GridConfigurationProperties propsA = ReplTestSupport.safetyProps(
				"race-a", "race", "dc-a", portA, tempDir.resolve("a"),
				List.of(ReplTestSupport.peer("race-b", "dc-a", portB))
		);
		final GridConfigurationProperties propsB = ReplTestSupport.safetyProps(
				"race-b", "race", "dc-a", portB, tempDir.resolve("b"),
				List.of(ReplTestSupport.peer("race-a", "dc-a", portA))
		);

		final ReplicationCoordinator a = new ReplicationCoordinator(propsA);
		final ReplicationCoordinator b = new ReplicationCoordinator(propsB);
		try {
			final GridEntriesProcessor procA = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
			final GridEntriesProcessor procB = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
			a.registerDomain("chaos.Race", ReplTestSupport.singleShard(procA), true);
			b.registerDomain("chaos.Race", ReplTestSupport.singleShard(procB), true);
			a.start();
			b.start();

			final long deadline = System.currentTimeMillis() + 12_000;
			while (System.currentTimeMillis() < deadline
					&& (!a.getOrchidNode().isSynced() || !b.getOrchidNode().isSynced())) {
				java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(50));
			}
			assertTrue(a.getOrchidNode().isSynced() && b.getOrchidNode().isSynced());

			final ReplicationCoordinator ranked = a.getOrchidNode().isPhaseRankedProposer() ? a : b;
			final ReplicationCoordinator other = ranked == a ? b : a;

			final ReplicationOp steal = OpLogCodec.withChecksum(new ReplicationOp(
					"chaos.Race", 0, 1L, ReplicationOpType.UPSERT, new byte[]{1}, new byte[]{1}, 1L, 0L
			));
			assertThrows(CompletionException.class, () -> other.getOrchidNode().appendAndWaitCommit(steal).join());

			final ReplicationOp win = OpLogCodec.withChecksum(new ReplicationOp(
					"chaos.Race", 0, 1L, ReplicationOpType.UPSERT, new byte[]{2}, new byte[]{2}, 1L, 0L
			));
			final long seq = ranked.getOrchidNode().appendAndWaitCommit(win).get(8, TimeUnit.SECONDS);
			assertEquals(1L, seq);

			final CompletableFuture<Long> again = other.getOrchidNode().appendAndWaitCommit(steal);
			assertThrows(Exception.class, () -> again.get(2, TimeUnit.SECONDS));
		} finally {
			try { a.stop(); } catch (Exception ignored) {}
			try { b.stop(); } catch (Exception ignored) {}
			System.gc();
			try { Thread.sleep(200); } catch (InterruptedException ignored) {}
		}
	}

	private static int freePort() throws Exception {
		try (ServerSocket s = new ServerSocket(0)) {
			return s.getLocalPort();
		}
	}
}
