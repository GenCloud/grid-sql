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
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.SqlSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrent single-stream SQL TX list-append must not lose ack'd tokens.
 * <p>
 * Regression for Jepsen multidc-async-chaos Elle G2-item / nil reads: pipelined
 * {@code TX_BEGIN} on the same {@code table#shard} wiped {@code ReplicaApplier}
 * staging until {@link org.genfork.grid.replication.MutationRecorder} serialized
 * contiguous tip admits per stream via admit-only
 * {@link org.genfork.grid.replication.tx.StreamCommitSerializer} (join/digest outside lock).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
class ConcurrentSingleStreamTxAppendIT {
	private static final String TABLE = "ss_append";
	private static final int KEY_ID = 15;
	private static final int SHARD_COUNT = 8;
	private static final int THREADS = 4;
	private static final int TX_PER_THREAD = 32;
	private static final int MAX_PROPOSE_IN_FLIGHT = 16;
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
				ReplTestSupport.props("ss-append", "ss-append", "dc-a", port, tempDir, List.of());
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
		engine.execute("CREATE TABLE " + TABLE + " (id INT PRIMARY KEY, number VARCHAR, status VARCHAR)");
		engine.execute("INSERT INTO " + TABLE + " (id, number, status) VALUES ("
				+ KEY_ID + ", '', 'seed')");
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
	void concurrentSingleStreamConcatTx_keepsAllAckedTokens() throws Exception {
		final ExecutorService pool = Executors.newFixedThreadPool(THREADS);
		final CountDownLatch start = new CountDownLatch(1);
		final CountDownLatch done = new CountDownLatch(THREADS);
		final AtomicInteger failures = new AtomicInteger();
		final AtomicReference<Throwable> firstError = new AtomicReference<>();
		final List<String> acked = new CopyOnWriteArrayList<>();
		try {
			for (int t = 0; t < THREADS; t++) {
				final int threadIdx = t;
				pool.execute(() -> {
					try {
						start.await();
						for (int n = 0; n < TX_PER_THREAD; n++) {
							final String token = "t" + threadIdx + "n" + n;
							final SqlSession session = engine.newSession();
							engine.execute(session, "BEGIN");
							engine.execute(session,
									"UPDATE " + TABLE + " SET number = number || ' ' || '"
											+ token + "' WHERE id = " + KEY_ID);
							engine.execute(session, "COMMIT");
							acked.add(token);
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
					() -> "commit failures=" + failures.get() + " first=" + firstError.get());

			final SqlResult row = engine.execute(
					"SELECT number, status FROM " + TABLE + " WHERE id = " + KEY_ID);
			assertEquals(1, row.rows().size(), "row must exist after concurrent appends");
			final String number = String.valueOf(row.rows().get(0)[0]);
			final List<String> parts = number == null || number.isBlank()
					? List.of()
					: new ArrayList<>(Arrays.asList(number.trim().split("\\s+")));
			final Set<String> seen = new HashSet<>(parts);
			final List<String> missing = new ArrayList<>();
			for (String token : acked) {
				if (!seen.contains(token)) {
					missing.add(token);
				}
			}
			assertTrue(missing.isEmpty(),
					() -> "lost ack'd concat tokens: missing=" + missing
							+ " acked=" + acked.size()
							+ " body=" + number);
			assertEquals(acked.size(), parts.size(),
					() -> "token count mismatch body=" + number);
		} finally {
			pool.shutdownNow();
		}
	}
}
