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
import org.genfork.grid.context.config.GridConfigurationProperties.ReplicationPeerProps;
import org.genfork.grid.replication.OrchidNotSyncedException;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.jepsen.JepsenSqlClient;
import org.genfork.grid.sql.netty.SqlServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 1dc N=3 isolate→heal then JepsenSqlClient ON CONFLICT append/reread (matrix cycle A shape).
 * <p>
 * Matrix 2026-10-05 1dc-chaos G-single: ok append key=15 t125 (pre-kill, after isolate n3/heal)
 * then ok r nil. Sticky writer TCP only — no assigned-node fallback. No tip invent.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
class OneDcPartitionHealJepsenAppendVisibilityIT {
	private static final String TABLE = JepsenSqlClient.TABLE;
	private static final String NODE_A = "n1";
	private static final String NODE_B = "n2";
	private static final String NODE_C = "n3";
	private static final long SYNC_MS = 20_000L;
	private static final int SEED_LO = 2;
	private static final int SEED_HI = 16;
	private static final int KEY_CYCLE_A = 15;
	private static final int WORKERS = 4;
	private static final int ROUNDS = 30;
	private static final String SQL_USER = "grid";
	private static final String SQL_PASS = "grid";

	@TempDir
	Path tempDir;

	private ReplicationCoordinator n1;
	private ReplicationCoordinator n2;
	private ReplicationCoordinator n3;
	private SqlEngine engine;
	private SqlServer sqlServer;
	private JepsenSqlClient client;
	private int sqlPort;

	@AfterEach
	void tearDown() {
		if (client != null) {
			client.close();
			client = null;
		}
		if (sqlServer != null) {
			sqlServer.close();
			sqlServer = null;
		}
		if (engine != null) {
			SqlBenchHelper.closeAllStores(engine);
			engine = null;
		}
		if (n1 != null) {
			n1.stop();
		}
		if (n2 != null) {
			n2.stop();
		}
		if (n3 != null) {
			n3.stop();
		}
	}

	/**
	 * Cycle A: after isolate minority + heal, append t125 on key=15 then separate TX reread
	 * must contain the token (never Elle-nil / seed).
	 */
	@Test
	void afterIsolateHeal_key15_appendThenReread_retainsToken() throws Exception {
		bootOneDcWriterSqlAndSeed();
		isolateMinorityThenHeal();
		final String token = "t125";
		final Map<String, Object> append = client.invoke("txn",
				List.of(List.of(":append", Integer.valueOf(KEY_CYCLE_A), token)));
		assertTrue("ok".equals(append.get("type")), () -> "append t125 failed: " + append);
		final Map<String, Object> read = client.invoke("txn",
				List.of(List.of(":r", Integer.valueOf(KEY_CYCLE_A))));
		assertTrue("ok".equals(read.get("type")), () -> "read failed: " + read);
		final Object tokens = txnReadTokens(read.get("value"));
		assertTrue(tokens != null,
				() -> "cycle-A class: Elle-nil after ok append txn=" + read);
		assertTrue(String.valueOf(tokens).contains(token),
				() -> "missing t125 body=" + tokens);
	}

