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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * N=3 cold start: majority commit → stop all → reopen same dataDirs → phase re-lock + catch-up.
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
public class ClusterColdStartN3IT {

	private static final String DOMAIN = "chaos.Cold";
	private static final String CLUSTER = "cold";

	@TempDir(cleanup = CleanupMode.NEVER)
	Path tempDir;

	@Test
	void coldStartAllThreeRelocksAndKeepsCommittedRow() throws Exception {
		final int portA = freePort();
		final int portB = freePort();
		final int portC = freePort();
		final Path dirA = tempDir.resolve("a");
		final Path dirB = tempDir.resolve("b");
		final Path dirC = tempDir.resolve("c");

		final GridConfigurationProperties propsA = props("cold-a", portA, dirA, portB, portC);
		final GridConfigurationProperties propsB = props("cold-b", portB, dirB, portA, portC);
		final GridConfigurationProperties propsC = props("cold-c", portC, dirC, portA, portB);

		final GridEntriesProcessor procA = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
		final GridEntriesProcessor procB = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
		final GridEntriesProcessor procC = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
		ReplicationCoordinator a = new ReplicationCoordinator(propsA);
		ReplicationCoordinator b = new ReplicationCoordinator(propsB);
		ReplicationCoordinator c = new ReplicationCoordinator(propsC);
		final byte[] key = new byte[]{1, 1};
		final byte[] value = new byte[]{2, 2};
		long seq;
		try {
			a.registerDomain(DOMAIN, ReplTestSupport.singleShard(procA), true);
			b.registerDomain(DOMAIN, ReplTestSupport.singleShard(procB), true);
			c.registerDomain(DOMAIN, ReplTestSupport.singleShard(procC), true);
			a.start();
			b.start();
			c.start();
			waitThreeSynced(a, b, c, "cold-a", 15_000);

			final ReplicationOp op = OpLogCodec.withChecksum(new ReplicationOp(
					DOMAIN, 0, 1L, ReplicationOpType.UPSERT, key, value, 1L, 0L
			));
			seq = a.getOrchidNode().appendAndWaitCommit(op).get(8, TimeUnit.SECONDS);
			final ReplicationOp committed = OpLogCodec.withChecksum(new ReplicationOp(
					DOMAIN, 0, seq, ReplicationOpType.UPSERT, key, value, 1L, 0L
			));
			a.getOpLog().append(committed);
			a.getOrchidNode().confirmPersisted(seq);
			procA.installCommitted(key, value, false);
			a.getNodeState().advanceApplied(DOMAIN, 0, seq);

			final List<ReplicationOp> ship = a.getOpLog().readFrom(DOMAIN, 0, 1, 100);
			assertTrue(ship.size() >= 1);
			final OpLogSegment segment = new OpLogSegment(
					DOMAIN, 0, ship.getFirst().opSeq(), ship.getLast().opSeq(), ship,
					OpLogCodec.segmentChecksum(ship)
			);
			a.getNettyTransport().pushSegment("cold-b", segment);
			a.getNettyTransport().pushSegment("cold-c", segment);
			waitApplied(procB, key, value, 10_000);
			waitApplied(procC, key, value, 10_000);
		} finally {
			stopQuietly(a);
			stopQuietly(b);
			stopQuietly(c);
			LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(200));
		}

