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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public class ReplicaApplierTest {

	@TempDir
	Path tempDir;

	@Test
	void monotonicApplyAndAwait() throws Exception {
		final ReplicationNodeState state = new ReplicationNodeState("n1", "c", "dc-a", 1L);
		try (OpLog opLog = new OpLog(tempDir.resolve("oplog"), false)) {
			final GridEntriesProcessor processor = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
			final ReplicaApplier applier = new ReplicaApplier(state, opLog, shard -> processor, null, true);

			final ReplicationOp op1 = OpLogCodec.withChecksum(new ReplicationOp(
					"d", 0, 1L, ReplicationOpType.UPSERT, new byte[]{1}, new byte[]{9}, 1L, 0L
			));
			applier.apply(op1, false);
			assertEquals(1L, state.appliedWatermark("d", 0));
			assertEquals(9, processor.get(new byte[]{1})[0]);

			final ReplicationOp op2 = OpLogCodec.withChecksum(new ReplicationOp(
					"d", 0, 2L, ReplicationOpType.UPSERT, new byte[]{1}, new byte[]{2}, 1L, 0L
			));
			applier.apply(op2, false);
			assertEquals(2L, state.appliedWatermark("d", 0));
			assertEquals(2, processor.get(new byte[]{1})[0]);
			assertTrue(applier.awaitApplied("d", 0, 2).get(1, TimeUnit.SECONDS) == null);
		}
	}

	@Test
	void allowsGlobalSeqGapsAcrossShards() throws Exception {
		final ReplicationNodeState state = new ReplicationNodeState("n1", "c", "dc-a", 1L);
		try (OpLog opLog = new OpLog(tempDir.resolve("oplog-gap"), false)) {
			final GridEntriesProcessor p0 = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
			final GridEntriesProcessor p1 = new GridEntriesProcessor(1, new GridScalableMap(), null, null);
			final ReplicaApplier applier = new ReplicaApplier(
					state, opLog, shard -> shard == 0 ? p0 : p1, null, true);

			// Global orchid seq 1 → shard 0, seq 2 → shard 1, seq 3 → shard 0 (gap on shard 0 is expected).
			applier.apply(OpLogCodec.withChecksum(new ReplicationOp(
					"d", 0, 1L, ReplicationOpType.UPSERT, new byte[]{1}, new byte[]{1}, 1L, 0L
			)), false);
			applier.apply(OpLogCodec.withChecksum(new ReplicationOp(
					"d", 1, 2L, ReplicationOpType.UPSERT, new byte[]{2}, new byte[]{2}, 1L, 0L
			)), false);
			applier.apply(OpLogCodec.withChecksum(new ReplicationOp(
					"d", 0, 3L, ReplicationOpType.UPSERT, new byte[]{3}, new byte[]{3}, 1L, 0L
			)), false);

			assertEquals(3L, state.appliedWatermark("d", 0));
			assertEquals(2L, state.appliedWatermark("d", 1));
			assertEquals(1, p0.get(new byte[]{1})[0]);
			assertEquals(3, p0.get(new byte[]{3})[0]);
			assertEquals(2, p1.get(new byte[]{2})[0]);
		}
	}

	@Test
	void ignoresStaleLowerSeq() throws Exception {
		final ReplicationNodeState state = new ReplicationNodeState("n1", "c", "dc-a", 1L);
		try (OpLog opLog = new OpLog(tempDir.resolve("oplog-stale"), false)) {
			final GridEntriesProcessor processor = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
			final ReplicaApplier applier = new ReplicaApplier(state, opLog, shard -> processor, null, true);

			applier.apply(OpLogCodec.withChecksum(new ReplicationOp(
					"d", 0, 5L, ReplicationOpType.UPSERT, new byte[]{1}, new byte[]{5}, 1L, 0L
			)), false);
			assertEquals(5L, state.appliedWatermark("d", 0));
			assertEquals(5, processor.get(new byte[]{1})[0]);

			applier.apply(OpLogCodec.withChecksum(new ReplicationOp(
					"d", 0, 2L, ReplicationOpType.UPSERT, new byte[]{1}, new byte[]{2}, 1L, 0L
			)), false);
			assertEquals(5L, state.appliedWatermark("d", 0));
			assertEquals(5, processor.get(new byte[]{1})[0]);
		}
	}

	@Test
	void forceInstallOverwritesMapAtSameSeq() throws Exception {
		final ReplicationNodeState state = new ReplicationNodeState("n1", "c", "dc-a", 1L);
		try (OpLog opLog = new OpLog(tempDir.resolve("oplog-force"), false)) {
			final GridEntriesProcessor processor = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
			final ReplicaApplier applier = new ReplicaApplier(state, opLog, shard -> processor, null, true);

			applier.apply(OpLogCodec.withChecksum(new ReplicationOp(
					"d", 0, 3L, ReplicationOpType.UPSERT, new byte[]{1}, new byte[]{3}, 1L, 0L
			)), false);
			assertEquals(3, processor.get(new byte[]{1})[0]);

			applier.apply(OpLogCodec.withChecksum(new ReplicationOp(
					"d", 0, 3L, ReplicationOpType.UPSERT, new byte[]{1}, new byte[]{9}, 1L, 0L
			)), false, true);
			assertEquals(3L, state.appliedWatermark("d", 0));
			assertEquals(9, processor.get(new byte[]{1})[0]);
		}
	}

	@Test
	void forceInstallIgnoresStaleOpBehindWatermark() throws Exception {
		final ReplicationNodeState state = new ReplicationNodeState("n1", "c", "dc-a", 1L);
		try (OpLog opLog = new OpLog(tempDir.resolve("oplog-stale-force"), false)) {
			final GridEntriesProcessor processor = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
			final ReplicaApplier applier = new ReplicaApplier(state, opLog, shard -> processor, null, true);

			applier.apply(OpLogCodec.withChecksum(new ReplicationOp(
					"d", 0, 5L, ReplicationOpType.UPSERT, new byte[]{1}, new byte[]{5}, 1L, 0L
			)), false);
			assertEquals(5, processor.get(new byte[]{1})[0]);

			applier.apply(OpLogCodec.withChecksum(new ReplicationOp(
					"d", 0, 2L, ReplicationOpType.UPSERT, new byte[]{1}, new byte[]{2}, 1L, 0L
			)), false, true);
			assertEquals(5L, state.appliedWatermark("d", 0));
			assertEquals(5, processor.get(new byte[]{1})[0]);
		}
	}
}
