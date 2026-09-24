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
import org.genfork.grid.replication.OrchidNotSyncedException;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Membership / quorum: {@code forgetPeer} does not shrink configured voters; minority cannot write;
 * solo only when configured peers are empty.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public class OrchidMembershipQuorumIT {

	@TempDir(cleanup = CleanupMode.NEVER)
	Path tempDir;

	@Test
	void forgetPeerDoesNotShrinkConfiguredQuorumAndMinorityCannotWrite() throws Exception {
		final int portA = freePort();
		final int portB = freePort();
		final GridConfigurationProperties propsA = ReplTestSupport.safetyProps(
				"mq-a", "mq", "dc-a", portA, tempDir.resolve("a"),
				List.of(ReplTestSupport.peer("mq-b", "dc-a", portB))
		);
		final GridConfigurationProperties propsB = ReplTestSupport.safetyProps(
				"mq-b", "mq", "dc-a", portB, tempDir.resolve("b"),
				List.of(ReplTestSupport.peer("mq-a", "dc-a", portA))
		);

		final ReplicationCoordinator a = new ReplicationCoordinator(propsA);
		final ReplicationCoordinator b = new ReplicationCoordinator(propsB);
		try {
			final GridEntriesProcessor procA = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
			final GridEntriesProcessor procB = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
			a.registerDomain("chaos.Mq", ReplTestSupport.singleShard(procA), true);
			b.registerDomain("chaos.Mq", ReplTestSupport.singleShard(procB), true);
			a.start();
			b.start();

			waitSynced(a, b, 12_000);
			assertEquals(2, a.getOrchidNode().configuredVoterCount());
			assertEquals(2, b.getOrchidNode().configuredVoterCount());

			a.isolatePeer("mq-b");
			b.isolatePeer("mq-a");

			assertEquals(2, a.getOrchidNode().configuredVoterCount(), "forget/isolate must not shrink configured N");
			assertEquals(2, b.getOrchidNode().configuredVoterCount());
			assertFalse(a.getOrchidNode().isSynced(), "N>=2 with empty live view is not solo");
			assertFalse(b.getOrchidNode().isSynced());

			final ReplicationOp op = OpLogCodec.withChecksum(new ReplicationOp(
					"chaos.Mq", 0, 1L, ReplicationOpType.UPSERT, new byte[]{1}, new byte[]{2}, 1L, 0L
			));
			assertThrows(CompletionException.class, () -> a.getOrchidNode().appendAndWaitCommit(op).join());
			assertThrows(CompletionException.class, () -> b.getOrchidNode().appendAndWaitCommit(op).join());
			assertThrows(OrchidNotSyncedException.class, a::ensureOrchidSynced);
		} finally {
			stopQuietly(a);
			stopQuietly(b);
		}
	}

	@Test
	void soloWriteOnlyWithEmptyConfiguredPeers() throws Exception {
		final int port = freePort();
		final GridConfigurationProperties props = ReplTestSupport.safetyProps(
				"mq-solo", "mq-solo", "dc-a", port, tempDir.resolve("solo"), List.of()
		);
		final ReplicationCoordinator solo = new ReplicationCoordinator(props);
		try {
			final GridEntriesProcessor proc = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
			solo.registerDomain("chaos.MqSolo", ReplTestSupport.singleShard(proc), true);
			solo.start();

			assertEquals(1, solo.getOrchidNode().configuredVoterCount());
			assertTrue(solo.getOrchidNode().isSynced());
			assertTrue(solo.getOrchidNode().isPhaseRankedProposer());

			final ReplicationOp op = OpLogCodec.withChecksum(new ReplicationOp(
					"chaos.MqSolo", 0, 1L, ReplicationOpType.UPSERT, new byte[]{3}, new byte[]{4}, 1L, 0L
			));
			final long seq = solo.getOrchidNode().appendAndWaitCommit(op).get(3, TimeUnit.SECONDS);
			assertEquals(1L, seq);
			assertEquals(0L, solo.getOrchidNode().pendingProposeAgeMs());
		} finally {
			stopQuietly(solo);
		}
	}

	private static void waitSynced(ReplicationCoordinator a, ReplicationCoordinator b, long timeoutMs) {
		final long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			if (a.getOrchidNode().isSynced() && b.getOrchidNode().isSynced()
					&& a.getOrchidNode().liveLocalPeerCount() >= 1
					&& b.getOrchidNode().liveLocalPeerCount() >= 1) {
				return;
			}
			LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
		}
		assertTrue(a.getOrchidNode().isSynced() && b.getOrchidNode().isSynced(),
				() -> "expected sync; a.R=" + a.getOrchidNode().orderParameterR()
						+ " b.R=" + b.getOrchidNode().orderParameterR());
	}

	private static void stopQuietly(ReplicationCoordinator c) {
		try {
			c.stop();
		} catch (Exception ignored) {
		}
	}

	private static int freePort() throws Exception {
		try (ServerSocket s = new ServerSocket(0)) {
			return s.getLocalPort();
		}
	}
}