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
 * Follower rejoin after writer seal + OpLog truncate: HELLO catch-up / repair must restore
 * map equality for pre-truncate and post-truncate keys (no silent permanent lag).
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
public class RejoinAfterSealTruncateIT {

	private static final String DOMAIN = "chaos.SealRejoin";
	private static final String CLUSTER = "seal-rj";
	private static final long SYNC_TIMEOUT_MS = 15_000L;
	private static final long CATCH_UP_TIMEOUT_MS = 20_000L;
	private static final long PARK_NANOS = TimeUnit.MILLISECONDS.toNanos(50L);

	@TempDir(cleanup = CleanupMode.NEVER)
	Path tempDir;

	@Test
	void followerRejoinsAfterSealTruncateAndSeesLaterWrites() throws Exception {
		final int portA = freePort();
		final int portB = freePort();
		final int portC = freePort();

		final GridConfigurationProperties propsA = ReplTestSupport.props(
				"seal-a", CLUSTER, "dc-a", portA, tempDir.resolve("a"),
				List.of(ReplTestSupport.peer("seal-b", "dc-a", portB),
						ReplTestSupport.peer("seal-c", "dc-a", portC)));
		final GridConfigurationProperties propsB = ReplTestSupport.props(
				"seal-b", CLUSTER, "dc-a", portB, tempDir.resolve("b"),
				List.of(ReplTestSupport.peer("seal-a", "dc-a", portA),
						ReplTestSupport.peer("seal-c", "dc-a", portC)));
		final GridConfigurationProperties propsC = ReplTestSupport.props(
				"seal-c", CLUSTER, "dc-a", portC, tempDir.resolve("c"),
				List.of(ReplTestSupport.peer("seal-a", "dc-a", portA),
						ReplTestSupport.peer("seal-b", "dc-a", portB)));

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
			commitLocal(a, procA, keyEarly, valEarly, 1L);
			shipAllFrom(a, "seal-b");
			shipAllFrom(a, "seal-c");
			waitKey(procB, keyEarly, valEarly, 8_000L);
			waitKey(procC, keyEarly, valEarly, 8_000L);

			// Stop follower C before seal/truncate so its OpLog lags permanently past watermark.
			a.isolatePeer("seal-c");
			b.isolatePeer("seal-c");
			stopQuietly(c);

			final byte[] keyMid = new byte[]{2, 2};
			final byte[] valMid = new byte[]{20, 21};
			commitLocal(a, procA, keyMid, valMid, 2L);
			shipAllFrom(a, "seal-b");
			waitKey(procB, keyMid, valMid, 8_000L);

			final int sealed = a.dumpDomainSnapshot(DOMAIN);
			assertTrue(sealed >= 0, "seal dump must not fail");
			// Force truncate through applied/min-ack among live peers (C forgotten from ack path).
			a.truncateSafe(DOMAIN, 0);
			final long truncated = a.getOpLog().truncatedThrough(DOMAIN, 0);
			assertTrue(truncated > 0L, "truncate watermark must advance");

			final byte[] keyLate = new byte[]{3, 3};
			final byte[] valLate = new byte[]{30, 31};
			commitLocal(a, procA, keyLate, valLate, 3L);
			shipAllFrom(a, "seal-b");
			waitKey(procB, keyLate, valLate, 8_000L);

			// Revive C on same dataDir + ports; reconnect HELLO should catch up or repair.
			LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(300L));
			final GridConfigurationProperties propsC2 = ReplTestSupport.props(
					"seal-c", CLUSTER, "dc-a", portC, tempDir.resolve("c"),
					List.of(ReplTestSupport.peer("seal-a", "dc-a", portA),
							ReplTestSupport.peer("seal-b", "dc-a", portB)));
			final GridEntriesProcessor procC2 = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
			c = new ReplicationCoordinator(propsC2);
			c.registerDomain(DOMAIN, ReplTestSupport.singleShard(procC2), true);
			c.start();
			a.reconnectPeer("seal-c");
			b.reconnectPeer("seal-c");

			final long deadline = System.currentTimeMillis() + CATCH_UP_TIMEOUT_MS;
			boolean caught = false;
			while (System.currentTimeMillis() < deadline) {
				shipAllFrom(a, "seal-c");
				if (bytesEqual(procC2.get(keyEarly), valEarly)
						&& bytesEqual(procC2.get(keyLate), valLate)) {
					caught = true;
					break;
				}
				LockSupport.parkNanos(PARK_NANOS);
			}
			assertTrue(caught,
					"follower must see pre-truncate and post-truncate keys after rejoin; "
							+ "early=" + (procC2.get(keyEarly) != null)
							+ " late=" + (procC2.get(keyLate) != null)
							+ " truncatedThrough=" + truncated);
			assertArrayEquals(valEarly, procC2.get(keyEarly));
			assertArrayEquals(valLate, procC2.get(keyLate));
		} finally {
			stopQuietly(a);
			stopQuietly(b);
			stopQuietly(c);
		}
	}

	private static void commitLocal(ReplicationCoordinator coord, GridEntriesProcessor proc,
	                                byte[] key, byte[] value, long hintSeq) throws Exception {
		final ReplicationOp op = OpLogCodec.withChecksum(new ReplicationOp(
				DOMAIN, 0, hintSeq, ReplicationOpType.UPSERT, key, value, 1L, 0L
		));
		final long seq = coord.getOrchidNode().appendAndWaitCommit(op).get(8, TimeUnit.SECONDS);
		final ReplicationOp committed = OpLogCodec.withChecksum(new ReplicationOp(
				DOMAIN, 0, seq, ReplicationOpType.UPSERT, key, value, 1L, 0L
		));
		coord.getOpLog().append(committed);
		coord.getOrchidNode().confirmPersisted(seq);
		proc.installCommitted(key, value, false);
		coord.getNodeState().advanceApplied(DOMAIN, 0, seq);
	}

	private static void shipAllFrom(ReplicationCoordinator from, String peerId) {
		final List<ReplicationOp> ops = from.getOpLog().readFrom(DOMAIN, 0, 1, 10_000);
		if (ops.isEmpty()) {
			return;
		}
		from.getNettyTransport().pushSegment(peerId, new OpLogSegment(
				DOMAIN, 0, ops.getFirst().opSeq(), ops.getLast().opSeq(), ops,
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

	private static boolean bytesEqual(byte[] a, byte[] b) {
		if (a == null || b == null) {
			return false;
		}
		if (a.length != b.length) {
			return false;
		}
		for (int i = 0; i < a.length; i++) {
			if (a[i] != b[i]) {
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