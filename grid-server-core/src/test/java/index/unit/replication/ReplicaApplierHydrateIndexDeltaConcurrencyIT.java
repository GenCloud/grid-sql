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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parallel sealed-pack style hydrate on one {@link ReplicaApplier} (different shards / VTs).
 * <p>
 * Repro for shared hydrate ArrayList CME in {@code flushHydrateIndexDelta} (HELLO catch-up).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
@DisplayName("ReplicaApplierHydrateIndexDeltaConcurrency")
class ReplicaApplierHydrateIndexDeltaConcurrencyIT {
	private static final String DOMAIN = "cme.hydrate";
	private static final int SHARD_A = 0;
	private static final int SHARD_B = 1;
	private static final int OPS_PER_SHARD = 256;
	private static final int WORKERS = 2;
	private static final long JOIN_TIMEOUT_SEC = 30L;

	@TempDir
	Path tempDir;

	@Test
	@DisplayName("parallel_hydrate_flush_same_applier_different_shards_no_CME")
	void parallelHydrateFlushSameApplierDifferentShardsNoCme() throws Exception {
		final ReplicationNodeState state = new ReplicationNodeState("n1", "c", "dc-a", 1L);
		try (OpLog opLog = new OpLog(tempDir.resolve("oplog"), false)) {
			final GridEntriesProcessor p0 = new GridEntriesProcessor(SHARD_A, new GridScalableMap(), null, null);
			final GridEntriesProcessor p1 = new GridEntriesProcessor(SHARD_B, new GridScalableMap(), null, null);
			final ReplicaApplier applier = new ReplicaApplier(
					state, opLog, shard -> shard == SHARD_A ? p0 : p1, null, true);

			final CountDownLatch start = new CountDownLatch(WORKERS);
			final AtomicReference<Throwable> failure = new AtomicReference<>();
			final ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
			try {
				final List<Future<?>> futures = new ArrayList<>(WORKERS);
				futures.add(pool.submit(() -> runHydrateShard(
						applier, SHARD_A, 1L, start, failure)));
				futures.add(pool.submit(() -> runHydrateShard(
						applier, SHARD_B, 1L + OPS_PER_SHARD, start, failure)));
				for (Future<?> future : futures) {
					future.get(JOIN_TIMEOUT_SEC, TimeUnit.SECONDS);
				}
			} finally {
				pool.shutdownNow();
			}
			assertNull(failure.get(), () -> "hydrate flush failed: " + failure.get());
			final byte[] lastKey = ("k" + SHARD_A + "-" + (OPS_PER_SHARD - 1))
					.getBytes(StandardCharsets.UTF_8);
			assertTrue(p0.get(lastKey) != null);
		}
	}

	private static void runHydrateShard(
			ReplicaApplier applier,
			int shard,
			long seqBase,
			CountDownLatch start,
			AtomicReference<Throwable> failure
	) {
		try {
			start.countDown();
			assertTrue(start.await(JOIN_TIMEOUT_SEC, TimeUnit.SECONDS));
			applier.beginHydrateMapOnly();
			try {
				long seq = seqBase;
				for (int i = 0; i < OPS_PER_SHARD; i++) {
					final byte[] key = ("k" + shard + "-" + i).getBytes(StandardCharsets.UTF_8);
					final byte[] val = ("v" + shard + "-" + i).getBytes(StandardCharsets.UTF_8);
					final ReplicationOp upsert = OpLogCodec.withChecksum(new ReplicationOp(
							DOMAIN, shard, seq++, ReplicationOpType.UPSERT, key, val, 1L, 0L));
					applier.apply(upsert, true);
					if ((i % 2) == 0) {
						final byte[] delKey = ("d" + shard + "-" + i).getBytes(StandardCharsets.UTF_8);
						final ReplicationOp delete = OpLogCodec.withChecksum(new ReplicationOp(
								DOMAIN, shard, seq++, ReplicationOpType.DELETE,
								delKey, null, 1L, 0L));
						applier.apply(delete, true);
					}
					if ((i % 32) == 31) {
						assertDoesNotThrow(() -> applier.flushHydrateIndexDelta(shard));
					}
				}
				assertDoesNotThrow(() -> applier.flushHydrateIndexDelta(shard));
			} finally {
				applier.endHydrateMapOnly();
			}
		} catch (Throwable error) {
			failure.compareAndSet(null, error);
		}
	}
}
