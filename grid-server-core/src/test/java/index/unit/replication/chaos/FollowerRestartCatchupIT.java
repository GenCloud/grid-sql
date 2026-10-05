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
import org.genfork.grid.replication.codec.OpLogSegment;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Follower stop while majority keeps writing; revive + ship catch-up restores map equality.
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
public class FollowerRestartCatchupIT {

	private static final String DOMAIN = "chaos.Foll";
	private static final String CLUSTER = "foll";

	@TempDir(cleanup = CleanupMode.NEVER)
	Path tempDir;

	@Test
	void followerStopMajorityWritesThenReviveCatchUp() throws Exception {
		final int portA = freePort();
		final int portB = freePort();
		final int portC = freePort();
		final Path dirC = tempDir.resolve("c");

		final GridConfigurationProperties propsA = ReplTestSupport.safetyProps(
				"foll-a", CLUSTER, "dc-a", portA, tempDir.resolve("a"),
				List.of(ReplTestSupport.peer("foll-b", "dc-a", portB),
						ReplTestSupport.peer("foll-c", "dc-a", portC))
		);
		final GridConfigurationProperties propsB = ReplTestSupport.safetyProps(
				"foll-b", CLUSTER, "dc-a", portB, tempDir.resolve("b"),
				List.of(ReplTestSupport.peer("foll-a", "dc-a", portA),
						ReplTestSupport.peer("foll-c", "dc-a", portC))
		);
		final GridConfigurationProperties propsC = ReplTestSupport.safetyProps(
				"foll-c", CLUSTER, "dc-a", portC, dirC,
				List.of(ReplTestSupport.peer("foll-a", "dc-a", portA),
						ReplTestSupport.peer("foll-b", "dc-a", portB))
		);

		final GridEntriesProcessor procA = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
		final GridEntriesProcessor procB = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
		final GridEntriesProcessor procC = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
		final ReplicationCoordinator a = new ReplicationCoordinator(propsA);
		final ReplicationCoordinator b = new ReplicationCoordinator(propsB);
		ReplicationCoordinator c = new ReplicationCoordinator(propsC);
		try {
			a.registerDomain(DOMAIN, ReplTestSupport.singleShard(procA), true);
			b.registerDomain(DOMAIN, ReplTestSupport.singleShard(procB), true);
			c.registerDomain(DOMAIN, ReplTestSupport.singleShard(procC), true);
			a.start();
			b.start();
			c.start();
			waitThreeSynced(a, b, c, 15_000);
			assertTrue(a.getOrchidNode().isPhaseRankedProposer());

			// Stop follower (lexicographically last) — not the proposer.
			a.isolatePeer("foll-c");
			b.isolatePeer("foll-c");
			stopQuietly(c);

			final long majDeadline = System.currentTimeMillis() + 8_000;
			while (System.currentTimeMillis() < majDeadline
					&& (!a.getOrchidNode().isSynced() || !b.getOrchidNode().isSynced()
					|| !a.getOrchidNode().isPhaseRankedProposer())) {
				LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
			}
			assertTrue(a.getOrchidNode().isSynced(), "majority must stay synced without follower");

			final byte[] key = new byte[]{8, 8};
			final byte[] value = new byte[]{9, 9};
			final ReplicationOp op = OpLogCodec.withChecksum(new ReplicationOp(
					DOMAIN, 0, 1L, ReplicationOpType.UPSERT, key, value, 1L, 0L
			));
			final long seq = a.getOrchidNode().appendAndWaitCommit(op).get(8, TimeUnit.SECONDS);
			final ReplicationOp committed = OpLogCodec.withChecksum(new ReplicationOp(
					DOMAIN, 0, seq, ReplicationOpType.UPSERT, key, value, 1L, 0L
			));
			a.getOpLog().append(committed);
			a.getOrchidNode().confirmPersisted(seq);
			procA.installCommitted(key, value, false);
			a.getNodeState().advanceApplied(DOMAIN, 0, seq);

			final List<ReplicationOp> shipToB = a.getOpLog().readFrom(DOMAIN, 0, 1, 100);
			a.getNettyTransport().pushSegment("foll-b", new OpLogSegment(
					DOMAIN, 0, shipToB.getFirst().opSeq(), shipToB.getLast().opSeq(), shipToB,
					OpLogCodec.segmentChecksum(shipToB)
			));
			waitKey(procB, key, value, 8_000);

			// Revive follower on same dataDir + same bind port (majority peer list unchanged).
			LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(300));
			final GridConfigurationProperties propsC2 = ReplTestSupport.safetyProps(
					"foll-c", CLUSTER, "dc-a", portC, dirC,
					List.of(ReplTestSupport.peer("foll-a", "dc-a", portA),
							ReplTestSupport.peer("foll-b", "dc-a", portB))
			);
			final GridEntriesProcessor procC2 = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
			c = new ReplicationCoordinator(propsC2);
			c.registerDomain(DOMAIN, ReplTestSupport.singleShard(procC2), true);
			c.start();
			a.reconnectPeer("foll-c");
			b.reconnectPeer("foll-c");

			final List<ReplicationOp> catchUp = a.getOpLog().readFrom(DOMAIN, 0, 1, 100);
			assertTrue(catchUp.size() >= 1);
			a.getNettyTransport().pushSegment("foll-c", new OpLogSegment(
					DOMAIN, 0, catchUp.getFirst().opSeq(), catchUp.getLast().opSeq(), catchUp,
					OpLogCodec.segmentChecksum(catchUp)
			));
			waitKey(procC2, key, value, 12_000);
			assertArrayEquals(value, procC2.get(key));
		} finally {
			stopQuietly(a);
			stopQuietly(b);
			stopQuietly(c);
		}
	}

	private static void waitThreeSynced(ReplicationCoordinator a,
	                                    ReplicationCoordinator b,
	                                    ReplicationCoordinator c,
	                                    long timeoutMs) {
		final long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			if (a.getOrchidNode().isSynced() && b.getOrchidNode().isSynced() && c.getOrchidNode().isSynced()
					&& a.getOrchidNode().isPhaseRankedProposer()
					&& !a.getOrchidNode().awaitsPeerTipAdvertisement()
					&& !b.getOrchidNode().awaitsPeerTipAdvertisement()
					&& !c.getOrchidNode().awaitsPeerTipAdvertisement()) {
				return;
			}
			LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
		}
		assertTrue(a.getOrchidNode().isPhaseRankedProposer());
	}

	private static void waitKey(GridEntriesProcessor proc, byte[] key, byte[] value, long timeoutMs) {
		final long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			final byte[] got = proc.get(key);
			if (got != null && java.util.Arrays.equals(got, value)) {
				return;
			}
			LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
		}
		assertArrayEquals(value, proc.get(key));
	}

	private static void stopQuietly(ReplicationCoordinator coord) {
		if (coord == null) {
			return;
		}
		try {
			coord.stop();
		} catch (Exception ignored) {
		}
	}

	private static int freePort() throws Exception {
		try (ServerSocket s = new ServerSocket(0)) {
			return s.getLocalPort();
		}
	}
}
