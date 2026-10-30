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
import org.genfork.grid.replication.util.SealedHydrateTxFenceUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-stream fence around sealed/LAZY OpLog hydrate.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
class SealedHydrateTxFenceUtilTest {
	private static final String DOMAIN = "fence.Tx";
	private static final int SHARD_A = 0;
	private static final int SHARD_B = 1;
	private static final long NODE_SCHEMA_EPOCH = 1L;
	private static final long CHECKSUM_PLACEHOLDER = 0L;

	@TempDir
	Path tempDir;

	@Test
	void beforeClearsOpenTxOnTargetShardOnly() throws Exception {
		final ReplicationNodeState state =
				new ReplicationNodeState("fence-1", "fence", "dc-a", NODE_SCHEMA_EPOCH);
		try (OpLog opLog = new OpLog(tempDir.resolve("oplog"), false)) {
			final GridEntriesProcessor p0 = new GridEntriesProcessor(SHARD_A, new GridScalableMap(), null, null);
			final GridEntriesProcessor p1 = new GridEntriesProcessor(SHARD_B, new GridScalableMap(), null, null);
			final ReplicaApplier applier = new ReplicaApplier(
					state, opLog, shard -> shard == SHARD_A ? p0 : p1, null, true);

			applier.apply(OpLogCodec.withChecksum(new ReplicationOp(
					DOMAIN, SHARD_A, 1L, ReplicationOpType.TX_BEGIN,
					new byte[]{1}, null, NODE_SCHEMA_EPOCH, CHECKSUM_PLACEHOLDER
			)), false);
			applier.apply(OpLogCodec.withChecksum(new ReplicationOp(
					DOMAIN, SHARD_A, 2L, ReplicationOpType.UPSERT,
					new byte[]{10}, new byte[]{99}, NODE_SCHEMA_EPOCH, CHECKSUM_PLACEHOLDER
			)), false);
			applier.apply(OpLogCodec.withChecksum(new ReplicationOp(
					DOMAIN, SHARD_B, 3L, ReplicationOpType.TX_BEGIN,
					new byte[]{2}, null, NODE_SCHEMA_EPOCH, CHECKSUM_PLACEHOLDER
			)), false);
			applier.apply(OpLogCodec.withChecksum(new ReplicationOp(
					DOMAIN, SHARD_B, 4L, ReplicationOpType.UPSERT,
					new byte[]{20}, new byte[]{77}, NODE_SCHEMA_EPOCH, CHECKSUM_PLACEHOLDER
			)), false);

			SealedHydrateTxFenceUtil.beforeSealedOrLazyOpLogHydrate(applier, DOMAIN, SHARD_A);

			assertFalse(applier.hasOpenTx(DOMAIN, SHARD_A));
			assertTrue(applier.hasOpenTx(DOMAIN, SHARD_B));
		}
	}

	@Test
	void afterClearsIncompleteTail() throws Exception {
		final ReplicationNodeState state =
				new ReplicationNodeState("fence-2", "fence", "dc-a", NODE_SCHEMA_EPOCH);
		try (OpLog opLog = new OpLog(tempDir.resolve("oplog2"), false)) {
			final GridEntriesProcessor processor =
					new GridEntriesProcessor(SHARD_A, new GridScalableMap(), null, null);
			final ReplicaApplier applier =
					new ReplicaApplier(state, opLog, shard -> processor, null, true);

			applier.apply(OpLogCodec.withChecksum(new ReplicationOp(
					DOMAIN, SHARD_A, 1L, ReplicationOpType.TX_BEGIN,
					new byte[]{1}, null, NODE_SCHEMA_EPOCH, CHECKSUM_PLACEHOLDER
			)), false);
			assertTrue(applier.hasOpenTx(DOMAIN, SHARD_A));

			SealedHydrateTxFenceUtil.afterOpLogHydrate(applier, DOMAIN, SHARD_A);
			assertFalse(applier.hasOpenTx(DOMAIN, SHARD_A));
		}
	}

	@Test
	void nullApplierIsNoop() {
		SealedHydrateTxFenceUtil.beforeSealedOrLazyOpLogHydrate(null, DOMAIN, SHARD_A);
		SealedHydrateTxFenceUtil.afterOpLogHydrate(null, DOMAIN, SHARD_A);
	}
}
