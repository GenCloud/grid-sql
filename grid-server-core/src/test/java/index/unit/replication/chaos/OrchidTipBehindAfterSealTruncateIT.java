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
 * Tip skew after seal+truncate with no live OpLog gap: HELLO sealed baseline must
 * advance orchid tip to the observed peer tip (not leave writerEligible fenced forever).
 * <p>
 * Uses 3 nodes so the writer stays phase-synced with a live peer while the lagging
 * follower is isolated (2-node isolate drops ORCHID R).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
public class OrchidTipBehindAfterSealTruncateIT {

	private static final String DOMAIN = "chaos.TipSealTrunc";
	private static final String CLUSTER = "tip-st";
	private static final int SHARD = 0;
	private static final long SYNC_TIMEOUT_MS = 15_000L;
	private static final long CATCH_UP_TIMEOUT_MS = 25_000L;
	private static final long KEY_WAIT_MS = 8_000L;
	private static final long COMMIT_TIMEOUT_SEC = 8L;
	private static final long ISOLATE_SETTLE_MS = 300L;
	private static final long PARK_POLL_MS = 50L;
	private static final long PARK_NANOS = TimeUnit.MILLISECONDS.toNanos(PARK_POLL_MS);
	private static final long HINT_SEQ_EARLY = 1L;
	private static final long HINT_SEQ_MID = 2L;
	private static final long TX_ID = 1L;
	private static final long PREV_OP_SEQ = 0L;
	private static final int SHIP_BATCH_LIMIT = 10_000;
	private static final long SHIP_FROM_SEQ = 1L;

	@TempDir(cleanup = CleanupMode.NEVER)
	Path tempDir;

	@Test
	void sealedBaselineDrainAdvancesTipPastTruncateGap() throws Exception {
		final int portA = freePort();
		final int portB = freePort();
		final int portC = freePort();

		final GridConfigurationProperties propsA = ReplTestSupport.props(
				"tip-a", CLUSTER, "dc-a", portA, tempDir.resolve("a"),
				List.of(ReplTestSupport.peer("tip-b", "dc-a", portB),
						ReplTestSupport.peer("tip-c", "dc-a", portC)));
		final GridConfigurationProperties propsB = ReplTestSupport.props(
				"tip-b", CLUSTER, "dc-a", portB, tempDir.resolve("b"),
				List.of(ReplTestSupport.peer("tip-a", "dc-a", portA),
						ReplTestSupport.peer("tip-c", "dc-a", portC)));
		final GridConfigurationProperties propsC = ReplTestSupport.props(
				"tip-c", CLUSTER, "dc-a", portC, tempDir.resolve("c"),
				List.of(ReplTestSupport.peer("tip-a", "dc-a", portA),
						ReplTestSupport.peer("tip-b", "dc-a", portB)));

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
			waitThreeSynced(a, b, c, SYNC_TIMEOUT_MS);
			assertTrue(a.getOrchidNode().isPhaseRankedProposer());

			final byte[] keyEarly = new byte[]{1, 1};
			final byte[] valEarly = new byte[]{10, 11};
			commitLocal(a, procA, keyEarly, valEarly, HINT_SEQ_EARLY);
			shipAllFrom(a, "tip-b");
			shipAllFrom(a, "tip-c");
			waitKey(procB, keyEarly, valEarly, KEY_WAIT_MS);
			waitKey(procC, keyEarly, valEarly, KEY_WAIT_MS);

			final long tipBeforeIsolate = c.getOrchidNode().getLastCommittedSeq();
			assertTrue(tipBeforeIsolate > 0L, "follower must have tip before isolate");

			a.isolatePeer("tip-c");
			b.isolatePeer("tip-c");
			stopQuietly(c);

			final byte[] keyMid = new byte[]{2, 2};
			final byte[] valMid = new byte[]{20, 21};
			commitLocal(a, procA, keyMid, valMid, HINT_SEQ_MID);
			shipAllFrom(a, "tip-b");
			waitKey(procB, keyMid, valMid, KEY_WAIT_MS);

			final int sealed = a.dumpDomainSnapshot(DOMAIN);
			assertTrue(sealed >= 0, "seal dump must not fail");
			a.truncateSafe(DOMAIN, SHARD);
			final long truncated = a.getOpLog().truncatedThrough(DOMAIN, SHARD);
			assertTrue(truncated > 0L, "truncate watermark must advance");

			final long writerTip = a.getOrchidNode().getLastCommittedSeq();
			assertTrue(writerTip > tipBeforeIsolate, "writer tip must advance past follower tip");
			// No post-truncate commits: live OpLog cannot re-ship the gap — sealed + tip jump required.

			LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(ISOLATE_SETTLE_MS));
			final GridConfigurationProperties propsC2 = ReplTestSupport.props(
					"tip-c", CLUSTER, "dc-a", portC, tempDir.resolve("c"),
					List.of(ReplTestSupport.peer("tip-a", "dc-a", portA),
							ReplTestSupport.peer("tip-b", "dc-a", portB)));
			final GridEntriesProcessor procC2 = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
			c = new ReplicationCoordinator(propsC2);
			c.registerDomain(DOMAIN, ReplTestSupport.singleShard(procC2), true);
			c.start();
			a.reconnectPeer("tip-c");
			b.reconnectPeer("tip-c");

