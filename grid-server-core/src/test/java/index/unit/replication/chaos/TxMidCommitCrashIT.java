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

import org.genfork.grid.mem.GridScalableMap;
import org.genfork.grid.mem.stage.GridEntriesProcessor;
import org.genfork.grid.replication.ReplicationNodeState;
import org.genfork.grid.replication.apply.ReplicaApplier;
import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.log.OpLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BEGIN + mutations without COMMIT must not leave partial rows in the peer map.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public class TxMidCommitCrashIT {

	@TempDir
	Path tempDir;

	@Test
	void beginOpsWithoutCommitLeavesMapClean() throws Exception {
		final ReplicationNodeState state = new ReplicationNodeState("tx-1", "tx-crash", "dc-a", 1L);
		try (OpLog opLog = new OpLog(tempDir.resolve("oplog"), false)) {
			final GridScalableMap map = new GridScalableMap();
			final GridEntriesProcessor processor = new GridEntriesProcessor(0, map, null, null);
			final ReplicaApplier applier = new ReplicaApplier(state, opLog, shard -> processor, null, true);

			applier.apply(OpLogCodec.withChecksum(new ReplicationOp(
					"chaos.Tx", 0, 1L, ReplicationOpType.TX_BEGIN, new byte[]{1}, null, 1L, 0L
			)), false);
			applier.apply(OpLogCodec.withChecksum(new ReplicationOp(
					"chaos.Tx", 0, 2L, ReplicationOpType.UPSERT, new byte[]{10}, new byte[]{99}, 1L, 0L
			)), false);
			applier.apply(OpLogCodec.withChecksum(new ReplicationOp(
					"chaos.Tx", 0, 3L, ReplicationOpType.UPSERT, new byte[]{11}, new byte[]{88}, 1L, 0L
			)), false);

			assertTrue(applier.hasOpenTx("chaos.Tx", 0));
			assertTrue(applier.openTxStagedCount("chaos.Tx", 0) >= 2);
			assertNull(processor.get(new byte[]{10}), "partial TX row must not be visible");
			assertNull(processor.get(new byte[]{11}), "partial TX row must not be visible");

			// Simulate crash / incomplete unit end: discard staging.
			applier.discardOpenTxStaging();
			assertNull(processor.get(new byte[]{10}));
			assertNull(processor.get(new byte[]{11}));
		}
	}

	@Test
	void abortDiscardsStaging() throws Exception {
		final ReplicationNodeState state = new ReplicationNodeState("tx-2", "tx-crash", "dc-a", 1L);
		try (OpLog opLog = new OpLog(tempDir.resolve("oplog2"), false)) {
			final GridEntriesProcessor processor = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
			final ReplicaApplier applier = new ReplicaApplier(state, opLog, shard -> processor, null, true);

			applier.apply(OpLogCodec.withChecksum(new ReplicationOp(
					"chaos.Tx", 0, 1L, ReplicationOpType.TX_BEGIN, new byte[]{1}, null, 1L, 0L
			)), false);
			applier.apply(OpLogCodec.withChecksum(new ReplicationOp(
					"chaos.Tx", 0, 2L, ReplicationOpType.UPSERT, new byte[]{10}, new byte[]{99}, 1L, 0L
			)), false);
			applier.apply(OpLogCodec.withChecksum(new ReplicationOp(
					"chaos.Tx", 0, 3L, ReplicationOpType.TX_ABORT, new byte[]{1}, null, 1L, 0L
			)), false);

			assertNull(processor.get(new byte[]{10}));
		}
	}

	@Test
	void commitFlushesStagingAsUnit() throws Exception {
		final ReplicationNodeState state = new ReplicationNodeState("tx-3", "tx-crash", "dc-a", 1L);
		try (OpLog opLog = new OpLog(tempDir.resolve("oplog3"), false)) {
			final GridEntriesProcessor processor = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
			final ReplicaApplier applier = new ReplicaApplier(state, opLog, shard -> processor, null, true);

			applier.apply(OpLogCodec.withChecksum(new ReplicationOp(
					"chaos.Tx", 0, 1L, ReplicationOpType.TX_BEGIN, new byte[]{1}, null, 1L, 0L
			)), false);
			applier.apply(OpLogCodec.withChecksum(new ReplicationOp(
					"chaos.Tx", 0, 2L, ReplicationOpType.UPSERT, new byte[]{10}, new byte[]{99}, 1L, 0L
			)), false);
			assertNull(processor.get(new byte[]{10}));

			applier.apply(OpLogCodec.withChecksum(new ReplicationOp(
					"chaos.Tx", 0, 3L, ReplicationOpType.TX_COMMIT, new byte[]{1}, null, 1L, 0L
			)), false);

			assertTrue(processor.get(new byte[]{10})[0] == 99);
		}
	}

	@Test
	void commitBehindWatermark_stillFlushesStaging() throws Exception {
		final ReplicationNodeState state = new ReplicationNodeState("tx-wm", "tx-crash", "dc-a", 1L);
		try (OpLog opLog = new OpLog(tempDir.resolve("oplog-wm"), false)) {
			final GridEntriesProcessor processor = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
			final ReplicaApplier applier = new ReplicaApplier(state, opLog, shard -> processor, null, true);

			applier.apply(OpLogCodec.withChecksum(new ReplicationOp(
					"chaos.Tx", 0, 1L, ReplicationOpType.TX_BEGIN, new byte[]{1}, null, 1L, 0L
			)), false);
			applier.apply(OpLogCodec.withChecksum(new ReplicationOp(
					"chaos.Tx", 0, 2L, ReplicationOpType.UPSERT, new byte[]{10}, new byte[]{99}, 1L, 0L
			)), false);
			// Simulate tip/repair race: watermark already at COMMIT seq before TX_COMMIT apply.
			state.advanceApplied("chaos.Tx", 0, 3L);
			applier.apply(OpLogCodec.withChecksum(new ReplicationOp(
					"chaos.Tx", 0, 3L, ReplicationOpType.TX_COMMIT, new byte[]{1}, null, 1L, 0L
			)), false);

			assertNotNull(processor.get(new byte[]{10}), "COMMIT behind watermark must flush staging");
			assertEquals(99, processor.get(new byte[]{10})[0]);
			assertFalse(applier.hasOpenTx("chaos.Tx", 0));
		}
	}

	@Test
	void discardOpenTxStaging_perStreamPreservesOtherShard() throws Exception {
		final ReplicationNodeState state = new ReplicationNodeState("tx-ps", "tx-crash", "dc-a", 1L);
		try (OpLog opLog = new OpLog(tempDir.resolve("oplog-ps"), false)) {
			final GridEntriesProcessor p0 = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
			final GridEntriesProcessor p1 = new GridEntriesProcessor(1, new GridScalableMap(), null, null);
			final ReplicaApplier applier = new ReplicaApplier(state, opLog, shard -> shard == 0 ? p0 : p1, null, true);

			applier.apply(OpLogCodec.withChecksum(new ReplicationOp(
					"chaos.Tx", 0, 1L, ReplicationOpType.TX_BEGIN, new byte[]{1}, null, 1L, 0L
			)), false);
			applier.apply(OpLogCodec.withChecksum(new ReplicationOp(
					"chaos.Tx", 0, 2L, ReplicationOpType.UPSERT, new byte[]{10}, new byte[]{99}, 1L, 0L
			)), false);
			applier.apply(OpLogCodec.withChecksum(new ReplicationOp(
					"chaos.Tx", 1, 3L, ReplicationOpType.TX_BEGIN, new byte[]{2}, null, 1L, 0L
			)), false);
			applier.apply(OpLogCodec.withChecksum(new ReplicationOp(
					"chaos.Tx", 1, 4L, ReplicationOpType.UPSERT, new byte[]{20}, new byte[]{77}, 1L, 0L
			)), false);

			applier.discardOpenTxStaging("chaos.Tx", 1);
			assertEquals(1, applier.openTxStagedCount("chaos.Tx", 0), "shard0 staging must survive");
			assertFalse(applier.hasOpenTx("chaos.Tx", 1));

			applier.apply(OpLogCodec.withChecksum(new ReplicationOp(
					"chaos.Tx", 0, 5L, ReplicationOpType.TX_COMMIT, new byte[]{1}, null, 1L, 0L
			)), false);
			assertEquals(99, p0.get(new byte[]{10})[0]);
			assertNull(p1.get(new byte[]{20}));
		}
	}

	@Test
	void interleavedBeginWithNonEmptyStaging_failsClosed() throws Exception {
		final ReplicationNodeState state = new ReplicationNodeState("tx-4", "tx-crash", "dc-a", 1L);
		try (OpLog opLog = new OpLog(tempDir.resolve("oplog4"), false)) {
			final GridEntriesProcessor processor = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
			final ReplicaApplier applier = new ReplicaApplier(state, opLog, shard -> processor, null, true);

			applier.apply(OpLogCodec.withChecksum(new ReplicationOp(
					"chaos.Tx", 0, 1L, ReplicationOpType.TX_BEGIN, new byte[]{1}, null, 1L, 0L
			)), true);
			applier.apply(OpLogCodec.withChecksum(new ReplicationOp(
					"chaos.Tx", 0, 2L, ReplicationOpType.UPSERT, new byte[]{10}, new byte[]{99}, 1L, 0L
			)), true);
			assertEquals(1, applier.openTxStagedCount("chaos.Tx", 0));

			final IllegalStateException ex = assertThrows(
					IllegalStateException.class,
					() -> applier.apply(OpLogCodec.withChecksum(new ReplicationOp(
							"chaos.Tx", 0, 3L, ReplicationOpType.TX_BEGIN, new byte[]{2}, null, 1L, 0L
					)), true));
			assertTrue(ex.getMessage().contains("non-empty staging"),
					() -> "message=" + ex.getMessage());
			assertEquals(1, applier.openTxStagedCount("chaos.Tx", 0), "prior staging must remain");

			applier.apply(OpLogCodec.withChecksum(new ReplicationOp(
					"chaos.Tx", 0, 4L, ReplicationOpType.TX_COMMIT, new byte[]{1}, null, 1L, 0L
			)), true);
			assertNotNull(processor.get(new byte[]{10}));
			assertEquals(99, processor.get(new byte[]{10})[0]);
		}
	}
}