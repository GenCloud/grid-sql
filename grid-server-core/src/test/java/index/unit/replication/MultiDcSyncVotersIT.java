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
import org.genfork.grid.replication.OrchidNotSyncedException;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.crossdc.CrossDcMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Multi-DC SYNC_VOTERS_ACROSS_DC over Netty: commit waits for remote digest; partition fail-closed.
 */
public class MultiDcSyncVotersIT {

	@TempDir
	Path tempDir;

	@Test
	void syncVotersCommitThenPartitionRefuses() throws Exception {
		final int portA = freePort();
		final int portB = freePort();

		final GridConfigurationProperties propsA = ReplTestSupport.props(
				"dc-a-1", "multi-dc-voters", "dc-a", portA, tempDir.resolve("a"),
				List.of(ReplTestSupport.peer("dc-b-1", "dc-b", portB))
		);
		propsA.getReplication().getCrossDc().setMode(CrossDcMode.SYNC_VOTERS_ACROSS_DC.name());
		propsA.getReplication().getCrossDc().setVoters(List.of("dc-b-1"));
		propsA.getReplication().getCrossDc().setPhaseCoupling(false);
		propsA.getReplication().getCrossDc().setRemoteAckTimeoutMs(1_500L);

		final GridConfigurationProperties propsB = ReplTestSupport.props(
				"dc-b-1", "multi-dc-voters", "dc-b", portB, tempDir.resolve("b"),
				List.of(ReplTestSupport.peer("dc-a-1", "dc-a", portA))
		);
		propsB.getReplication().getCrossDc().setMode(CrossDcMode.ASYNC_SHIP.name());
		propsB.getReplication().getCrossDc().setPhaseCoupling(false);

		final ReplicationCoordinator a = new ReplicationCoordinator(propsA);
		final ReplicationCoordinator b = new ReplicationCoordinator(propsB);
		a.start();
		b.start();

		final long deadline = System.currentTimeMillis() + 8_000;
		while (System.currentTimeMillis() < deadline
				&& (a.getOrchidNode().livePeerCount() < 1 || b.getOrchidNode().livePeerCount() < 1)) {
			Thread.sleep(50);
		}
		assertTrue(a.getOrchidNode().livePeerCount() >= 1, "HELLO from remote voter");
		assertTrue(a.getOrchidNode().multiDcConfig().hasRemoteVoters());
		assertTrue(a.getOrchidNode().isSynced(), "local N=1 synced without phase-coupling");

		// Brief settle for Netty channels before first propose.
		Thread.sleep(150);

		final ReplicationOp op = OpLogCodec.withChecksum(new ReplicationOp(
				"demo.Voters", 0, 1L, ReplicationOpType.UPSERT, new byte[]{1}, new byte[]{9}, 1L, 0L
		));
		final long seq = a.getOrchidNode().appendAndWaitCommit(op).get(8, TimeUnit.SECONDS);
		assertTrue(seq >= 1);
		a.getOrchidNode().confirmPersisted(seq);

		final long catchUpDeadline = System.currentTimeMillis() + 5_000;
		while (System.currentTimeMillis() < catchUpDeadline && b.getOrchidNode().getLastCommittedSeq() < seq) {
			Thread.sleep(50);
		}
		assertTrue(b.getOrchidNode().getLastCommittedSeq() >= seq, "remote voter advanced on commit");

		// Partition: stop remote voter → next write fail-closed on digest timeout.
		b.stop();
		a.getOrchidNode().forgetPeer("dc-b-1");

		final ReplicationOp op2 = OpLogCodec.withChecksum(new ReplicationOp(
				"demo.Voters", 0, 2L, ReplicationOpType.UPSERT, new byte[]{2}, new byte[]{8}, 1L, 0L
		));
		final CompletionException failed = assertThrows(CompletionException.class,
				() -> a.getOrchidNode().appendAndWaitCommit(op2).join());
		Throwable cause = failed.getCause() == null ? failed : failed.getCause();
		assertTrue(cause instanceof OrchidNotSyncedException, "got " + cause);
		assertTrue(cause.getMessage().contains("remote voter digest timeout"), cause.getMessage());

		a.stop();
	}

	private static int freePort() throws Exception {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}
}
