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
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.Map;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Deterministic Jepsen G history replay for Elle G2-item-realtime smoking guns.
 * <p>
 * From lab excerpt 2026-10-04-G-elle-g2-excerpt.txt (jamoa-orchid-append-nochao):
 * key 16 p0 ok append t110 then later same-process {@code [:r 16 nil]};
 * key 2 p1 ok append t9 then later p0 read sees nil in multi-mop txn.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
class SyncVotersG2HistoryReplayIT {
	private static final String TABLE = "g2_hist";
	private static final String CLUSTER = "g2-hist-replay";
	private static final int SHARD_COUNT = 8;
	private static final long SYNC_MS = 12_000L;

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
		DuplexCodecSupport.configure(false, DuplexRepairMode.FAIL, false, false, 1, true, true);
	}

	@Test
	void scenarioA_delayedSameProcessReadAfterOwnAppend_key16() throws Exception {
		bootCluster();
		upsertAppend(16, "t110");
		assertNotElleNil(16, "t110", "immediate self-read after append 16");
		assertNotElleNil(16, "t110", "delayed same-process read of key 16 (CI idx 305->341)");
	}

	/**
	 * Calm G 2026-10-05-gates-G-r2: p0 {@code :ok [:append 6 "t202"]} then next txn
	 * {@code [:append 5 "t203"][:r 6 nil]} — lost own append under multi-mop follow-up.
	 */
	@Test
	void scenarioE_nextTxnOtherKeyAppendThenRereadOwnKey_mustSeeToken() throws Exception {
		bootCluster();
		upsertAppend(6, "t202");
		assertNotElleNil(6, "t202", "self-read after append 6");

		final SqlSession session = engine.newSession();
		engine.execute(session, "BEGIN");
		upsertAppendInTx(session, 5, "t203");
		final SqlResult read6 = engine.execute(session,
				"SELECT number, status FROM " + TABLE + " WHERE id = 6");
		engine.execute(session, "COMMIT");

		assertFalse(isElleNil(read6), () -> "G-r2 class nil on key 6 in next multi-mop txn; rows="
				+ read6.rows().size() + " body=" + bodyOf(read6));
		assertTrue(bodyOf(read6).contains("t202"),
				() -> "G-r2 class missing t202 on key 6 body=" + bodyOf(read6));
		assertNotElleNil(6, "t202", "post-commit reread key 6 after other-key txn");
	}

	@Test
	void scenarioB_crossKeyReadSeesPriorOkAppend_key2() throws Exception {
		bootCluster();
		upsertAppend(2, "t9");
		assertNotElleNil(2, "t9", "self-read after append 2");

		final SqlSession session = engine.newSession();
		engine.execute(session, "BEGIN");
		upsertAppendInTx(session, 16, "t110");
		final SqlResult read = engine.execute(session,
				"SELECT number, status FROM " + TABLE + " WHERE id = 2");
		engine.execute(session, "COMMIT");

		assertFalse(isElleNil(read), () -> "CI-class nil on key 2 after prior ok t9; rows="
				+ read.rows().size() + " body=" + bodyOf(read));
		final String body = bodyOf(read);
		assertTrue(body.contains("t9"), () -> "key 2 missing t9 after prior ok; body=" + body);
	}

	/**
	 * Concurrent append-gen mix + barrier + delayed reread of every ok-appended key.
	 * Catches lost map visibility / overwrite that A/B alone miss.
	 */
	@Test
	void scenarioC_concurrentAppendGen_delayedRereadAllOkKeys() throws Exception {
		bootCluster();
		final int keyLo = 2;
		final int keyHi = 16;
		final int threads = 8;
		final int txPerThread = 100;
		final Map<Integer, Set<String>> committed = new ConcurrentHashMap<>();
		for (int k = keyLo; k <= keyHi; k++) {
			committed.put(k, ConcurrentHashMap.newKeySet());
		}
		final List<String> violations = new CopyOnWriteArrayList<>();
		final AtomicInteger orchidSkips = new AtomicInteger();
		final AtomicReference<Throwable> firstErr = new AtomicReference<>();
		final ExecutorService pool = Executors.newFixedThreadPool(threads);
		final CountDownLatch start = new CountDownLatch(1);
		final CountDownLatch done = new CountDownLatch(threads);
		try {
			for (int ti = 0; ti < threads; ti++) {
				final int threadIdx = ti;
				pool.execute(() -> {
					try {
						start.await();
						int key = keyLo + (threadIdx % (keyHi - keyLo + 1));
						for (int n = 0; n < txPerThread; n++) {
							final String token = "t" + threadIdx + "n" + n;
							final int appendKey = key;
							final int readKey = key >= keyHi ? keyLo : key + 1;
							key = readKey;
							try {
								final SqlSession session = engine.newSession();
								engine.execute(session, "BEGIN");
								upsertAppendInTx(session, appendKey, token);
								engine.execute(session,
										"SELECT number, status FROM " + TABLE + " WHERE id = " + readKey);
								engine.execute(session, "COMMIT");
								committed.get(appendKey).add(token);
								final SqlResult self = engine.execute(
										"SELECT number, status FROM " + TABLE + " WHERE id = " + appendKey);
								if (isElleNil(self) || !bodyOf(self).contains(token)) {
									violations.add("immediate miss key=" + appendKey + " token=" + token
											+ " body=" + bodyOf(self));
								}
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
						firstErr.compareAndSet(null, ex);
					} finally {
						done.countDown();
					}
				});
			}
			start.countDown();
			assertTrue(done.await(180_000L, TimeUnit.MILLISECONDS), "workers timed out");
			if (firstErr.get() != null) {
				fail("worker error: " + firstErr.get(), firstErr.get());
			}
			for (Map.Entry<Integer, Set<String>> e : committed.entrySet()) {
				if (e.getValue().isEmpty()) {
					continue;
				}
				final SqlResult row = engine.execute(
						"SELECT number, status FROM " + TABLE + " WHERE id = " + e.getKey());
				if (isElleNil(row)) {
					violations.add("delayed elle-nil key=" + e.getKey()
							+ " priorOk=" + e.getValue());
					continue;
				}
				final String body = bodyOf(row);
				final List<String> missing = new ArrayList<>();
				for (String tok : e.getValue()) {
					if (!body.contains(tok)) {
						missing.add(tok);
					}
				}
				if (!missing.isEmpty()) {
					violations.add("delayed missing key=" + e.getKey()
							+ " missing=" + missing + " body=" + body);
				}
			}
			assertTrue(violations.isEmpty(),
					() -> "G2-class delayed visibility loss: " + violations
							+ " orchidSkips=" + orchidSkips.get());
		} finally {
			pool.shutdownNow();
		}
	}

	private void bootCluster() throws Exception {
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
		DuplexCodecSupport.configure(true, DuplexRepairMode.REBUILD_DATA_FROM_PARITY, true, true, 1, true, true);
		a1.start();
		a2.start();
		b1.start();
		waitWriter(a1, a2, SYNC_MS);
		final ReplicationCoordinator writer = a1.isWriterEligible() ? a1 : a2;
		assertTrue(writer.isWriterEligible(), "sticky writer must be eligible");
		engine = SqlBenchHelper.createEngine(SHARD_COUNT, writer);
		engine.execute("CREATE TABLE " + TABLE
				+ " (id INT PRIMARY KEY, number VARCHAR, status VARCHAR)");
	}

	private void upsertAppend(int key, String token) {
		final SqlSession session = engine.newSession();
		engine.execute(session, "BEGIN");
		upsertAppendInTx(session, key, token);
		engine.execute(session, "COMMIT");
	}

	private void upsertAppendInTx(SqlSession session, int key, String token) {
		final SqlResult updated = engine.execute(session,
				"UPDATE " + TABLE + " SET number = number || ' ' || '" + token
						+ "', status = 'jepsen-append' WHERE id = " + key);
		if (updated.rowsAffected() == 0L) {
			engine.execute(session,
					"INSERT INTO " + TABLE + " (id, number, status) VALUES ("
							+ key + ", '" + token + "', 'jepsen-append')");
		}
	}

	private void assertNotElleNil(int key, String mustContain, String label) {
		final SqlResult row = engine.execute(
				"SELECT number, status FROM " + TABLE + " WHERE id = " + key);
		assertFalse(isElleNil(row), () -> label + " elle-nil key=" + key
				+ " rows=" + row.rows().size() + " body=" + bodyOf(row));
		final String body = bodyOf(row);
		assertTrue(body.contains(mustContain), () -> label + " missing " + mustContain
				+ " key=" + key + " body=" + body);
	}

	private static boolean isElleNil(SqlResult row) {
		if (row.rows().isEmpty()) {
			return true;
		}
		final Object numberObj = row.rows().get(0)[0];
		final Object statusObj = row.rows().get(0).length > 1 ? row.rows().get(0)[1] : null;
		final String number = numberObj == null ? "" : String.valueOf(numberObj);
		return number.isEmpty() && (statusObj == null || "seed".equals(String.valueOf(statusObj)));
	}

	private static String bodyOf(SqlResult row) {
		if (row.rows().isEmpty()) {
			return "<empty>";
		}
		return String.valueOf(row.rows().get(0)[0]);
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