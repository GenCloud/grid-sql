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
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.replication.codec.OpLogCodec;
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
 * Two-node ORCHID commit over localhost Netty.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public class OrchidFailoverIT {

	@TempDir
	Path tempDir;

	@Test
	void singleNodeCommitsImmediately() throws Exception {
		final int port = freePort();
		final GridConfigurationProperties props = ReplTestSupport.props(
				"solo", "orchid-it", "dc-a", port, tempDir.resolve("solo"), List.of()
		);
		final ReplicationCoordinator coord = new ReplicationCoordinator(props);
		coord.start();
		assertTrue(coord.getOrchidNode().isSynced());
		final ReplicationOp op = OpLogCodec.withChecksum(new ReplicationOp(
				"demo.Solo", 0, 1L, ReplicationOpType.UPSERT, new byte[]{1}, new byte[]{2}, 1L, 0L
		));
		final long seq = coord.getOrchidNode().appendAndWaitCommit(op).get(3, TimeUnit.SECONDS);
		assertTrue(seq >= 1);
		coord.stop();
	}

	@Test
	void twoNodesReachOrderThreshold() throws Exception {
		final int portA = freePort();
		final int portB = freePort();
		final GridConfigurationProperties propsA = ReplTestSupport.props(
				"n-a", "orchid-r", "dc-a", portA, tempDir.resolve("ra"),
				List.of(ReplTestSupport.peer("n-b", "dc-a", portB))
		);
		final GridConfigurationProperties propsB = ReplTestSupport.props(
				"n-b", "orchid-r", "dc-a", portB, tempDir.resolve("rb"),
				List.of(ReplTestSupport.peer("n-a", "dc-a", portA))
		);
		// Realistic admission gate (IT defaults use 0.0 to skip Kuramoto lock).
		propsA.getReplication().getOrchid().setOrderThreshold(0.85);
		propsB.getReplication().getOrchid().setOrderThreshold(0.85);
		propsA.getReplication().getOrchid().setNaturalFreqHz(1.0);
		propsB.getReplication().getOrchid().setNaturalFreqHz(1.0);
		propsA.getReplication().getOrchid().setCoupling(15.0);
		propsB.getReplication().getOrchid().setCoupling(15.0);

		final ReplicationCoordinator a = new ReplicationCoordinator(propsA);
		final ReplicationCoordinator b = new ReplicationCoordinator(propsB);
		a.start();
		b.start();

		final long deadline = System.currentTimeMillis() + 10_000;
		boolean synced = false;
		while (System.currentTimeMillis() < deadline) {
			if (a.getOrchidNode().isSynced() && b.getOrchidNode().isSynced()
					&& a.getOrchidNode().livePeerCount() >= 1
					&& b.getOrchidNode().livePeerCount() >= 1
					&& a.getOrchidNode().isPhaseRankedProposer()) {
				synced = true;
				break;
			}
			Thread.sleep(50);
		}
		assertTrue(synced,
				() -> "expected R synced + phase-ranked a; a.R=" + a.getOrchidNode().orderParameterR()
						+ " b.R=" + b.getOrchidNode().orderParameterR()
						+ " proposer=" + a.getOrchidNode().phaseRankedProposerId());

		final ReplicationOp op = OpLogCodec.withChecksum(new ReplicationOp(
				"demo.R", 0, 1L, ReplicationOpType.UPSERT, new byte[]{5}, new byte[]{6}, 1L, 0L
		));
		assertTrue(a.getOrchidNode().appendAndWaitCommit(op).get(5, TimeUnit.SECONDS) >= 1);

		a.stop();
		b.stop();
	}

	private static int freePort() throws Exception {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}
}
