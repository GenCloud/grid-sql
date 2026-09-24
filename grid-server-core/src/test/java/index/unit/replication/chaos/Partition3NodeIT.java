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
import org.genfork.grid.mem.GridScalableMap;
import org.genfork.grid.mem.stage.GridEntriesProcessor;
import org.genfork.grid.replication.OrchidNotSyncedException;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.OpLogSegment;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 3-node partition: majority writes; minority refuses; heal + catch-up.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public class Partition3NodeIT {

	@TempDir(cleanup = CleanupMode.NEVER)
	Path tempDir;

	@Test
	void majorityWritesMinorityRefusesThenHeal() throws Exception {
		final int portA = freePort();
		final int portB = freePort();
		final int portC = freePort();
		final var propsA = ReplTestSupport.safetyProps(
				"p3-a", "p3", "dc-a", portA, tempDir.resolve("a"),
				List.of(ReplTestSupport.peer("p3-b", "dc-a", portB), ReplTestSupport.peer("p3-c", "dc-a", portC))
		);
		final var propsB = ReplTestSupport.safetyProps(
				"p3-b", "p3", "dc-a", portB, tempDir.resolve("b"),
				List.of(ReplTestSupport.peer("p3-a", "dc-a", portA), ReplTestSupport.peer("p3-c", "dc-a", portC))
		);
		final var propsC = ReplTestSupport.safetyProps(
				"p3-c", "p3", "dc-a", portC, tempDir.resolve("c"),
				List.of(ReplTestSupport.peer("p3-a", "dc-a", portA), ReplTestSupport.peer("p3-b", "dc-a", portB))
		);

		final GridEntriesProcessor procA = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
		final GridEntriesProcessor procB = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
		final GridEntriesProcessor procC = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
		final ReplicationCoordinator a = new ReplicationCoordinator(propsA);
		final ReplicationCoordinator b = new ReplicationCoordinator(propsB);
		final ReplicationCoordinator c = new ReplicationCoordinator(propsC);
		try {
			a.registerDomain("chaos.P3", ReplTestSupport.singleShard(procA), true);
			b.registerDomain("chaos.P3", ReplTestSupport.singleShard(procB), true);
			c.registerDomain("chaos.P3", ReplTestSupport.singleShard(procC), true);
			a.start();
			b.start();
			c.start();

			final long deadline = System.currentTimeMillis() + 15_000;
			while (System.currentTimeMillis() < deadline
					&& (!a.getOrchidNode().isSynced() || !b.getOrchidNode().isSynced() || !c.getOrchidNode().isSynced()
					|| !a.getOrchidNode().isPhaseRankedProposer())) {
				Thread.sleep(50);
			}
			assertTrue(a.getOrchidNode().isPhaseRankedProposer());

			// Isolate minority c (keep configured N=3 — do not unconfigure / solo-write)
			a.isolatePeer("p3-c");
			b.isolatePeer("p3-c");
			c.isolatePeer("p3-a");
			c.isolatePeer("p3-b");

			assertThrows(OrchidNotSyncedException.class, c::ensureOrchidSynced);
			final ReplicationOp minorityOp = OpLogCodec.withChecksum(new ReplicationOp(
					"chaos.P3", 0, 1L, ReplicationOpType.UPSERT, new byte[]{9}, new byte[]{9}, 1L, 0L
			));
			assertThrows(CompletionException.class, () -> c.getOrchidNode().appendAndWaitCommit(minorityOp).join());

			final long majDeadline = System.currentTimeMillis() + 5_000;
			while (System.currentTimeMillis() < majDeadline
					&& (!a.getOrchidNode().isSynced() || !b.getOrchidNode().isSynced()
					|| !a.getOrchidNode().isPhaseRankedProposer())) {
				Thread.sleep(50);
			}
			assertTrue(a.getOrchidNode().isSynced(), "majority must stay writable after isolating minority");

			final byte[] key = new byte[]{1, 1};
			final byte[] value = new byte[]{2, 2};
			final ReplicationOp op = OpLogCodec.withChecksum(new ReplicationOp(
					"chaos.P3", 0, 1L, ReplicationOpType.UPSERT, key, value, 1L, 0L
			));
			final long seq = a.getOrchidNode().appendAndWaitCommit(op).get(8, TimeUnit.SECONDS);
			final ReplicationOp committed = OpLogCodec.withChecksum(new ReplicationOp(
					op.domainType(), op.shard(), seq, op.type(), op.key(), op.value(), op.schemaEpoch(), 0L
			));
			a.getOpLog().append(committed);
			a.getOrchidNode().confirmPersisted(seq);
			procA.installCommitted(key, value, false);

			// Heal: restore live channels; configured membership unchanged
			a.reconnectPeer("p3-c");
			b.reconnectPeer("p3-c");
			c.reconnectPeer("p3-a");
			c.reconnectPeer("p3-b");

			final List<ReplicationOp> catchUp = a.getOpLog().readFrom("chaos.P3", 0, 1, 100);
			assertTrue(catchUp.size() >= 1);
			a.getNettyTransport().pushSegment("p3-c", new OpLogSegment(
					"chaos.P3", 0, catchUp.getFirst().opSeq(), catchUp.getLast().opSeq(), catchUp,
					OpLogCodec.segmentChecksum(catchUp)
			));

			final long applyDeadline = System.currentTimeMillis() + 10_000;
			while (System.currentTimeMillis() < applyDeadline && procC.get(key) == null) {
				Thread.sleep(50);
			}
			assertArrayEquals(value, procC.get(key));
		} finally {
			try { a.stop(); } catch (Exception ignored) {}
			try { b.stop(); } catch (Exception ignored) {}
			try { c.stop(); } catch (Exception ignored) {}
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
