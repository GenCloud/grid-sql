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

import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.log.OpLog;
import org.genfork.grid.replication.orchid.DigestQuorum;
import org.genfork.grid.replication.orchid.OrchidNode;
import org.genfork.grid.replication.orchid.OrchidTransport;
import org.genfork.grid.replication.util.OrchidTipCatchUpUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit: journal tip catch-up and sealed-baseline peerTip jump (never max sealed wm).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
public class OrchidTipCatchUpUtilTest {

	private static final String DOMAIN = "tip.catch";
	private static final String DOMAIN_B = "tip.catch.b";
	private static final String NODE_ID = "tip-u1";
	private static final int SHARD = 0;
	private static final long PEER_TIP = 40L;
	private static final long JOURNAL_SEQ = 7L;
	private static final long STREAM_A_SEQ = 3L;
	private static final long STREAM_B_SEQ = 11L;
	private static final long JOURNAL_AHEAD_OF_PEER = 5L;
	private static final int PACKS_NONE = 0;
	private static final double COUPLING = 15.0;
	private static final double NATURAL_FREQ_HZ = 1.0;
	private static final double ORDER_THRESHOLD = 0.85;
	private static final long TICK_MS = 10L;
	private static final byte[] KEY_BYTES = new byte[]{1};
	private static final byte[] VALUE_BYTES = new byte[]{2};
	private static final long TX_ID = 1L;
	private static final long PREV_OP_SEQ = 0L;

	private static final OrchidTransport NOOP = new OrchidTransport() {
		@Override
		public void sendPhase(String toNodeId, OrchidTransport.OrchidPhaseMessage message) {
		}

		@Override
		public void broadcastPhase(OrchidTransport.OrchidPhaseMessage message) {
		}

		@Override
		public void sendPropose(String toNodeId, OrchidTransport.OrchidProposeMessage message) {
		}

		@Override
		public void broadcastPropose(OrchidTransport.OrchidProposeMessage message) {
		}

		@Override
		public void sendCommit(String toNodeId, OrchidTransport.OrchidCommitMessage message) {
		}

		@Override
		public void broadcastCommit(OrchidTransport.OrchidCommitMessage message) {
		}

		@Override
		public void sendNack(String toNodeId, OrchidTransport.OrchidNackMessage message) {
		}
	};

	@TempDir(cleanup = CleanupMode.NEVER)
	Path tempDir;

	@Test
	void journalBoundTracksLastSeqOnly() throws Exception {
		try (OpLog opLog = new OpLog(tempDir.resolve("oplog-j"), true)) {
			assertEquals(OrchidTipCatchUpUtil.NO_SEQ, OrchidTipCatchUpUtil.journalBound(opLog));
			appendUpsert(opLog, DOMAIN, SHARD, JOURNAL_SEQ);
			assertEquals(JOURNAL_SEQ, OrchidTipCatchUpUtil.journalBound(opLog));
			assertEquals(opLog.lastSeq(DOMAIN, SHARD), OrchidTipCatchUpUtil.journalBound(opLog));
		}
	}

	@Test
	void catchUpFromJournalAdvancesTip() throws Exception {
		try (OpLog opLog = new OpLog(tempDir.resolve("oplog-c"), true)) {
			appendUpsert(opLog, DOMAIN, SHARD, JOURNAL_SEQ);
			final OrchidNode orchid = newOrchid();
			assertEquals(OrchidTipCatchUpUtil.NO_SEQ, orchid.getLastCommittedSeq());
			final long jumped = OrchidTipCatchUpUtil.catchUpFromJournal(orchid, opLog);
			assertEquals(JOURNAL_SEQ, jumped);
			assertEquals(JOURNAL_SEQ, orchid.getLastCommittedSeq());
			assertEquals(OrchidTipCatchUpUtil.NO_SEQ, OrchidTipCatchUpUtil.catchUpFromJournal(orchid, opLog));
		}
	}

	@Test
	void peerTipJumpRequiresPacksAndRejectsBlindHello() throws Exception {
		try (OpLog opLog = new OpLog(tempDir.resolve("oplog-p"), true)) {
			final OrchidNode orchid = newOrchid();
			assertEquals(OrchidTipCatchUpUtil.NO_SEQ,
					OrchidTipCatchUpUtil.catchUpToPeerTipAfterSealedBaselineRound(
							orchid, opLog, PEER_TIP, PACKS_NONE));
			assertEquals(OrchidTipCatchUpUtil.NO_SEQ, orchid.getLastCommittedSeq());

			final long jumped = OrchidTipCatchUpUtil.catchUpToPeerTipAfterSealedBaselineRound(
					orchid, opLog, PEER_TIP, OrchidTipCatchUpUtil.MIN_SEALED_PACKS_FOR_PEER_TIP);
			assertEquals(PEER_TIP, jumped);
			assertEquals(PEER_TIP, orchid.getLastCommittedSeq());
		}
	}

	@Test
	void peerTipPrefersJournalWhenJournalCoversPeer() throws Exception {
		try (OpLog opLog = new OpLog(tempDir.resolve("oplog-pref"), true)) {
			final long high = PEER_TIP + JOURNAL_AHEAD_OF_PEER;
			appendUpsert(opLog, DOMAIN, SHARD, high);
			final OrchidNode orchid = newOrchid();
			final long peerPath = OrchidTipCatchUpUtil.catchUpToPeerTipAfterSealedBaselineRound(
					orchid, opLog, PEER_TIP, OrchidTipCatchUpUtil.MIN_SEALED_PACKS_FOR_PEER_TIP);
			assertEquals(OrchidTipCatchUpUtil.NO_SEQ, peerPath,
					"peer path must not claim jump when journal covers peerTip");
			assertEquals(high, orchid.getLastCommittedSeq());
		}
	}

	@Test
	void journalBoundIsMaxAcrossStreamsNotSealedWmInput() throws Exception {
		try (OpLog opLog = new OpLog(tempDir.resolve("oplog-m"), true)) {
			appendUpsert(opLog, DOMAIN, SHARD, STREAM_A_SEQ);
			appendUpsert(opLog, DOMAIN_B, SHARD, STREAM_B_SEQ);
			assertEquals(STREAM_B_SEQ, OrchidTipCatchUpUtil.journalBound(opLog));
		}
	}

	private static OrchidNode newOrchid() {
		return new OrchidNode(
				NODE_ID,
				COUPLING,
				NATURAL_FREQ_HZ,
				ORDER_THRESHOLD,
				TICK_MS,
				DigestQuorum.MAJORITY,
				NOOP,
				List.of());
	}

	private static void appendUpsert(OpLog opLog, String domain, int shard, long seq) throws Exception {
		final ReplicationOp op = OpLogCodec.withChecksum(new ReplicationOp(
				domain, shard, seq, ReplicationOpType.UPSERT,
				KEY_BYTES, VALUE_BYTES, TX_ID, PREV_OP_SEQ));
		opLog.append(op);
	}
}
