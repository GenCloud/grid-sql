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
import org.genfork.grid.replication.orchid.OrchidNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tip-behind admit must fail-closed even when peer tip is only slightly ahead
 * ({@code peerTip - localTip <= maxProposeInFlight}) — former hole that allowed dual-writer concat forks.
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
public class OrchidTipBehindConcatAdmitIT {

	private static final String DOMAIN = "chaos.TipAdmit";
	private static final String CLUSTER = "tadm";
	private static final String NODE = "tadm-1";
	private static final String FAKE_PEER = "tadm-peer";
	private static final long TIP_AHEAD_DELTA = 3L;
	private static final long SYNC_TIMEOUT_MS = 8_000L;

	@TempDir(cleanup = CleanupMode.NEVER)
	Path tempDir;

	@Test
	void appendAndAdmitDeniedWhenPeerTipSlightlyAhead() throws Exception {
		final int port = freePort();
		final GridConfigurationProperties props = ReplTestSupport.safetyProps(
				NODE, CLUSTER, "dc-a", port, tempDir.resolve("solo"), List.of());
		props.getReplication().getOrchid().setOrderThreshold(0.0);
		props.getReplication().getOrchid().setMaxProposeInFlight(64);

		final GridEntriesProcessor proc = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
		final ReplicationCoordinator coord = new ReplicationCoordinator(props);
		try {
			coord.registerDomain(DOMAIN, ReplTestSupport.singleShard(proc), true);
			coord.start();
			waitSynced(coord, SYNC_TIMEOUT_MS);
			assertTrue(coord.getOrchidNode().isPhaseRankedProposer());

			final byte[] key = new byte[]{1, 6};
			final byte[] value = new byte[]{4, 4};
			final ReplicationOp seed = OpLogCodec.withChecksum(new ReplicationOp(
					DOMAIN, 0, 1L, ReplicationOpType.UPSERT, key, value, 1L, 0L));
			final long localTip = coord.getOrchidNode().appendAndWaitCommit(seed).get(8, TimeUnit.SECONDS);
			assertTrue(localTip >= 1L);

			final OrchidNode orchid = coord.getOrchidNode();
			final long peerTip = localTip + TIP_AHEAD_DELTA;
			assertTrue(peerTip - localTip <= orchid.getMaxProposeInFlight(),
					"delta must sit inside former inFlight tip hole");
			orchid.testingNotePeerCommittedSeq(FAKE_PEER, peerTip);
			assertTrue(orchid.maxSeenPeerCommittedSeq() > orchid.getLastCommittedSeq());

			final ReplicationOp steal = OpLogCodec.withChecksum(new ReplicationOp(
					DOMAIN, 0, localTip + 1L, ReplicationOpType.UPSERT,
					new byte[]{2, 2}, new byte[]{3, 3}, 1L, 0L));
			final CompletionException thrown = assertThrows(CompletionException.class,
					() -> orchid.appendAndWaitCommit(steal).join());
			assertTrue(rootCauseIsTipBehind(thrown),
					() -> "expected tip-behind OrchidNotSyncedException, got " + thrown);

			assertTrue(!coord.isWriterEligible(),
					"writerEligible must be false while peer tip ahead of local");
		} finally {
			try {
				coord.stop();
			} catch (Exception ignored) {
			}
		}
	}

	private static boolean rootCauseIsTipBehind(Throwable thrown) {
		Throwable cur = thrown;
		while (cur != null) {
			if (cur instanceof OrchidNotSyncedException) {
				final String msg = cur.getMessage();
				return msg != null && msg.contains("tip behind");
			}
			cur = cur.getCause();
		}
		return false;
	}

	private static void waitSynced(ReplicationCoordinator coord, long timeoutMs) throws InterruptedException {
		final long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline && !coord.getOrchidNode().isSynced()) {
			Thread.sleep(20L);
		}
		if (!coord.getOrchidNode().isSynced()) {
			fail("orchid not synced");
		}
	}

	private static int freePort() throws Exception {
		try (ServerSocket s = new ServerSocket(0)) {
			return s.getLocalPort();
		}
	}
}