			final long deadline = System.currentTimeMillis() + CATCH_UP_TIMEOUT_MS;
			boolean tipCaught = false;
			boolean mapCaught = false;
			while (System.currentTimeMillis() < deadline) {
				shipAllFrom(a, "tip-c");
				mapCaught = bytesEqual(procC2.get(keyEarly), valEarly)
						&& bytesEqual(procC2.get(keyMid), valMid);
				final long peerTip = a.getOrchidNode().getLastCommittedSeq();
				final long localTip = c.getOrchidNode().getLastCommittedSeq();
				tipCaught = localTip >= peerTip && localTip >= writerTip;
				if (mapCaught && tipCaught) {
					break;
				}
				LockSupport.parkNanos(PARK_NANOS);
			}

			assertTrue(mapCaught,
					"follower map must restore pre/post-truncate keys; early="
							+ (procC2.get(keyEarly) != null) + " mid=" + (procC2.get(keyMid) != null)
							+ " truncatedThrough=" + truncated);
			assertArrayEquals(valEarly, procC2.get(keyEarly));
			assertArrayEquals(valMid, procC2.get(keyMid));
			assertTrue(tipCaught,
					"follower orchid tip must catch peer tip after sealed baseline; local="
							+ c.getOrchidNode().getLastCommittedSeq()
							+ " writer=" + a.getOrchidNode().getLastCommittedSeq()
							+ " truncatedThrough=" + truncated);
		} finally {
			stopQuietly(a);
			stopQuietly(b);
			stopQuietly(c);
		}
	}

	private static void commitLocal(ReplicationCoordinator coord, GridEntriesProcessor proc,
	                                byte[] key, byte[] value, long hintSeq) throws Exception {
		final ReplicationOp op = OpLogCodec.withChecksum(new ReplicationOp(
				DOMAIN, SHARD, hintSeq, ReplicationOpType.UPSERT, key, value, TX_ID, PREV_OP_SEQ
		));
		final long seq = coord.getOrchidNode().appendAndWaitCommit(op).get(COMMIT_TIMEOUT_SEC, TimeUnit.SECONDS);
		final ReplicationOp committed = OpLogCodec.withChecksum(new ReplicationOp(
				DOMAIN, SHARD, seq, ReplicationOpType.UPSERT, key, value, TX_ID, PREV_OP_SEQ
		));
		coord.getOpLog().append(committed);
		coord.getOrchidNode().confirmPersisted(seq);
		proc.installCommitted(key, value, false);
		coord.getNodeState().advanceApplied(DOMAIN, SHARD, seq);
	}

	private static void shipAllFrom(ReplicationCoordinator from, String peerId) {
		final List<ReplicationOp> ops = from.getOpLog().readFrom(DOMAIN, SHARD, SHIP_FROM_SEQ, SHIP_BATCH_LIMIT);
		if (ops.isEmpty()) {
			return;
		}
		from.getNettyTransport().pushSegment(peerId, new OpLogSegment(
				DOMAIN, SHARD, ops.getFirst().opSeq(), ops.getLast().opSeq(), ops,
				OpLogCodec.segmentChecksum(ops)
		));
	}

	private static void waitThreeSynced(ReplicationCoordinator a, ReplicationCoordinator b,
	                                    ReplicationCoordinator c, long timeoutMs) {
		final long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			if (a.getOrchidNode().isSynced() && b.getOrchidNode().isSynced() && c.getOrchidNode().isSynced()
					&& a.getOrchidNode().isPhaseRankedProposer()) {
				return;
			}
			LockSupport.parkNanos(PARK_NANOS);
		}
		assertTrue(a.getOrchidNode().isPhaseRankedProposer());
	}

	private static void waitKey(GridEntriesProcessor proc, byte[] key, byte[] value, long timeoutMs) {
		final long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			final byte[] got = proc.get(key);
			if (bytesEqual(got, value)) {
				return;
			}
			LockSupport.parkNanos(PARK_NANOS);
		}
		assertArrayEquals(value, proc.get(key));
	}

	private static boolean bytesEqual(byte[] left, byte[] right) {
		if (left == null || right == null) {
			return false;
		}
		if (left.length != right.length) {
			return false;
		}
		for (int i = 0; i < left.length; i++) {
			if (left[i] != right[i]) {
				return false;
			}
		}
		return true;
	}

	private static void stopQuietly(ReplicationCoordinator coord) {
		if (coord == null) {
			return;
		}
		try {
			coord.stop();
		} catch (Exception ignored) {
			// best-effort
		}
	}

	private static int freePort() throws Exception {
		try (ServerSocket s = new ServerSocket(0)) {
			return s.getLocalPort();
		}
	}
}
