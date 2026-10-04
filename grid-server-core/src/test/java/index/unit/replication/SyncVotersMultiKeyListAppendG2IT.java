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
import org.genfork.grid.codec.duplex.DuplexCodecSupport;
import org.genfork.grid.codec.duplex.DuplexRepairMode;
import org.genfork.grid.replication.OrchidNotSyncedException;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.replication.crossdc.CrossDcMode;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.SqlSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Concurrent multi-key list-append+read txns must not observe nil for a prior :ok append.
 * <p>
 * Characterization for Jepsen G {@code multidc-sync-nochao} Elle {@code :G2-item-realtime}
 * (GHA 2026-10-04 gh.raw.log: process0 {@code [:r 2 nil]} after process1 {@code :ok}
 * {@code [:append 2 "t9"]}, sticky=a1, {@code no-proposer-count=0}, no-nemesis).
 * Product visibility is commit+dirty overlay (not MVCC snapshot) — a committed append on the
 * sticky writer must remain readable by later txns on that writer.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
class SyncVotersMultiKeyListAppendG2IT {
	private static final String TABLE = "g2_append";
	private static final String CLUSTER = "g2-sync-nochao";
	private static final int SHARD_COUNT = 8;
	private static final int KEY_LO = 2;
	private static final int KEY_HI = 16;
	private static final int THREADS = 8;
	private static final int TX_PER_THREAD = 80;
	private static final long SYNC_MS = 12_000L;
	private static final long JOIN_MS = 120_000L;

	@TempDir
	Path tempDir;

	private ReplicationCoordinator a1;
	private ReplicationCoordinator a2;
	private ReplicationCoordinator b1;
	private SqlEngine engine;

	@AfterEach
	void tearDown() {
		if (engine != null) {
			SqlBenchHelper.closeAllStores(engine);
			engine = null;
		}
		if (a1 != null) {
			a1.stop();
		}
		if (a2 != null) {
			a2.stop();
		}
		if (b1 != null) {
			b1.stop();
		}
		DuplexCodecSupport.configure(false, DuplexRepairMode.FAIL, false, false, 1L, true, true);
	}

