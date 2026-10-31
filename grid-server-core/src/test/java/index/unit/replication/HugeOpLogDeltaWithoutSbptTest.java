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
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.replication.apply.ReplicaApplier;
import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.tx.TxEnvelopeCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Huge OpLog without {@code .sbpt}: at-risk dump heals when dump gates are NONE;
 * ceiling {@code MAX_OPLOG_DELTA_WITHOUT_SBPT} is never raised. ENVELOPE mid-TX defer
 * remains (closure/orphan paths covered by {@link LazyMultiStreamEnvelopeOrphanSealIT}).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
class HugeOpLogDeltaWithoutSbptTest {
	private static final String DOMAIN = "huge_oplog_gate";
	private static final String DOMAIN_B = "huge_oplog_gate_b";
	/** Same ceiling as {@code SealedHydrateService.MAX_OPLOG_DELTA_WITHOUT_SBPT}. */
	private static final long MAX_OPLOG_DELTA_WITHOUT_SBPT = 100_000L;
	private static final long PAD_SEQ = MAX_OPLOG_DELTA_WITHOUT_SBPT + 1L;
	private static final long TX_ID = 0x0e99L;
	private static final byte[] KEY = "k1".getBytes(StandardCharsets.UTF_8);
	private static final byte[] VAL = "v1".getBytes(StandardCharsets.UTF_8);
	private static final AtomicInteger DIR_SEQ = new AtomicInteger();

	@TempDir
	Path tmp;

	@Test
	void ensureShardHydratedAtRiskDumpsWhenGatesClear() {
		final ReplicationCoordinator coord = startCoord("ok");
		try {
			final GridEntriesProcessor proc = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
			coord.registerDomain(DOMAIN, shard -> shard == 0 ? proc : null, true);

			coord.getOpLog().append(OpLogCodec.withChecksum(new ReplicationOp(
					DOMAIN, 0, PAD_SEQ, ReplicationOpType.UPSERT, KEY, VAL, 1L, 0L)));

			assertDoesNotThrow(() -> coord.ensureShardHydrated(DOMAIN, 0),
					"gates NONE → at-risk dump then hydrate; ceiling not raised");
		} finally {
			coord.stop();
		}
	}

	@Test
	void ensureShardHydratedRejectsWhenDumpCannotCreateUsableSeal() {
		// Orphan+pad heals without raising ceiling (closure + at-risk dump).
		final ReplicationCoordinator coord = startCoord("orphan");
		try {
			final GridEntriesProcessor procA = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
			final GridEntriesProcessor procB = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
			coord.registerDomain(DOMAIN, shard -> shard == 0 ? procA : null, true);
			coord.registerDomain(DOMAIN_B, shard -> shard == 0 ? procB : null, true);

			final byte[] txKey = Long.toHexString(TX_ID).getBytes(StandardCharsets.UTF_8);
			final byte[] membership = TxEnvelopeCodec.encode(List.of(
					new TxEnvelopeCodec.StreamRef(DOMAIN, 0),
					new TxEnvelopeCodec.StreamRef(DOMAIN_B, 0)));
			final ReplicaApplier applierA = coord.applier(DOMAIN);
			applierA.apply(OpLogCodec.withChecksum(new ReplicationOp(
					DOMAIN, 0, 1L, ReplicationOpType.TX_BEGIN, txKey, membership, 1L, 0L)), false);
			applierA.apply(OpLogCodec.withChecksum(new ReplicationOp(
					DOMAIN, 0, 2L, ReplicationOpType.UPSERT, KEY, VAL, 1L, 0L)), false);
			applierA.apply(OpLogCodec.withChecksum(new ReplicationOp(
					DOMAIN, 0, 3L, ReplicationOpType.TX_COMMIT, txKey, null, 1L, 0L)), false);

			coord.getOpLog().append(OpLogCodec.withChecksum(new ReplicationOp(
					DOMAIN, 0, PAD_SEQ, ReplicationOpType.UPSERT,
					"pad".getBytes(StandardCharsets.UTF_8), VAL, 1L, 0L)));

			assertDoesNotThrow(() -> coord.ensureShardHydrated(DOMAIN, 0));
			assertTrue(PAD_SEQ > MAX_OPLOG_DELTA_WITHOUT_SBPT);
		} finally {
			coord.stop();
		}
	}

	@Test
	void emptyMergeHighTipAtRiskDumpTruncatesTipWithoutRaisingCeiling() {
		final ReplicationCoordinator coord = startCoord("empty-merge");
		try {
			final GridEntriesProcessor proc = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
			coord.registerDomain(DOMAIN, shard -> shard == 0 ? proc : null, true);

			final byte[] txKey = Long.toHexString(TX_ID + 1L).getBytes(StandardCharsets.UTF_8);
			// Markers only — merge stays empty while tip exceeds without-.sbpt ceiling.
			coord.getOpLog().append(OpLogCodec.withChecksum(new ReplicationOp(
					DOMAIN, 0, PAD_SEQ, ReplicationOpType.TX_COMMIT, txKey, null, 1L, 0L)));

			assertDoesNotThrow(() -> coord.ensureShardHydrated(DOMAIN, 0),
					"net-empty/marker tip must truncate on at-risk dump — ceiling unchanged");
			assertTrue(PAD_SEQ > MAX_OPLOG_DELTA_WITHOUT_SBPT);
			assertTrue(coord.getOpLog().lastSeq(DOMAIN, 0) <= MAX_OPLOG_DELTA_WITHOUT_SBPT
							|| coord.getOpLog().size(DOMAIN, 0) == 0,
					"empty-merge dump must retire huge tip so reopen is not permanently fenced");
		} finally {
			coord.stop();
		}
	}

	private ReplicationCoordinator startCoord(String suffix) {
		final GridConfigurationProperties props = new GridConfigurationProperties();
		props.getDurability().setEnabled(true);
		props.getDurability().setHydrateMode("LAZY");
		props.getDurability().setAdaptiveDiskFirst(false);
		props.getReplication().setEnabled(false);
		props.getReplication().getOpLog().setDataDir(
				tmp.resolve("repl-" + suffix + "-" + DIR_SEQ.incrementAndGet()).toString());
		props.getReplication().getOpLog().setFsync(false);
		props.getReplication().setClusterId("c-huge-" + suffix);
		props.getReplication().setNodeId("n1");
		final ReplicationCoordinator coord = new ReplicationCoordinator(props);
		coord.start();
		return coord;
	}
}
