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

import index.sql.SqlBenchHelper;
import org.genfork.grid.context.config.GridConfigurationProperties;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlSession;
import org.genfork.grid.store.TableStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrent multi-shard TX units must keep per-stream OpLog monotonic
 * (WRITE_BATCH-style path / re-admit cancelJoin race).
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
class ConcurrentMultiShardTxOpLogOrderIT {
	private static final String DOMAIN = "batch_ooo";
	private static final String TABLE = "batch_ooo";
	private static final int SHARD_COUNT = 4;
	private static final int THREADS = 8;
	private static final int TX_PER_THREAD = 24;
	private static final int KEYS_PER_TX = 4;
	private static final int MAX_PROPOSE_IN_FLIGHT = 8;
	private static final long SYNC_DEADLINE_MS = 5_000L;

	@TempDir
	Path tempDir;

	private ReplicationCoordinator coordinator;
	private SqlEngine engine;

	@BeforeEach
	void setUp() throws Exception {
		int port;
		try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
			port = s.getLocalPort();
		}
		final GridConfigurationProperties props =
				ReplTestSupport.props("batch-ooo", "batch-ooo", "dc-a", port, tempDir, List.of());
		props.getReplication().getOpLog().setFsync(false);
		props.getReplication().getOrchid().setMaxProposeInFlight(MAX_PROPOSE_IN_FLIGHT);
		coordinator = new ReplicationCoordinator(props);
		coordinator.start();
		final long deadline = System.currentTimeMillis() + SYNC_DEADLINE_MS;
		while (System.currentTimeMillis() < deadline && !coordinator.getOrchidNode().isSynced()) {
			Thread.sleep(10L);
		}
		assertTrue(coordinator.getOrchidNode().isSynced());
		engine = SqlBenchHelper.createEngine(SHARD_COUNT, coordinator);
		engine.execute("CREATE TABLE " + TABLE + " (id INT PRIMARY KEY, v VARCHAR)");
	}

	@AfterEach
	void tearDown() {
		if (engine != null) {
			SqlBenchHelper.closeAllStores(engine);
		}
		if (coordinator != null) {
			coordinator.stop();
		}
	}

	@Test
	void concurrentMultiShardSqlTx_opLogMonotonic() throws Exception {
		final TableStore store = engine.catalog().getStore(TABLE);
		final List<Integer>[] keysByShard = partitionKeys(store);
		for (int s = 0; s < SHARD_COUNT; s++) {
			for (Integer id : keysByShard[s]) {
				engine.execute("INSERT INTO " + TABLE + " (id, v) VALUES (" + id + ", 'seed')");
			}
		}
		final ExecutorService pool = Executors.newFixedThreadPool(THREADS);
		final CountDownLatch start = new CountDownLatch(1);
		final CountDownLatch done = new CountDownLatch(THREADS);
		final AtomicInteger failures = new AtomicInteger();
		final AtomicReference<Throwable> firstError = new AtomicReference<>();
		try {
			for (int t = 0; t < THREADS; t++) {
				final int threadIdx = t;
				pool.execute(() -> {
					try {
						start.await();
						for (int n = 0; n < TX_PER_THREAD; n++) {
							final SqlSession session = engine.newSession();
							engine.execute(session, "BEGIN");
							for (int k = 0; k < KEYS_PER_TX; k++) {
								final int shard = (threadIdx + k) % SHARD_COUNT;
								final List<Integer> shardKeys = keysByShard[shard];
								final int id = shardKeys.get(threadIdx % shardKeys.size());
								final String v = "t" + threadIdx + "-n" + n + "-k" + k;
								engine.execute(session,
										"UPDATE " + TABLE + " SET v = '" + v + "' WHERE id = " + id);
							}
							engine.execute(session, "COMMIT");
						}
					} catch (Throwable ex) {
						failures.incrementAndGet();
						firstError.compareAndSet(null, ex);
					} finally {
						done.countDown();
					}
				});
			}
			start.countDown();
			assertTrue(done.await(120, TimeUnit.SECONDS), "workers timed out");
			assertEquals(0, failures.get(),
					() -> "OOO/commit failures=" + failures.get() + " first=" + firstError.get());
			assertMonotonicAllShards(TABLE);
		} finally {
			pool.shutdownNow();
		}
	}


	@SuppressWarnings("unchecked")
	private static List<Integer>[] partitionKeys(TableStore store) {
		final List<Integer>[] byShard = new List[SHARD_COUNT];
		for (int s = 0; s < SHARD_COUNT; s++) {
			byShard[s] = new ArrayList<>();
		}
		for (int id = 1; id < 2_000 && !allShardsHaveKeys(byShard); id++) {
			final byte[] key = store.keyBytesForPk(id);
			final int shard = store.shardOf(key);
			if (shard >= 0 && shard < SHARD_COUNT && byShard[shard].size() < 64) {
				byShard[shard].add(id);
			}
		}
		for (int s = 0; s < SHARD_COUNT; s++) {
			assertTrue(!byShard[s].isEmpty(), "no keys for shard " + s);
		}
		return byShard;
	}

	private static boolean allShardsHaveKeys(List<Integer>[] byShard) {
		for (List<Integer> keys : byShard) {
			if (keys.size() < 8) {
				return false;
			}
		}
		return true;
	}

	private void assertMonotonicAllShards(String domain) {
		for (int shard = 0; shard < SHARD_COUNT; shard++) {
			final long last = coordinator.getOpLog().lastSeq(domain, shard);
			if (last <= 0L) {
				continue;
			}
			final List<ReplicationOp> ops = coordinator.getOpLog().readFrom(domain, shard, 1L, (int) last + 8);
			long prev = 0L;
			for (ReplicationOp op : ops) {
				assertTrue(op.opSeq() > prev,
						"domain=" + domain + " shard=" + shard + " prev=" + prev + " got=" + op.opSeq());
				prev = op.opSeq();
			}
		}
	}
}