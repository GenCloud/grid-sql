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
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.OpLogSegment;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Linearizability smoke: sequential commits appear in same order on both nodes after ship.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public class LinearizabilityIT {

	@TempDir
	Path tempDir;

	@Test
	void sequentialWritesPreserveOrderOnReplica() throws Exception {
		final int portA = freePort();
		final int portB = freePort();
		final var propsA = ReplTestSupport.safetyProps(
				"lin-a", "lin", "dc-a", portA, tempDir.resolve("a"),
				List.of(ReplTestSupport.peer("lin-b", "dc-a", portB))
		);
		final var propsB = ReplTestSupport.safetyProps(
				"lin-b", "lin", "dc-a", portB, tempDir.resolve("b"),
				List.of(ReplTestSupport.peer("lin-a", "dc-a", portA))
		);

		final GridEntriesProcessor procA = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
		final GridEntriesProcessor procB = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
		final ReplicationCoordinator a = new ReplicationCoordinator(propsA);
		final ReplicationCoordinator b = new ReplicationCoordinator(propsB);
		try {
			a.registerDomain("chaos.Lin", ReplTestSupport.singleShard(procA), true);
			b.registerDomain("chaos.Lin", ReplTestSupport.singleShard(procB), true);
			a.start();
			b.start();

			final long deadline = System.currentTimeMillis() + 12_000;
			while (System.currentTimeMillis() < deadline
					&& (!a.getOrchidNode().isSynced() || !b.getOrchidNode().isSynced()
					|| !a.getOrchidNode().isPhaseRankedProposer())) {
				Thread.sleep(50);
			}
			assertTrue(a.getOrchidNode().isPhaseRankedProposer());

			final List<ReplicationOp> committed = new ArrayList<>();
			for (int i = 0; i < 5; i++) {
				final byte[] key = new byte[]{(byte) i};
				final byte[] value = new byte[]{(byte) (10 + i)};
				final ReplicationOp raw = OpLogCodec.withChecksum(new ReplicationOp(
						"chaos.Lin", 0, i + 1L, ReplicationOpType.UPSERT, key, value, 1L, 0L
				));
				final long seq = a.getOrchidNode().appendAndWaitCommit(raw).get(8, TimeUnit.SECONDS);
				final ReplicationOp op = OpLogCodec.withChecksum(new ReplicationOp(
						raw.domainType(), raw.shard(), seq, raw.type(), raw.key(), raw.value(), raw.schemaEpoch(), 0L
				));
				a.getOpLog().append(op);
				a.getOrchidNode().confirmPersisted(seq);
				procA.installCommitted(key, value, false);
				committed.add(op);
			}

			a.getNettyTransport().pushSegment("lin-b", new OpLogSegment(
					"chaos.Lin", 0, committed.getFirst().opSeq(), committed.getLast().opSeq(), committed,
					OpLogCodec.segmentChecksum(committed)
			));

			final long applyDeadline = System.currentTimeMillis() + 10_000;
			while (System.currentTimeMillis() < applyDeadline && procB.get(new byte[]{4}) == null) {
				Thread.sleep(50);
			}
			for (int i = 0; i < 5; i++) {
				assertArrayEquals(new byte[]{(byte) (10 + i)}, procB.get(new byte[]{(byte) i}));
			}
			assertEquals(committed.getLast().opSeq(), a.getOrchidNode().getLastCommittedSeq());
		} finally {
			a.stop();
			b.stop();
			System.gc();
			Thread.sleep(50);
		}
	}

	private static int freePort() throws Exception {
		try (ServerSocket s = new ServerSocket(0)) {
			return s.getLocalPort();
		}
	}
}