	@Test
	void concurrentAppendReadTxn_neverReadsNilForPriorOkAppend() throws Exception {
		final int p1 = freePort();
		final int p2 = freePort();
		final int p3 = freePort();
		a1 = new ReplicationCoordinator(syncActiveProps("a1", "dc-a", p1, tempDir.resolve("a1"),
				List.of(ReplTestSupport.peer("a2", "dc-a", p2), ReplTestSupport.peer("b1", "dc-b", p3)),
				List.of("b1")));
		a2 = new ReplicationCoordinator(syncActiveProps("a2", "dc-a", p2, tempDir.resolve("a2"),
				List.of(ReplTestSupport.peer("a1", "dc-a", p1), ReplTestSupport.peer("b1", "dc-b", p3)),
				List.of("b1")));
		b1 = new ReplicationCoordinator(syncVoterProps("b1", "dc-b", p3, tempDir.resolve("b1"),
				List.of(ReplTestSupport.peer("a1", "dc-a", p1), ReplTestSupport.peer("a2", "dc-a", p2))));
		DuplexCodecSupport.configure(true, DuplexRepairMode.REBUILD_DATA_FROM_PARITY, true, true, 1L, true, true);
		a1.start();
		a2.start();
		b1.start();
		waitWriter(a1, a2, SYNC_MS);
		final ReplicationCoordinator writer = a1.isWriterEligible() ? a1 : a2;
		assertTrue(writer.isWriterEligible(), "sticky writer must be eligible");

		engine = SqlBenchHelper.createEngine(SHARD_COUNT, writer);
		engine.execute("CREATE TABLE " + TABLE
				+ " (id INT PRIMARY KEY, number VARCHAR, status VARCHAR)");

		final Map<Integer, Set<String>> committedByKey = new ConcurrentHashMap<>();
		for (int k = KEY_LO; k <= KEY_HI; k++) {
			committedByKey.put(k, ConcurrentHashMap.newKeySet());
		}
		final List<String> violations = new CopyOnWriteArrayList<>();
		final AtomicInteger orchidSkips = new AtomicInteger();
		final AtomicReference<Throwable> firstError = new AtomicReference<>();
		final ExecutorService pool = Executors.newFixedThreadPool(THREADS);
		final CountDownLatch start = new CountDownLatch(1);
		final CountDownLatch done = new CountDownLatch(THREADS);
		try {
			for (int t = 0; t < THREADS; t++) {
				final int threadIdx = t;
				pool.execute(() -> {
					try {
						start.await();
						int key = KEY_LO + (threadIdx % (KEY_HI - KEY_LO + 1));
						for (int n = 0; n < TX_PER_THREAD; n++) {
							final String token = "t" + threadIdx + "n" + n;
							final int appendKey = key;
							final int readKey = key >= KEY_HI ? KEY_LO : key + 1;
							key = readKey;
							try {
								runAppendReadTxn(appendKey, readKey, token, committedByKey, violations);
							} catch (OrchidNotSyncedException ex) {
								orchidSkips.incrementAndGet();
							} catch (RuntimeException ex) {
								if (isOrchid(ex)) {
									orchidSkips.incrementAndGet();
								} else {
									throw ex;
								}
							}
						}
					} catch (Throwable ex) {
						firstError.compareAndSet(null, ex);
					} finally {
						done.countDown();
					}
				});
			}
			start.countDown();
			assertTrue(done.await(JOIN_MS, TimeUnit.MILLISECONDS), "workers timed out");
			if (firstError.get() != null) {
				fail("worker error: " + firstError.get(), firstError.get());
			}
			assertTrue(violations.isEmpty(),
					() -> "G2-class nil read after :ok append (Jepsen G): " + violations
							+ " orchidSkips=" + orchidSkips.get());

			int totalCommitted = 0;
			for (Map.Entry<Integer, Set<String>> e : committedByKey.entrySet()) {
				totalCommitted += e.getValue().size();
				final Set<String> expected = e.getValue();
				if (expected.isEmpty()) {
					continue;
				}
				final SqlResult row = engine.execute(
						"SELECT number, status FROM " + TABLE + " WHERE id = " + e.getKey());
				assertEquals(1, row.rows().size(),
						() -> "missing row for key=" + e.getKey() + " tokens=" + expected);
				final String body = String.valueOf(row.rows().get(0)[0]);
				final List<String> parts = body == null || body.isBlank()
						? List.of()
						: new ArrayList<>(Arrays.asList(body.trim().split("\\s+")));
				final List<String> missing = new ArrayList<>();
				for (String token : expected) {
					if (!parts.contains(token)) {
						missing.add(token);
					}
				}
				assertTrue(missing.isEmpty(),
						() -> "lost tokens key=" + e.getKey() + " missing=" + missing
								+ " body=" + body);
			}
			assertTrue(totalCommitted > 0, "no successful appends");
		} finally {
			pool.shutdownNow();
		}
	}

	private void runAppendReadTxn(
			int appendKey,
			int readKey,
			String token,
			Map<Integer, Set<String>> committedByKey,
			List<String> violations
	) {
		final Set<String> mustSee = Set.copyOf(committedByKey.get(readKey));
		final SqlSession session = engine.newSession();
		engine.execute(session, "BEGIN");
		final SqlResult updated = engine.execute(session,
				"UPDATE " + TABLE + " SET number = number || ' ' || '" + token
						+ "', status = 'jepsen-append' WHERE id = " + appendKey);
		if (updated.rowsAffected() == 0L) {
			engine.execute(session,
					"INSERT INTO " + TABLE + " (id, number, status) VALUES ("
							+ appendKey + ", '" + token + "', 'jepsen-append')");
		}
		final SqlResult read = engine.execute(session,
				"SELECT number, status FROM " + TABLE + " WHERE id = " + readKey);
		engine.execute(session, "COMMIT");
		committedByKey.get(appendKey).add(token);

		final SqlResult selfRead = engine.execute(
				"SELECT number, status FROM " + TABLE + " WHERE id = " + appendKey);
		if (selfRead.rows().isEmpty()) {
			violations.add("self-read rows=0 key=" + appendKey + " token=" + token);
		} else {
			final String selfBody = selfRead.rows().get(0)[0] == null
					? "" : String.valueOf(selfRead.rows().get(0)[0]);
			if (!selfBody.contains(token)) {
				violations.add("self-read miss key=" + appendKey + " token=" + token
						+ " body=" + selfBody);
			}
		}

		if (!mustSee.isEmpty()) {
			if (read.rows().isEmpty()) {
				violations.add("key=" + readKey + " rows=0 after priorOk=" + mustSee
						+ " appendKey=" + appendKey + " token=" + token);
				return;
			}
			final Object numberObj = read.rows().get(0)[0];
			final Object statusObj = read.rows().get(0).length > 1 ? read.rows().get(0)[1] : null;
			final String number = numberObj == null ? "" : String.valueOf(numberObj);
			if (number.isEmpty() && (statusObj == null || "seed".equals(String.valueOf(statusObj)))) {
				violations.add("key=" + readKey + " elle-nil body empty/seed after priorOk=" + mustSee
						+ " appendKey=" + appendKey + " token=" + token);
				return;
			}
			final List<String> missing = new ArrayList<>();
			for (String t : mustSee) {
				if (!number.contains(t)) {
					missing.add(t);
				}
			}
			if (!missing.isEmpty()) {
				violations.add("key=" + readKey + " body=" + number + " missing=" + missing
						+ " appendKey=" + appendKey + " token=" + token);
			}
		}
	}

