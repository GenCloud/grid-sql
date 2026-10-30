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
import org.genfork.grid.replication.snapshot.SnapshotService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boot/join fatal repro: live open TX on a stream, then sealed-baseline
 * {@code resetApplied} + {@link SnapshotService#hydrateOpLogFrom} replaying {@code TX_BEGIN}.
 * <p>
 * Without per-stream discard before hydrate, ReplicaApplier fail-closes with
 * {@code TX_BEGIN while open TX already present} and kills SqlServerRuntime boot.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
class SealedPackHydrateOpenTxIT {
	private static final String DOMAIN = "chaos.SealedHydrateOpenTx";
	private static final int SHARD = 4;
	private static final long SEALED_WM = 5L;
	private static final long LIVE_BEGIN_SEQ = 10L;
	private static final long LIVE_UPSERT_SEQ = 11L;
	private static final long NODE_SCHEMA_EPOCH = 1L;
	private static final long CHECKSUM_PLACEHOLDER = 0L;
	private static final byte[] TX_KEY = new byte[]{1};
	private static final byte[] ROW_KEY = new byte[]{10};
	private static final byte[] ROW_VALUE = new byte[]{99};

	@TempDir
	Path tempDir;

	@Test
	void liveOpenTxThenHydrateOpLogFrom_doesNotThrowInterleave() throws Exception {
		final ReplicationNodeState state =
				new ReplicationNodeState("sho-1", "sho", "dc-a", NODE_SCHEMA_EPOCH);
		try (OpLog opLog = new OpLog(tempDir.resolve("oplog"), false)) {
			final GridEntriesProcessor processor =
					new GridEntriesProcessor(SHARD, new GridScalableMap(), null, null);
			final ReplicaApplier applier =
					new ReplicaApplier(state, opLog, shard -> shard == SHARD ? processor : null, null, true);
			final SnapshotService snapshots = new SnapshotService(opLog, state);

			// Live peer apply during catalog bootstrap (open TX + journaled ops).
			applier.apply(OpLogCodec.withChecksum(new ReplicationOp(
					DOMAIN, SHARD, LIVE_BEGIN_SEQ, ReplicationOpType.TX_BEGIN,
					TX_KEY, null, NODE_SCHEMA_EPOCH, CHECKSUM_PLACEHOLDER
			)), false);
			applier.apply(OpLogCodec.withChecksum(new ReplicationOp(
					DOMAIN, SHARD, LIVE_UPSERT_SEQ, ReplicationOpType.UPSERT,
					ROW_KEY, ROW_VALUE, NODE_SCHEMA_EPOCH, CHECKSUM_PLACEHOLDER
			)), false);
			assertTrue(applier.hasOpenTx(DOMAIN, SHARD));

			// Sealed pack path: reset watermark then OpLog-tail hydrate (same stream).
			state.resetApplied(DOMAIN, SHARD);
			state.advanceApplied(DOMAIN, SHARD, SEALED_WM);

			assertDoesNotThrow(() -> snapshots.hydrateOpLogFrom(
					DOMAIN, SHARD, SEALED_WM, applier, null));

			// Incomplete unit must not stick after hydrate fence.
			assertFalse(applier.hasOpenTx(DOMAIN, SHARD));
		}
	}
}
