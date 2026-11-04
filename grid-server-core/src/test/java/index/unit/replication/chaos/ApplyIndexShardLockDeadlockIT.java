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

import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.replication.apply.ReplicaApplier;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlServerRuntime;
import org.genfork.grid.store.TableStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AB-BA: {@code ReplicaApplier.shardLocks} × sync {@code indexNow} × STRICT displace
 * {@code ensureShardHydrated}/{@code discardOpenTxStaging}. Must complete under timeout
 * (lab n1 hung on {@code markCatalogBootstrapComplete} without the lock invariant).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
@DisplayName("ApplyIndexShardLockDeadlock")
class ApplyIndexShardLockDeadlockIT {

	private static final String TABLE = "apply_idx_deadlock_t";
	private static final String UNIQUE_INDEX = "apply_idx_deadlock_t_name_u";
	private static final String NAME_COL = "name_key";
	private static final String SHARED_NAME = "shared";
	private static final int SHARDS = 4;
	private static final int WORKERS = 8;
	private static final int UPSERTS_PER_WORKER = 24;
	private static final int DISCARD_ROUNDS = 64;
	private static final String HYDRATE_LAZY = "LAZY";
	private static final Duration HANG_BUDGET = Duration.ofSeconds(45L);

	@TempDir
	Path tempDir;

	@Test
	@DisplayName("strictDisplace_concurrentApply_discardHydrate_noHang")
	void strictDisplace_concurrentApply_discardHydrate_noHang() {
		final Path dataDir = tempDir.resolve("apply-idx-deadlock");
		assertTimeoutPreemptively(HANG_BUDGET, () -> runConcurrentStrictDisplace(dataDir));
	}

	private void runConcurrentStrictDisplace(Path dataDir) throws Exception {
		try (SqlServerRuntime runtime = SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(SHARDS)
				.durability(true)
				.hydrateMode(HYDRATE_LAZY)
				.build()) {
			final SqlEngine engine = runtime.engine();
			engine.execute(
					"CREATE TABLE " + TABLE + " ("
							+ "id INT PRIMARY KEY, "
							+ NAME_COL + " VARCHAR NOT NULL)");
			engine.execute(
					"CREATE UNIQUE INDEX " + UNIQUE_INDEX
							+ " ON " + TABLE + " (" + NAME_COL + ")");
			engine.execute(
					"INSERT INTO " + TABLE + " (id, " + NAME_COL + ") VALUES (1, '" + SHARED_NAME + "')");

			final ReplicationCoordinator coord = runtime.replication();
			assertTrue(coord != null && coord.isEnabled());
			final ReplicaApplier applier = coord.applier(TABLE);
			assertTrue(applier != null);

			final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
			try {
				final List<Callable<Void>> tasks = new ArrayList<>(WORKERS + 1);
				for (int w = 0; w < WORKERS; w++) {
					final int worker = w;
					tasks.add(() -> {
						for (int i = 0; i < UPSERTS_PER_WORKER; i++) {
							final int pk = 1_000 + worker * UPSERTS_PER_WORKER + i;
							engine.execute(
									"INSERT INTO " + TABLE + " (id, " + NAME_COL + ") VALUES ("
											+ pk + ", '" + SHARED_NAME + "') "
											+ "ON CONFLICT (" + NAME_COL + ") DO UPDATE SET id = " + pk);
						}
						return null;
					});
				}
				tasks.add(() -> {
					for (int r = 0; r < DISCARD_ROUNDS; r++) {
						for (int shard = 0; shard < SHARDS; shard++) {
							coord.ensureShardHydrated(TABLE, shard);
							applier.discardOpenTxStaging(TABLE, shard);
						}
					}
					return null;
				});
				final List<Future<Void>> futures = pool.invokeAll(tasks, HANG_BUDGET.toSeconds(), TimeUnit.SECONDS);
				for (Future<Void> future : futures) {
					assertDoesNotThrow(() -> future.get(1L, TimeUnit.SECONDS));
				}
			} finally {
				pool.shutdownNow();
			}

			final TableStore store = engine.catalog().getStore(TABLE);
			assertTrue(store != null);
			final Object countObj = engine.execute(
					"SELECT COUNT(*) FROM " + TABLE + " WHERE " + NAME_COL + " = '" + SHARED_NAME + "'")
					.rows().getFirst()[0];
			final int count = countObj instanceof Number number
					? number.intValue()
					: Integer.parseInt(String.valueOf(countObj));
			assertEquals(1, count, "STRICT UNIQUE must leave a single live row");
		}
	}
}
