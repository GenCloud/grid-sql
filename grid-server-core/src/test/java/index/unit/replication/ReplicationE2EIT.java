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
import org.genfork.grid.replication.OrchidNotSyncedException;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.OpLogSegment;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.metrics.ReplicationMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E: OrchidNotSynced gate + CRUD-style ship → peer apply.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public class ReplicationE2EIT {

	@TempDir
	Path tempDir;

	@Test
	void orchidNotSyncedBlocksEnsure() throws Exception {
		final int portA = freePort();
		final int portB = freePort();
		// True solo N=1 (empty peer list) → solo writes allowed.
		final GridConfigurationProperties soloProps = ReplTestSupport.props(
				"solo-ok", "e2e-solo", "dc-a", portA, tempDir.resolve("solo"),
				List.of()
		);
		soloProps.getReplication().getOrchid().setOrderThreshold(0.85);
		final ReplicationCoordinator solo = new ReplicationCoordinator(soloProps);
		solo.start();
		Thread.sleep(50);
		solo.ensureOrchidSynced();
		solo.stop();

		// Live peer + impossible threshold: gate must block.
		final GridConfigurationProperties aProps = ReplTestSupport.props(
				"strict-a", "e2e-strict", "dc-a", portA, tempDir.resolve("strict-a"),
				List.of(ReplTestSupport.peer("strict-b", "dc-a", portB))
		);
		aProps.getReplication().getOrchid().setOrderThreshold(1.01);
		final GridConfigurationProperties bProps = ReplTestSupport.props(
				"strict-b", "e2e-strict", "dc-a", portB, tempDir.resolve("strict-b"),
				List.of(ReplTestSupport.peer("strict-a", "dc-a", portA))
		);
		bProps.getReplication().getOrchid().setOrderThreshold(1.01);

		final ReplicationCoordinator a = new ReplicationCoordinator(aProps);
		final ReplicationCoordinator b = new ReplicationCoordinator(bProps);
		a.start();
		b.start();
		final long deadline = System.currentTimeMillis() + 5000;
		while (System.currentTimeMillis() < deadline && a.getOrchidNode().livePeerCount() < 1) {
			Thread.sleep(50);
		}
		assertTrue(a.getOrchidNode().livePeerCount() >= 1);
		assertThrows(OrchidNotSyncedException.class, a::ensureOrchidSynced);
		a.stop();
		b.stop();
	}

	@Test
	void shipAppliesOnPeer() throws Exception {
		final int portA = freePort();
		final int portB = freePort();
		final ReplicationCoordinator a = new ReplicationCoordinator(ReplTestSupport.props(
				"n-a", "e2e-ship", "dc-a", portA, tempDir.resolve("a"),
				List.of(ReplTestSupport.peer("n-b", "dc-a", portB))
		));
		final ReplicationCoordinator b = new ReplicationCoordinator(ReplTestSupport.props(
				"n-b", "e2e-ship", "dc-a", portB, tempDir.resolve("b"),
				List.of(ReplTestSupport.peer("n-a", "dc-a", portA))
		));
		final GridEntriesProcessor procA = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
		final GridEntriesProcessor procB = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
		a.registerDomain("demo.E2E", ReplTestSupport.singleShard(procA), true);
		b.registerDomain("demo.E2E", ReplTestSupport.singleShard(procB), true);
		a.start();
		b.start();

		final long deadline = System.currentTimeMillis() + 5000;
		while (System.currentTimeMillis() < deadline
				&& (a.getOrchidNode().livePeerCount() < 1 || b.getOrchidNode().livePeerCount() < 1)) {
			Thread.sleep(50);
		}

		final byte[] key = new byte[]{1, 2};
		final byte[] value = new byte[]{9, 9, 9};
		final ReplicationOp op = OpLogCodec.withChecksum(new ReplicationOp(
				"demo.E2E", 0, 1L, ReplicationOpType.UPSERT, key, value, 1L, 0L
		));
		final long seq = a.getOrchidNode().appendAndWaitCommit(op).get(5, TimeUnit.SECONDS);
		final ReplicationOp committed = OpLogCodec.withChecksum(new ReplicationOp(
				op.domainType(), op.shard(), seq, op.type(), op.key(), op.value(), op.schemaEpoch(), 0L
		));
		a.getOpLog().append(committed);
		a.getNettyTransport().pushSegment("n-b", new OpLogSegment(
				"demo.E2E", 0, seq, seq, List.of(committed), OpLogCodec.segmentChecksum(List.of(committed))
		));

		final long applyDeadline = System.currentTimeMillis() + 5000;
		while (System.currentTimeMillis() < applyDeadline && procB.get(key) == null) {
			Thread.sleep(50);
		}
		assertArrayEquals(value, procB.get(key));
		assertTrue(ReplicationMetrics.oplogPushSent() > 0);

		a.stop();
		b.stop();
	}

	private static int freePort() throws Exception {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}
}