		final int portA2 = freePort();
		final int portB2 = freePort();
		final int portC2 = freePort();
		final GridEntriesProcessor procA2 = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
		final GridEntriesProcessor procB2 = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
		final GridEntriesProcessor procC2 = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
		a = new ReplicationCoordinator(props("cold-a", portA2, dirA, portB2, portC2));
		b = new ReplicationCoordinator(props("cold-b", portB2, dirB, portA2, portC2));
		c = new ReplicationCoordinator(props("cold-c", portC2, dirC, portA2, portB2));
		try {
			a.registerDomain(DOMAIN, ReplTestSupport.singleShard(procA2), true);
			b.registerDomain(DOMAIN, ReplTestSupport.singleShard(procB2), true);
			c.registerDomain(DOMAIN, ReplTestSupport.singleShard(procC2), true);
			a.start();
			b.start();
			c.start();
			waitThreeSynced(a, b, c, "cold-a", 20_000);

			assertArrayEquals(value, procA2.get(key), "cold-a must hydrate committed row");
			assertTrue(a.getOrchidNode().getLastCommittedSeq() >= seq
							|| a.getOpLog().lastSeq(DOMAIN, 0) >= seq,
					"tip or OpLog must retain seq after cold start");
			assertTrue(a.getOrchidNode().isPhaseRankedProposer());
			assertEquals("cold-a", b.getOrchidNode().getPhaseRankedProposerId());

			final byte[] key2 = new byte[]{3, 3};
			final byte[] value2 = new byte[]{4, 4};
			final ReplicationOp next = OpLogCodec.withChecksum(new ReplicationOp(
					DOMAIN, 0, seq + 1L, ReplicationOpType.UPSERT, key2, value2, 1L, 0L
			));
			final long seq2 = a.getOrchidNode().appendAndWaitCommit(next).get(8, TimeUnit.SECONDS);
			assertTrue(seq2 > seq, "post-cold-start write must mint a new seq");
		} finally {
			stopQuietly(a);
			stopQuietly(b);
			stopQuietly(c);
		}
	}

	private static GridConfigurationProperties props(String nodeId, int bind, Path dataDir,
	                                                 int peerPort1, int peerPort2) {
		if (nodeId.equals("cold-a")) {
			return ReplTestSupport.safetyProps(nodeId, CLUSTER, "dc-a", bind, dataDir,
					List.of(ReplTestSupport.peer("cold-b", "dc-a", peerPort1),
							ReplTestSupport.peer("cold-c", "dc-a", peerPort2)));
		}
		if (nodeId.equals("cold-b")) {
			return ReplTestSupport.safetyProps(nodeId, CLUSTER, "dc-a", bind, dataDir,
					List.of(ReplTestSupport.peer("cold-a", "dc-a", peerPort1),
							ReplTestSupport.peer("cold-c", "dc-a", peerPort2)));
		}
		return ReplTestSupport.safetyProps(nodeId, CLUSTER, "dc-a", bind, dataDir,
				List.of(ReplTestSupport.peer("cold-a", "dc-a", peerPort1),
						ReplTestSupport.peer("cold-b", "dc-a", peerPort2)));
	}

	private static void waitThreeSynced(ReplicationCoordinator a,
	                                    ReplicationCoordinator b,
	                                    ReplicationCoordinator c,
	                                    String expectedProposer,
	                                    long timeoutMs) {
		final long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			if (a.getOrchidNode().isSynced() && b.getOrchidNode().isSynced() && c.getOrchidNode().isSynced()
					&& expectedProposer.equals(a.getOrchidNode().getPhaseRankedProposerId())
					&& a.getOrchidNode().isPhaseRankedProposer()
					&& !a.getOrchidNode().awaitsPeerTipAdvertisement()
					&& !b.getOrchidNode().awaitsPeerTipAdvertisement()
					&& !c.getOrchidNode().awaitsPeerTipAdvertisement()) {
				return;
			}
			LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
		}
		assertTrue(a.getOrchidNode().isPhaseRankedProposer(),
				() -> "expected proposer " + expectedProposer
						+ " got " + a.getOrchidNode().getPhaseRankedProposerId()
						+ " a.R=" + a.getOrchidNode().orderParameterR()
						+ " a.awaitTip=" + a.getOrchidNode().awaitsPeerTipAdvertisement()
						+ " b.awaitTip=" + b.getOrchidNode().awaitsPeerTipAdvertisement()
						+ " c.awaitTip=" + c.getOrchidNode().awaitsPeerTipAdvertisement());
	}

	private static void waitApplied(GridEntriesProcessor proc, byte[] key, byte[] value, long timeoutMs) {
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
