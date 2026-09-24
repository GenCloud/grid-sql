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
 * Partition: minority must refuse writes (configured quorum). Heal via catch-up.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public class PartitionHealSmokeIT {

	@TempDir
	Path tempDir;

	@Test
	void partitionMinorityRefusesThenHealConverges() throws Exception {
		final int portA = freePort();
		final int portB = freePort();
		final var propsA = ReplTestSupport.props(
				"chaos-a", "chaos", "dc-a", portA, tempDir.resolve("a"),
				List.of(ReplTestSupport.peer("chaos-b", "dc-a", portB))
		);
		propsA.getReplication().getOrchid().setOrderThreshold(0.0);
		propsA.getReplication().getOrchid().setNaturalFreqHz(1.0);
		propsA.getReplication().getOrchid().setCoupling(15.0);
		final var propsB = ReplTestSupport.props(
				"chaos-b", "chaos", "dc-a", portB, tempDir.resolve("b"),
				List.of(ReplTestSupport.peer("chaos-a", "dc-a", portA))
		);
		propsB.getReplication().getOrchid().setOrderThreshold(0.0);
		propsB.getReplication().getOrchid().setNaturalFreqHz(1.0);
		propsB.getReplication().getOrchid().setCoupling(15.0);

		final ReplicationCoordinator a = new ReplicationCoordinator(propsA);
		final ReplicationCoordinator b = new ReplicationCoordinator(propsB);
		final GridEntriesProcessor procA = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
		final GridEntriesProcessor procB = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
		a.registerDomain("chaos.Domain", ReplTestSupport.singleShard(procA), true);
		b.registerDomain("chaos.Domain", ReplTestSupport.singleShard(procB), true);
		a.start();
		b.start();

		final long deadline = System.currentTimeMillis() + 8_000;
		while (System.currentTimeMillis() < deadline
				&& (!a.getOrchidNode().isSynced() || !b.getOrchidNode().isSynced()
				|| !a.getOrchidNode().isPhaseRankedProposer())) {
			Thread.sleep(50);
		}
		assertTrue(a.getOrchidNode().isPhaseRankedProposer(), "chaos-a should rank before chaos-b");

		final byte[] key = new byte[]{1, 2};
		final byte[] value = new byte[]{9, 9, 9};
		final ReplicationOp op = OpLogCodec.withChecksum(new ReplicationOp(
				"chaos.Domain", 0, 1L, ReplicationOpType.UPSERT, key, value, 1L, 0L
		));
		final long seq = a.getOrchidNode().appendAndWaitCommit(op).get(5, TimeUnit.SECONDS);
		final ReplicationOp committed = OpLogCodec.withChecksum(new ReplicationOp(
				op.domainType(), op.shard(), seq, op.type(), op.key(), op.value(), op.schemaEpoch(), 0L
		));
		a.getOpLog().append(committed);
		a.getOrchidNode().confirmPersisted(seq);
		procA.installCommitted(key, value, false);

		// Partition: bilateral isolate — configured N=2 so solo write must fail
		a.isolatePeer("chaos-b");
		b.isolatePeer("chaos-a");
		assertThrows(OrchidNotSyncedException.class, a::ensureOrchidSynced);
		final ReplicationOp op2 = OpLogCodec.withChecksum(new ReplicationOp(
				"chaos.Domain", 0, seq + 1, ReplicationOpType.UPSERT, new byte[]{3, 4}, new byte[]{7, 7}, 1L, 0L
		));
		assertThrows(CompletionException.class, () -> a.getOrchidNode().appendAndWaitCommit(op2).join());

		// Heal + catch-up first op
		a.reconnectPeer("chaos-b");
		b.reconnectPeer("chaos-a");
		final List<ReplicationOp> catchUp = a.getOpLog().readFrom("chaos.Domain", 0, 1, 100);
		assertTrue(catchUp.size() >= 1);
		a.getNettyTransport().pushSegment("chaos-b", new OpLogSegment(
				"chaos.Domain", 0, catchUp.getFirst().opSeq(), catchUp.getLast().opSeq(), catchUp,
				OpLogCodec.segmentChecksum(catchUp)
		));

		final long applyDeadline = System.currentTimeMillis() + 8_000;
		while (System.currentTimeMillis() < applyDeadline && procB.get(key) == null) {
			Thread.sleep(50);
		}
		assertArrayEquals(value, procB.get(key));

		a.stop();
		b.stop();
	}

	private static int freePort() throws Exception {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}
}