	/**
	 * Post-heal multi-worker pressure (same ON CONFLICT path as Jepsen append).
	 */
	@Test
	void afterIsolateHeal_multiWorker_appendThenReread_mustNotSeeSeedNil() throws Exception {
		bootOneDcWriterSqlAndSeed();
		isolateMinorityThenHeal();
		final List<String> violations = new CopyOnWriteArrayList<>();
		final AtomicInteger orchidSkips = new AtomicInteger();
		final AtomicReference<Throwable> firstErr = new AtomicReference<>();
		final ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
		final CountDownLatch start = new CountDownLatch(1);
		final CountDownLatch done = new CountDownLatch(WORKERS);
		try {
			for (int wi = 0; wi < WORKERS; wi++) {
				final int worker = wi;
				pool.execute(() -> {
					try {
						start.await();
						for (int n = 0; n < ROUNDS; n++) {
							final String token = "t" + worker + "n" + n;
							final int key = SEED_LO + ((worker + n) % (SEED_HI - SEED_LO + 1));
							try {
								final Map<String, Object> append = client.invoke("txn",
										List.of(List.of(":append", Integer.valueOf(key), token)));
								if (!"ok".equals(append.get("type"))) {
									if (isOrchidResult(append)) {
										orchidSkips.incrementAndGet();
										continue;
									}
									violations.add("append not ok worker=" + worker + " key=" + key
											+ " token=" + token + " res=" + append);
									continue;
								}
								final Map<String, Object> read = client.invoke("txn",
										List.of(List.of(":r", Integer.valueOf(key))));
								if (!"ok".equals(read.get("type"))) {
									if (isOrchidResult(read)) {
										orchidSkips.incrementAndGet();
										continue;
									}
									violations.add("read not ok after append worker=" + worker
											+ " key=" + key + " token=" + token + " res=" + read);
									continue;
								}
								final Object tokens = txnReadTokens(read.get("value"));
								if (tokens == null) {
									violations.add("Elle-nil after ok append worker=" + worker
											+ " key=" + key + " token=" + token + " txn="
											+ read.get("value"));
									continue;
								}
								if (!String.valueOf(tokens).contains(token)) {
									violations.add("lost token after ok append worker=" + worker
											+ " key=" + key + " token=" + token + " body="
											+ tokens);
								}
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
			assertTrue(violations.isEmpty(),
					() -> "1dc isolate/heal Jepsen-TCP visibility: " + violations
							+ " orchidSkips=" + orchidSkips.get());
		} finally {
			pool.shutdownNow();
		}
	}

	private void isolateMinorityThenHeal() throws Exception {
		// Isolate minority n3 (matrix: isolate n3 then heal before cycle A).
		n1.isolatePeer(NODE_C);
		n2.isolatePeer(NODE_C);
		n3.isolatePeer(NODE_A);
		n3.isolatePeer(NODE_B);
		final long majDeadline = System.currentTimeMillis() + 8_000L;
		while (System.currentTimeMillis() < majDeadline
				&& (!n1.getOrchidNode().isSynced() || !n2.getOrchidNode().isSynced())) {
			Thread.sleep(20L);
		}
		assertTrue(n1.getOrchidNode().isSynced() || n2.getOrchidNode().isSynced(),
				"majority must stay synced while n3 isolated");
		Thread.sleep(200L);
		n1.reconnectPeer(NODE_C);
		n2.reconnectPeer(NODE_C);
		n3.reconnectPeer(NODE_A);
		n3.reconnectPeer(NODE_B);
		waitSynced(n1, SYNC_MS);
		waitSynced(n2, SYNC_MS);
		waitSynced(n3, SYNC_MS);
		waitWriterEligible(n1, n2, n3, SYNC_MS);
		final ReplicationCoordinator writer = pickWriter(n1, n2, n3);
		assertTrue(writer != null, "no writerEligible after heal");
		assertTrue(client.writerEligible(),
				() -> "SQL sticky writerEligible=false after heal meta=" + client.lastServerMeta());
	}

	private void bootOneDcWriterSqlAndSeed() throws Exception {
		final int p1 = freePort();
		final int p2 = freePort();
		final int p3 = freePort();
		sqlPort = freePort();
		final GridConfigurationProperties props1 = oneDcProps(NODE_A, p1, tempDir.resolve("n1"),
				List.of(peer(NODE_B, p2), peer(NODE_C, p3)));
		final GridConfigurationProperties props2 = oneDcProps(NODE_B, p2, tempDir.resolve("n2"),
				List.of(peer(NODE_A, p1), peer(NODE_C, p3)));
		final GridConfigurationProperties props3 = oneDcProps(NODE_C, p3, tempDir.resolve("n3"),
				List.of(peer(NODE_A, p1), peer(NODE_B, p2)));
		n1 = new ReplicationCoordinator(props1);
		n2 = new ReplicationCoordinator(props2);
		n3 = new ReplicationCoordinator(props3);
		n1.start();
		n2.start();
		n3.start();
		waitSynced(n1, SYNC_MS);
		waitSynced(n2, SYNC_MS);
		waitSynced(n3, SYNC_MS);
		waitWriterEligible(n1, n2, n3, SYNC_MS);
		final ReplicationCoordinator writer = pickWriter(n1, n2, n3);
		assertTrue(writer != null, "no writerEligible at boot");
		engine = SqlBenchHelper.createEngine(8, writer);
		sqlServer = new SqlServer("127.0.0.1", sqlPort, engine, SQL_USER, SQL_PASS, 64);
		sqlServer.start();
		TimeUnit.MILLISECONDS.sleep(150L);
		engine.execute("CREATE TABLE " + TABLE
				+ " (id INT PRIMARY KEY, number VARCHAR, status VARCHAR)");
		for (int id = SEED_LO; id <= SEED_HI; id++) {
			engine.execute("INSERT INTO " + TABLE
					+ " (id, number, status) VALUES (" + id + ", '', 'seed')");
		}
		final String url = "grid://" + SQL_USER + ":" + SQL_PASS + "@127.0.0.1:" + sqlPort
				+ "/public?maxConnections=1&maxTxContexts=64";
		client = new JepsenSqlClient(url);
	}

	private static GridConfigurationProperties oneDcProps(
			String nodeId, int port, Path dataDir, List<ReplicationPeerProps> peers
	) {
		final GridConfigurationProperties props =
				ReplTestSupport.safetyProps(nodeId, "1dc-heal-jepsen", "dc-a", port, dataDir, peers);
		props.getReplication().getCrossDc().setEnabled(false);
		props.getReplication().getCrossDc().setMode("ASYNC_SHIP");
		props.getReplication().getOrchid().setMaxProposeInFlight(16);
		props.getReplication().getRepair().setHomologousEnabled(true);
		props.getReplication().getHa().setMaxStaleLag(0);
		props.getReplication().getSwarm().setEnabled(false);
		return props;
	}

	private static ReplicationPeerProps peer(String id, int port) {
		return ReplTestSupport.peer(id, "dc-a", port);
	}

	private static ReplicationCoordinator pickWriter(
			ReplicationCoordinator a, ReplicationCoordinator b, ReplicationCoordinator c
	) {
		if (a.isWriterEligible()) {
			return a;
		}
		if (b.isWriterEligible()) {
			return b;
		}
		if (c.isWriterEligible()) {
			return c;
		}
		return null;
	}

	private static Object txnReadTokens(Object txnValue) {
		if (!(txnValue instanceof List<?> mops) || mops.isEmpty()) {
			return null;
		}
		final Object mop0 = mops.get(0);
		if (!(mop0 instanceof List<?> triple) || triple.size() < 3) {
			return null;
		}
		return triple.get(2);
	}

	private static boolean isOrchidResult(Map<String, Object> res) {
		final Object err = res.get("error");
		if (err == null) {
			return false;
		}
		final String s = String.valueOf(err);
		return s.contains("orchid") || s.contains("OrchidNotSynced");
	}

	private static void waitSynced(ReplicationCoordinator c, long timeoutMs) throws Exception {
		final long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline && !c.getOrchidNode().isSynced()) {
			Thread.sleep(20L);
		}
		assertTrue(c.getOrchidNode().isSynced(), "orchid synced node=" + c.getNodeState().getNodeId());
	}

	private static void waitWriterEligible(
			ReplicationCoordinator a,
			ReplicationCoordinator b,
			ReplicationCoordinator c,
			long timeoutMs
	) throws Exception {
		final long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			if (a.isWriterEligible() || b.isWriterEligible() || c.isWriterEligible()) {
				return;
			}
			Thread.sleep(20L);
		}
		fail("1dc writerEligible timeout"
				+ " a.awaitTip=" + a.getOrchidNode().awaitsPeerTipAdvertisement()
				+ " b.awaitTip=" + b.getOrchidNode().awaitsPeerTipAdvertisement()
				+ " c.awaitTip=" + c.getOrchidNode().awaitsPeerTipAdvertisement());
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