	private static GridConfigurationProperties syncActiveProps(
			String nodeId,
			String dc,
			int port,
			Path dataDir,
			List<GridConfigurationProperties.ReplicationPeerProps> peers,
			List<String> voters
	) {
		final GridConfigurationProperties props =
				ReplTestSupport.safetyProps(nodeId, CLUSTER, dc, port, dataDir, peers);
		props.getReplication().getCrossDc().setMode(CrossDcMode.SYNC_VOTERS_ACROSS_DC.name());
		props.getReplication().getCrossDc().setVoters(voters);
		props.getReplication().getCrossDc().setPhaseCoupling(false);
		props.getReplication().getCrossDc().setWriteAdmission(true);
		props.getReplication().getOrchid().setMaxProposeInFlight(16);
		props.getReplication().getHa().setMaxStaleLag(0);
		props.getReplication().getSwarm().setEnabled(false);
		props.getReplication().getRepair().setHomologousEnabled(true);
		return props;
	}

	private static GridConfigurationProperties syncVoterProps(
			String nodeId,
			String dc,
			int port,
			Path dataDir,
			List<GridConfigurationProperties.ReplicationPeerProps> peers
	) {
		final GridConfigurationProperties props =
				ReplTestSupport.safetyProps(nodeId, CLUSTER, dc, port, dataDir, peers);
		props.getReplication().getCrossDc().setMode(CrossDcMode.ASYNC_SHIP.name());
		props.getReplication().getCrossDc().setPhaseCoupling(false);
		props.getReplication().getCrossDc().setWriteAdmission(true);
		props.getReplication().getOrchid().setMaxProposeInFlight(16);
		props.getReplication().getHa().setMaxStaleLag(0);
		props.getReplication().getSwarm().setEnabled(false);
		return props;
	}

	private static void waitWriter(ReplicationCoordinator a, ReplicationCoordinator b, long timeoutMs)
			throws Exception {
		final long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			if ((a.isWriterEligible() && a.getOrchidNode().isPhaseRankedProposer())
					|| (b.isWriterEligible() && b.getOrchidNode().isPhaseRankedProposer())) {
				return;
			}
			Thread.sleep(20L);
		}
		fail("no writerEligible proposer a.synced=" + a.getOrchidNode().isSynced()
				+ " b.synced=" + b.getOrchidNode().isSynced()
				+ " a.elig=" + a.isWriterEligible()
				+ " b.elig=" + b.isWriterEligible()
				+ " a.awaitTip=" + a.getOrchidNode().awaitsPeerTipAdvertisement()
				+ " b.awaitTip=" + b.getOrchidNode().awaitsPeerTipAdvertisement());
	}

	private static boolean isOrchid(Throwable ex) {
		Throwable cur = ex;
		while (cur != null) {
			if (cur instanceof OrchidNotSyncedException) {
				return true;
			}
			cur = cur.getCause();
		}
		return false;
	}

	private static int freePort() throws Exception {
		try (ServerSocket s = new ServerSocket(0)) {
			return s.getLocalPort();
		}
	}
}