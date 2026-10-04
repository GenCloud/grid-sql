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
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Linearizability smoke: sequential commits appear in same order on both nodes after ship.
 * <p>
 * Tip may advance past the last user UPSERT when a deferred schema {@link ReplicationOpType#BARRIER}
 * commits on the same tip chain — assert {@code tip >= lastUserUpsertSeq}, not equality.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public class LinearizabilityIT {

	private static final String DOMAIN = "chaos.Lin";
	private static final String NODE_A = "lin-a";
	private static final String NODE_B = "lin-b";
	private static final String CLUSTER_ID = "lin";
	private static final String DC = "dc-a";
	private static final int SHARD = 0;
	private static final int WRITE_COUNT = 5;
	private static final int VALUE_BASE = 10;
	private static final long SCHEMA_EPOCH = 1L;
	private static final long SYNC_TIMEOUT_MS = 12_000L;
	private static final long APPLY_TIMEOUT_MS = 10_000L;
	private static final long COMMIT_TIMEOUT_SEC = 8L;
	private static final long POLL_MS = 50L;
	private static final long GC_PAUSE_MS = 50L;
	private static final int OPLOG_READ_LIMIT = 100;

	@TempDir
	Path tempDir;

	@Test
	void sequentialWritesPreserveOrderOnReplica() throws Exception {
		final int portA = freePort();
		final int portB = freePort();
		final GridConfigurationProperties propsA = ReplTestSupport.safetyProps(
				NODE_A, CLUSTER_ID, DC, portA, tempDir.resolve("a"),
				List.of(ReplTestSupport.peer(NODE_B, DC, portB))
		);
		final GridConfigurationProperties propsB = ReplTestSupport.safetyProps(
				NODE_B, CLUSTER_ID, DC, portB, tempDir.resolve("b"),
				List.of(ReplTestSupport.peer(NODE_A, DC, portA))
		);

		final GridEntriesProcessor procA = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
		final GridEntriesProcessor procB = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
		final ReplicationCoordinator a = new ReplicationCoordinator(propsA);
		final ReplicationCoordinator b = new ReplicationCoordinator(propsB);
		try {
			a.registerDomain(DOMAIN, ReplTestSupport.singleShard(procA), true);
			b.registerDomain(DOMAIN, ReplTestSupport.singleShard(procB), true);
			a.start();
			b.start();

			final long syncDeadline = System.currentTimeMillis() + SYNC_TIMEOUT_MS;
			while (System.currentTimeMillis() < syncDeadline
					&& (!a.getOrchidNode().isSynced() || !b.getOrchidNode().isSynced()
					|| !a.getOrchidNode().isPhaseRankedProposer())) {
				Thread.sleep(POLL_MS);
			}
			assertTrue(a.getOrchidNode().isPhaseRankedProposer());

			final List<ReplicationOp> committed = new ArrayList<>(WRITE_COUNT);
			long lastUserUpsertSeq = 0L;
			for (int i = 0; i < WRITE_COUNT; i++) {
				final byte[] key = new byte[]{(byte) i};
				final byte[] value = new byte[]{(byte) (VALUE_BASE + i)};
				final ReplicationOp raw = OpLogCodec.withChecksum(new ReplicationOp(
						DOMAIN, SHARD, i + 1L, ReplicationOpType.UPSERT, key, value, SCHEMA_EPOCH, 0L
				));
				final long seq = a.getOrchidNode().appendAndWaitCommit(raw).get(COMMIT_TIMEOUT_SEC, TimeUnit.SECONDS);
				lastUserUpsertSeq = seq;
				final ReplicationOp op = OpLogCodec.withChecksum(new ReplicationOp(
						raw.domainType(), raw.shard(), seq, raw.type(), raw.key(), raw.value(), raw.schemaEpoch(), 0L
				));
				a.getOpLog().append(op);
				a.getOrchidNode().confirmPersisted(seq);
				procA.installCommitted(key, value, false);
				committed.add(op);
			}

			// Honest ship: orchid broadcastCommit → peer fireApply (no dual pushSegment).
			final byte[] lastKey = new byte[]{(byte) (WRITE_COUNT - 1)};
			final long applyDeadline = System.currentTimeMillis() + APPLY_TIMEOUT_MS;
			while (System.currentTimeMillis() < applyDeadline && procB.get(lastKey) == null) {
				Thread.sleep(POLL_MS);
			}
			for (int i = 0; i < WRITE_COUNT; i++) {
				assertArrayEquals(new byte[]{(byte) (VALUE_BASE + i)}, procB.get(new byte[]{(byte) i}));
			}

			final List<ReplicationOp> logOps = a.getOpLog().readFrom(DOMAIN, SHARD, 1L, OPLOG_READ_LIMIT);
			int upsertCount = 0;
			for (ReplicationOp op : logOps) {
				if (op.type() == ReplicationOpType.UPSERT) {
					upsertCount++;
				}
			}
			assertEquals(WRITE_COUNT, upsertCount, "OpLog must retain all user UPSERTs");
			assertEquals(WRITE_COUNT, committed.size());
			assertTrue(a.getOrchidNode().getLastCommittedSeq() >= lastUserUpsertSeq,
					"tip must be at least last user UPSERT (schema BARRIER may advance tip)");
		} finally {
			a.stop();
			b.stop();
			System.gc();
			Thread.sleep(GC_PAUSE_MS);
		}
	}

	private static int freePort() throws Exception {
		try (ServerSocket s = new ServerSocket(0)) {
			return s.getLocalPort();
		}
	}
}
