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

import org.genfork.grid.context.config.GridConfigurationProperties;
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
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Multi-node coordinator IT over localhost Netty (ORCHID + OPLOG ship).
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public class ReplicationCoordinatorIT {

	@TempDir
	Path tempDir;

	@Test
	void orchidCommitAndNettyShip() throws Exception {
		final int portA = freePort();
		final int portB = freePort();
		final int portC = freePort();

		final GridConfigurationProperties primaryProps = ReplTestSupport.props(
				"n1", "it-cluster", "dc-a", portA, tempDir.resolve("n1"), List.of(
						ReplTestSupport.peer("n2", "dc-a", portB),
						ReplTestSupport.peer("n3", "dc-b", portC)
				));
		final GridConfigurationProperties replicaProps = ReplTestSupport.props(
				"n2", "it-cluster", "dc-a", portB, tempDir.resolve("n2"), List.of(
						ReplTestSupport.peer("n1", "dc-a", portA),
						ReplTestSupport.peer("n3", "dc-b", portC)
				));
		final GridConfigurationProperties remoteProps = ReplTestSupport.props(
				"n3", "it-cluster", "dc-b", portC, tempDir.resolve("n3"), List.of(
						ReplTestSupport.peer("n1", "dc-a", portA),
						ReplTestSupport.peer("n2", "dc-a", portB)
				));

		final ReplicationCoordinator primary = new ReplicationCoordinator(primaryProps);
		final ReplicationCoordinator replica = new ReplicationCoordinator(replicaProps);
		final ReplicationCoordinator remote = new ReplicationCoordinator(remoteProps);

		final GridEntriesProcessor primaryProc = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
		final GridEntriesProcessor replicaProc = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
		final GridEntriesProcessor remoteProc = new GridEntriesProcessor(0, new GridScalableMap(), null, null);

		primary.registerDomain("demo.Domain", ReplTestSupport.singleShard(primaryProc), true);
		replica.registerDomain("demo.Domain", ReplTestSupport.singleShard(replicaProc), true);
		remote.registerDomain("demo.Domain", ReplTestSupport.singleShard(remoteProc), true);

		primary.start();
		replica.start();
		remote.start();

		final long deadline = System.currentTimeMillis() + 5000;
		while (System.currentTimeMillis() < deadline
				&& (primary.getOrchidNode().livePeerCount() < 1
				|| replica.getOrchidNode().livePeerCount() < 1
				|| remote.getOrchidNode().livePeerCount() < 1)) {
			Thread.sleep(50);
		}

		final byte[] key = new byte[]{9};
		final byte[] value = new byte[]{7, 7};
		final ReplicationOp op = OpLogCodec.withChecksum(new ReplicationOp(
				"demo.Domain", 0, 1L, ReplicationOpType.UPSERT, key, value, 1L, 0L
		));
		final long seq = primary.getOrchidNode().appendAndWaitCommit(op).get(10, TimeUnit.SECONDS);
		assertTrue(seq >= 1);
		final ReplicationOp committed = OpLogCodec.withChecksum(new ReplicationOp(
				op.domainType(), op.shard(), seq, op.type(), op.key(), op.value(), op.schemaEpoch(), 0L
		));
		primary.getOpLog().append(committed);
		final OpLogSegment segment = new OpLogSegment(
				"demo.Domain", 0, seq, seq, List.of(committed), OpLogCodec.segmentChecksum(List.of(committed))
		);
		primary.getNettyTransport().pushSegment("n2", segment);
		primary.getPublisher().onAppended(committed);
		primary.getCrossDcPublisher().onAppended(committed);

		Thread.sleep(400);

		assertTrue(primary.installSnapshot("demo.Domain", 0).ops().getFirst().type() == ReplicationOpType.SNAPSHOT_MARKER);
		assertTrue(primary.getCrossDcPublisher().getMetrics().shipFailures() >= 0);
		assertTrue(primary.getSwarm().tick() != null);
		assertTrue(primary.installSchemaBarrier("demo.Domain", 0, new byte[]{1, 2}).ops().getFirst().type()
				== ReplicationOpType.BARRIER);

		primary.stop();
		replica.stop();
		remote.stop();
	}

	private static int freePort() throws Exception {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}
}
