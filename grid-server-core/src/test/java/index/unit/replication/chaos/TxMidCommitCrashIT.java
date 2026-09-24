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

import static org.junit.jupiter.api.Assertions.assertNull;
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
}
