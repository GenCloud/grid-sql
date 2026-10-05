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
package index.sql;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.genfork.grid.mem.GridScalableMap;
import org.genfork.grid.mem.stage.GridEntriesProcessor;
import org.genfork.grid.replication.ReplicationNodeState;
import org.genfork.grid.replication.apply.ReplicaApplier;
import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.log.OpLog;
import org.genfork.grid.replication.tx.TxEnvelopeCodec;
import org.genfork.grid.replication.tx.TxEnvelopeCoordinator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Multi-stream child-then-parent apply must flush both streams (GHA M G-single).
 * <p>
 * Evidence gha-fail-37281849691 M: b1 applied child half of tx122 early; after claim/hydrate
 * parent half arrived without COMPLETE flushEnvelope; map.getCommitted MISS key 15.
 * Hydrate must not discardAllApply on a shared TxEnvelopeCoordinator while envelopes wait.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
public class MultiStreamSplitApplyVisibilityIT {

	private static final String CHILD = "jepsen_child";
	private static final String PARENT = "jepsen_parent";
	private static final long TX_ID = 122L;
	private static final byte[] TX_KEY =
			Long.toHexString(TX_ID).getBytes(StandardCharsets.UTF_8);
	private static final byte[] CHILD_KEY = new byte[] {0x15, 0x00, 0x00, 0x00};
	private static final byte[] CHILD_VAL = "t22 t35".getBytes(StandardCharsets.UTF_8);
	private static final byte[] PARENT_KEY = new byte[] {0x64, 0x00, 0x00, 0x00};
	private static final byte[] PARENT_VAL = "p15".getBytes(StandardCharsets.UTF_8);

	@TempDir
	Path tempDir;

	@Test
	void childThenParent_flushInstallsChildKey() throws Exception {
		try (Harness h = Harness.open(tempDir.resolve("ok"))) {
			h.applyChildHalf(1L);
			assertNull(h.childMap.get(CHILD_KEY), "child staged until parent COMMIT");
			h.applyParentHalf(4L);
			assertNotNull(h.childMap.get(CHILD_KEY), "child must install after parent COMMIT");
			assertArrayEquals(CHILD_VAL, h.childMap.get(CHILD_KEY));
			assertNotNull(h.parentMap.get(PARENT_KEY));
			assertTrue(!h.gate.isMultiApplyOpen(TX_ID), "envelope must close");
		}
	}

	@Test
	void hydrateDiscardMustNotDropWaitingEnvelope_childVisibleAfterParent() throws Exception {
		try (Harness h = Harness.open(tempDir.resolve("hydrate"))) {
			h.applyChildHalf(1L);
			assertNull(h.childMap.get(CHILD_KEY));
			assertTrue(h.gate.isMultiApplyOpen(TX_ID), "child COMMIT leaves multi apply open");

			// SnapshotService.hydrate* must use discardLocalOpenTxStaging (not discardAllApply).
			h.childApplier.discardLocalOpenTxStaging();
			assertTrue(h.gate.isMultiApplyOpen(TX_ID),
					"hydrate local discard must keep waiting multi-stream envelope");

			h.applyParentHalf(4L);
			assertNotNull(h.childMap.get(CHILD_KEY),
					"after hydrate-style discard, late parent must still flush child staging");
			assertArrayEquals(CHILD_VAL, h.childMap.get(CHILD_KEY));
		}
	}

	private static final class Harness implements AutoCloseable {
		final TxEnvelopeCoordinator gate;
		final GridScalableMap childMap;
		final GridScalableMap parentMap;
		final ReplicaApplier childApplier;
		final ReplicaApplier parentApplier;
		final OpLog opLog;
		final byte[] membershipBytes;

		private Harness(
				TxEnvelopeCoordinator gate,
				GridScalableMap childMap,
				GridScalableMap parentMap,
				ReplicaApplier childApplier,
				ReplicaApplier parentApplier,
				OpLog opLog,
				byte[] membershipBytes
		) {
			this.gate = gate;
			this.childMap = childMap;
			this.parentMap = parentMap;
			this.childApplier = childApplier;
			this.parentApplier = parentApplier;
			this.opLog = opLog;
			this.membershipBytes = membershipBytes;
		}

		static Harness open(Path dir) throws Exception {
			final ReplicationNodeState state =
					new ReplicationNodeState("split-m", "split-m", "dc-b", 1L);
			final OpLog opLog = new OpLog(dir.resolve("oplog"), false);
			final GridScalableMap childMap = new GridScalableMap();
			final GridScalableMap parentMap = new GridScalableMap();
			final GridEntriesProcessor childProc =
					new GridEntriesProcessor(0, childMap, null, null);
			final GridEntriesProcessor parentProc =
					new GridEntriesProcessor(4, parentMap, null, null);
			final ReplicaApplier childApplier =
					new ReplicaApplier(state, opLog, shard -> childProc, null, true);
			final ReplicaApplier parentApplier =
					new ReplicaApplier(state, opLog, shard -> parentProc, null, true);
			final TxEnvelopeCoordinator gate = new TxEnvelopeCoordinator();
			childApplier.setEnvelopeCoordinator(gate);
			parentApplier.setEnvelopeCoordinator(gate);
			final Map<String, ReplicaApplier> byDomain = new ConcurrentHashMap<>();
			byDomain.put(CHILD, childApplier);
			byDomain.put(PARENT, parentApplier);
			childApplier.setApplierByDomain(byDomain::get);
			parentApplier.setApplierByDomain(byDomain::get);
			final byte[] membershipBytes = TxEnvelopeCodec.encode(List.of(
					new TxEnvelopeCodec.StreamRef(CHILD, 0),
					new TxEnvelopeCodec.StreamRef(PARENT, 4)));
			assertNotNull(membershipBytes);
			return new Harness(
					gate, childMap, parentMap, childApplier, parentApplier, opLog, membershipBytes);
		}

		void applyChildHalf(long seqBase) {
			childApplier.apply(OpLogCodec.withChecksum(new ReplicationOp(
					CHILD, 0, seqBase, ReplicationOpType.TX_BEGIN, TX_KEY, membershipBytes, 1L, 0L
			)), true);
			childApplier.apply(OpLogCodec.withChecksum(new ReplicationOp(
					CHILD, 0, seqBase + 1L, ReplicationOpType.UPSERT, CHILD_KEY, CHILD_VAL, 1L, 0L
			)), true);
			childApplier.apply(OpLogCodec.withChecksum(new ReplicationOp(
					CHILD, 0, seqBase + 2L, ReplicationOpType.TX_COMMIT, TX_KEY, null, 1L, 0L
			)), true);
		}

		void applyParentHalf(long seqBase) {
			parentApplier.apply(OpLogCodec.withChecksum(new ReplicationOp(
					PARENT, 4, seqBase, ReplicationOpType.TX_BEGIN, TX_KEY, membershipBytes, 1L, 0L
			)), false);
			parentApplier.apply(OpLogCodec.withChecksum(new ReplicationOp(
					PARENT, 4, seqBase + 1L, ReplicationOpType.UPSERT, PARENT_KEY, PARENT_VAL, 1L, 0L
			)), false);
			parentApplier.apply(OpLogCodec.withChecksum(new ReplicationOp(
					PARENT, 4, seqBase + 2L, ReplicationOpType.TX_COMMIT, TX_KEY, null, 1L, 0L
			)), false);
		}

		@Override
		public void close() throws Exception {
			opLog.close();
		}
	}
}