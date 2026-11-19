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
package org.genfork.grid.replication.snapshot.sealed;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.genfork.grid.fs.GridFs;
import org.genfork.grid.mem.GridScalableMap;
import org.genfork.grid.mem.stage.GridEntriesProcessor;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlServerRuntime;
import org.genfork.grid.threading.ThreadService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrent getCommitted / DELETE-WHERE vs dump exclusive and HELLO unpack must never
 * surface {@link SealedGridMapReader#CLOSED_READER_MESSAGE_PREFIX} to callers.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
class SealedClosedRaceGetDeleteIT {
	private static final int SHARDS = 4;
	private static final int WORKER_THREADS = 6;
	private static final int ROUNDS = 10;
	private static final long AWAIT_SECONDS = 90L;
	private static final String HYDRATE_LAZY = "LAZY";
	private static final String TABLE = "player_variables_closed_race";
	private static final String CLOSED_PREFIX = SealedGridMapReader.CLOSED_READER_MESSAGE_PREFIX;
	private static final String DOMAIN = "closed_race_unpack";
	private static final int SHARD = 0;
	private static final byte[] KEY = "owner-1".getBytes(StandardCharsets.UTF_8);
	private static final byte[] VAL = "v1".getBytes(StandardCharsets.UTF_8);

	@TempDir(cleanup = CleanupMode.NEVER)
	Path tempDir;

	@BeforeAll
	static void bootThreads() {
		ThreadService.ensureRunning();
	}

	@AfterAll
	static void shutdownThreads() {
		ThreadService.shutdownNow();
	}

	@Test
	void concurrentDeleteWhereVsDumpNeverReportsClosedReader() throws Exception {
		final Path dataDir = tempDir.resolve("dump-race");
		try (SqlServerRuntime runtime = SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(SHARDS)
				.durability(true)
				.hydrateMode(HYDRATE_LAZY)
				.build()) {
			final SqlEngine engine = runtime.engine();
			engine.execute(
					"CREATE TABLE " + TABLE + " ("
							+ "owner_id INT PRIMARY KEY, "
							+ "var_name VARCHAR, "
							+ "var_value VARCHAR)");
			for (int i = 1; i <= 32; i++) {
				engine.execute(
						"INSERT INTO " + TABLE
								+ " (owner_id, var_name, var_value) VALUES ("
								+ i + ", 'k', 'v')");
			}
			final ReplicationCoordinator coord = runtime.replication();
			assertNotNull(coord);
			assertTrue(coord.dumpDomainSnapshot(TABLE) >= 1);

			final ExecutorService pool = Executors.newFixedThreadPool(WORKER_THREADS + 1);
			final CountDownLatch go = new CountDownLatch(1);
			final AtomicInteger closedFails = new AtomicInteger();
			final AtomicReference<String> firstClosed = new AtomicReference<>();
			final List<Future<?>> futures = new ArrayList<>(WORKER_THREADS + 1);
			try {
				futures.add(pool.submit(() -> {
					try {
						go.await();
						for (int i = 0; i < ROUNDS; i++) {
							coord.dumpDomainSnapshot(TABLE);
						}
					} catch (Exception ex) {
						noteClosed(ex, closedFails, firstClosed, "dump");
					}
				}));
				for (int t = 0; t < WORKER_THREADS; t++) {
					final int thread = t;
					futures.add(pool.submit(() -> {
						try {
							go.await();
							for (int i = 0; i < ROUNDS; i++) {
								final int owner = 1 + ((thread + i) % 32);
								if ((thread + i) % 2 == 0) {
									engine.execute(
											"SELECT var_value FROM " + TABLE
													+ " WHERE owner_id = " + owner);
								} else {
									engine.execute(
											"DELETE FROM " + TABLE
													+ " WHERE owner_id = " + owner);
								}
							}
						} catch (Exception ex) {
							noteClosed(ex, closedFails, firstClosed, "sql");
						}
					}));
				}
				go.countDown();
				for (Future<?> future : futures) {
					future.get(AWAIT_SECONDS, TimeUnit.SECONDS);
				}
			} finally {
				pool.shutdownNow();
				assertTrue(pool.awaitTermination(AWAIT_SECONDS, TimeUnit.SECONDS));
			}

			assertEquals(0, closedFails.get(),
					"must not surface sealed reader closed: " + firstClosed.get());
		}
	}

	@Test
	void concurrentGetCommittedVsUnpackNeverReportsClosedReader() throws Exception {
		final Path sealedRoot = tempDir.resolve("unpack-sealed");
		GridFs.createDirs(sealedRoot);
		final SealedGridMapService service = new SealedGridMapService(sealedRoot);
		final GridEntriesProcessor processor = new GridEntriesProcessor(
				SHARD, new GridScalableMap(), null, null);
		service.bindProcessor(DOMAIN, shard -> shard == SHARD ? processor : null);

		processor.installCommitted(KEY, VAL, false);
		assertTrue(service.dumpDomain(DOMAIN, null, null) > 0);
		final byte[] packed = SealedShardPack.pack(sealedRoot, DOMAIN, SHARD);
		assertTrue(packed.length > 0);
		service.openShardLazy(DOMAIN, SHARD);
		processor.evictCommitted(KEY);

		final ExecutorService pool = Executors.newFixedThreadPool(WORKER_THREADS + 1);
		final CountDownLatch go = new CountDownLatch(1);
		final AtomicInteger closedFails = new AtomicInteger();
		final AtomicReference<String> firstClosed = new AtomicReference<>();
		final List<Future<?>> futures = new ArrayList<>(WORKER_THREADS + 1);
		try {
			futures.add(pool.submit(() -> {
				try {
					go.await();
					for (int i = 0; i < ROUNDS; i++) {
						service.unpackShardArtifacts(DOMAIN, SHARD, packed);
						service.openShardLazy(DOMAIN, SHARD);
					}
				} catch (Exception ex) {
					noteClosed(ex, closedFails, firstClosed, "unpack");
				}
			}));
			for (int t = 0; t < WORKER_THREADS; t++) {
				futures.add(pool.submit(() -> {
					try {
						go.await();
						for (int i = 0; i < ROUNDS * 8; i++) {
							processor.getCommitted(KEY);
						}
					} catch (Exception ex) {
						noteClosed(ex, closedFails, firstClosed, "getCommitted");
					}
				}));
			}
			go.countDown();
			for (Future<?> future : futures) {
				future.get(AWAIT_SECONDS, TimeUnit.SECONDS);
			}
		} finally {
			pool.shutdownNow();
			assertTrue(pool.awaitTermination(AWAIT_SECONDS, TimeUnit.SECONDS));
			service.closeAll();
		}

		assertEquals(0, closedFails.get(),
				"must not surface sealed reader closed: " + firstClosed.get());
		assertFalse(firstClosed.get() != null && firstClosed.get().contains(CLOSED_PREFIX));
	}

	private static void noteClosed(
			Exception ex,
			AtomicInteger closedFails,
			AtomicReference<String> firstClosed,
			String where
	) {
		final String message = String.valueOf(ex.getMessage());
		if (message.contains(CLOSED_PREFIX)
				|| SealedClosedRaceUtil.isClosedReaderFailure(ex)) {
			closedFails.incrementAndGet();
			firstClosed.compareAndSet(null, where + ": " + ex);
		}
	}
}
