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
import org.genfork.grid.catalog.SqlType;
import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.context.config.GridConfigurationProperties;
import org.genfork.grid.mem.stage.GridEntriesProcessor;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.store.TableStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Contract for GHA 37356096713 cell D: concurrent store open must be single-flight.
 * <p>
 * Legacy put-bind allowed applier {@code registerDomain} last-wins to diverge from catalog
 * bind - install-then-MISS under kill-dc-a claim. Product fix is
 * {@link TableCatalog#computeStoreIfAbsent}; this IT asserts that fence without injecting
 * latches into {@code SqlTableResolver}.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
public class ConcurrentOpenStoreApplierCatalogDesyncIT {

	private static final String TABLE = "jepsen_register_dual";
	private static final String CLUSTER = "dualstore";
	private static final String NODE = "dualstore-a";
	private static final int KEY_ID = 10;
	private static final long RACE_TIMEOUT_MS = 15_000L;
	private static final int SHARDS = 4;
	private static final int HAMMER_THREADS = 8;

	@TempDir
	Path tempDir;

	@Test
	@Timeout(30)
	void computeStoreIfAbsentRunsFactoryOnceUnderConcurrentCallers() throws Exception {
		final TableCatalog catalog = new TableCatalog(tempDir.resolve("cat-flight"));
		final Object first = new Object();
		final Object second = new Object();
		final AtomicInteger factories = new AtomicInteger();
		final CountDownLatch entered = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		final AtomicReference<Throwable> error = new AtomicReference<>();

		final Thread opener = new Thread(() -> {
			try {
				catalog.computeStoreIfAbsent(TABLE, () -> {
					factories.incrementAndGet();
					entered.countDown();
					try {
						if (!release.await(RACE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
							throw new IllegalStateException("release timeout");
						}
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
						throw new IllegalStateException("release interrupted", ie);
					}
					return first;
				});
			} catch (Throwable t) {
				error.set(t);
			}
		}, "store-flight-opener");
		final Thread waiter = new Thread(() -> {
			try {
				if (!entered.await(RACE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
					fail("factory enter timeout");
				}
				final Object got = catalog.computeStoreIfAbsent(TABLE, () -> {
					factories.incrementAndGet();
					return second;
				});
				assertSame(first, got, "waiter must observe in-flight store");
			} catch (Throwable t) {
				error.set(t);
			}
		}, "store-flight-waiter");

		opener.start();
		waiter.start();
		if (!entered.await(RACE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
			fail("opener never entered factory");
		}
		release.countDown();
		opener.join(RACE_TIMEOUT_MS);
		waiter.join(RACE_TIMEOUT_MS);
		if (error.get() != null) {
			fail("single-flight race failed: " + error.get());
		}
		assertEquals(1, factories.get(), "exactly one factory under concurrent computeStoreIfAbsent");
		assertSame(first, catalog.getStore(TABLE));
	}

	@Test
	@Timeout(60)
	void concurrentEnsureStoreKeepsSqlVisibleApplierInstall() throws Exception {
		final int port = freePort();
		final GridConfigurationProperties props = ReplTestSupport.props(
				NODE, CLUSTER, "dc-a", port, tempDir.resolve("data"), List.of());
		final ReplicationCoordinator coord = new ReplicationCoordinator(props);
		final TableCatalog catalog = new TableCatalog(tempDir.resolve("cat"));
		final SqlEngine engine = new SqlEngine(catalog, coord, SHARDS);

		final TableSchema schema = TableSchema.builder(TABLE)
				.primaryKey("id", SqlType.INT)
				.column("number", SqlType.VARCHAR, true)
				.column("status", SqlType.VARCHAR, true)
				.build();

		coord.start();
		try {
			waitSoloSynced(coord, RACE_TIMEOUT_MS);
			catalog.createTable(schema);
			assertNotNull(catalog.getStore(TABLE), "createTable must bind store");

			final CountDownLatch start = new CountDownLatch(1);
			final CountDownLatch done = new CountDownLatch(HAMMER_THREADS);
			final AtomicReference<Throwable> hammerError = new AtomicReference<>();
			for (int i = 0; i < HAMMER_THREADS; i++) {
				final Thread t = new Thread(() -> {
					try {
						start.await(RACE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
						engine.execute("CREATE TABLE IF NOT EXISTS " + TABLE
								+ " (id INT PRIMARY KEY, number VARCHAR, status VARCHAR)");
					} catch (Throwable ex) {
						hammerError.compareAndSet(null, ex);
					} finally {
						done.countDown();
					}
				}, "ensure-hammer-" + i);
				t.start();
			}
			start.countDown();
			if (!done.await(RACE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
				fail("ensureStore hammer timeout");
			}
			if (hammerError.get() != null) {
				fail("ensureStore hammer failed: " + hammerError.get());
			}

			final TableStore store = catalog.getStore(TABLE);
			assertNotNull(store);
			final byte[] keyBytes = store.keyBytesForPk(KEY_ID);
			final byte[] valueBytes = store.encodeUpsert(new Object[]{
					KEY_ID, "t17 t8", "jepsen-append"
			}, false).valueBytes();
			final GridEntriesProcessor proc = store.processorForTest(keyBytes);
			proc.installCommitted(keyBytes, valueBytes, false);

			final SqlResult row = engine.execute(
					"SELECT number FROM " + TABLE + " WHERE id = " + KEY_ID);
			assertTrue(!row.rows().isEmpty() && row.rows().get(0)[0] != null,
					() -> "GHA-37356096713: installed pk=" + KEY_ID + " must stay SQL-visible");
		} finally {
			try {
				coord.stop();
			} catch (Exception ignored) {
			}
		}
	}

	private static void waitSoloSynced(ReplicationCoordinator coord, long timeoutMs) {
		final long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			if (coord.getOrchidNode().isSynced()
					&& !coord.getOrchidNode().awaitsPeerTipAdvertisement()
					&& coord.isWriterEligible()) {
				return;
			}
			try {
				Thread.sleep(20L);
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
				fail("interrupted waiting solo sync");
			}
		}
		fail("solo not ready for admit tip=" + coord.getOrchidNode().getLastCommittedSeq()
				+ " awaitsTip=" + coord.getOrchidNode().awaitsPeerTipAdvertisement()
				+ " el=" + coord.isWriterEligible());
	}

	private static int freePort() throws Exception {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}
}