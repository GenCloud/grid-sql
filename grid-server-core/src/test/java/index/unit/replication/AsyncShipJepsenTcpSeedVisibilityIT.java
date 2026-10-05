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
import org.genfork.grid.replication.crossdc.CrossDcMode;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.store.TableStore;
import org.genfork.grid.sql.jepsen.JepsenSqlClient;
import org.genfork.grid.sql.netty.SqlServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * TCP sticky JepsenSqlClient + seed rows — unclean-p0 / GHA-I Elle nil class.
 * <p>
 * Contract matches Jepsen (not in-process SqlEngine): seed {@code ('','seed')},
 * UPDATE concat without status=, separate TX append then reread, multi-worker
 * same-key pressure. Smoking gun: ok append then ok r nil / lost token.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
class AsyncShipJepsenTcpSeedVisibilityIT {
	private static final String TABLE = JepsenSqlClient.TABLE;
	private static final long SYNC_MS = 12_000L;
	private static final int SEED_LO = 2;
	private static final int SEED_HI = 16;
	private static final int WORKERS = 4;
	private static final int ROUNDS = 40;
	private static final String SQL_USER = "grid";
	private static final String SQL_PASS = "grid";

	@TempDir
	Path tempDir;

	private ReplicationCoordinator a1;
	private ReplicationCoordinator a2;
	private ReplicationCoordinator b1;
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
		if (a1 != null) {
			a1.stop();
		}
		if (a2 != null) {
			a2.stop();
		}
		if (b1 != null) {
			b1.stop();
		}
	}

	@Test
	void sameKeyAppendThenSeparateTxnReread_mustNotSeeSeedNil() throws Exception {
		bootWriterSqlAndSeed();
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
								final Object value = read.get("value");
								final Object tokens = txnReadTokens(value);
								if (tokens == null) {
									violations.add("Elle-nil after ok append worker=" + worker
											+ " key=" + key + " token=" + token + " txn=" + value);
									continue;
								}
								final String body = String.valueOf(tokens);
								if (!body.contains(token)) {
									violations.add("lost token after ok append worker=" + worker
											+ " key=" + key + " token=" + token + " body=" + body);
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
					() -> "Jepsen-TCP seed visibility loss: " + violations
							+ " orchidSkips=" + orchidSkips.get());
		} finally {
			pool.shutdownNow();
		}
	}

	/**
	 * unclean-p0 shape: multi-worker same key=2, separate TX append then reread.
	 */
	@Test
	void key2_multiWorker_appendThenReread_mustRetainTokens() throws Exception {
		bootWriterSqlAndSeed();
		final int key = 2;
		final int workers = 8;
		final int rounds = 80;
		final List<String> violations = new CopyOnWriteArrayList<>();
		final List<String> ackTokens = new CopyOnWriteArrayList<>();
		final AtomicInteger orchidSkips = new AtomicInteger();
		final AtomicReference<Throwable> firstErr = new AtomicReference<>();
		final ExecutorService pool = Executors.newFixedThreadPool(workers);
		final CountDownLatch start = new CountDownLatch(1);
		final CountDownLatch done = new CountDownLatch(workers);
		try {
			for (int wi = 0; wi < workers; wi++) {
				final int worker = wi;
				pool.execute(() -> {
					try {
						start.await();
						for (int n = 0; n < rounds; n++) {
							final String token = "k2w" + worker + "n" + n;
							try {
								final Map<String, Object> append = client.invoke("txn",
										List.of(List.of(":append", Integer.valueOf(key), token)));
								if (!"ok".equals(append.get("type"))) {
									if (isOrchidResult(append)) {
										orchidSkips.incrementAndGet();
										continue;
									}
									violations.add("append not ok token=" + token + " res=" + append);
									continue;
								}
								ackTokens.add(token);
								final Map<String, Object> read = client.invoke("txn",
										List.of(List.of(":r", Integer.valueOf(key))));
								if (!"ok".equals(read.get("type"))) {
									if (isOrchidResult(read)) {
										orchidSkips.incrementAndGet();
										continue;
									}
									violations.add("read not ok token=" + token + " res=" + read);
									continue;
								}
								final Object tokens = txnReadTokens(read.get("value"));
								if (tokens == null) {
									violations.add("Elle-nil after ok append token=" + token);
									continue;
								}
								if (!String.valueOf(tokens).contains(token)) {
									violations.add("lost own token=" + token + " body=" + tokens);
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
			assertTrue(done.await(240_000L, TimeUnit.MILLISECONDS), "key2 workers timed out");
			if (firstErr.get() != null) {
				fail("worker error: " + firstErr.get(), firstErr.get());
			}
			final Map<String, Object> finalRead = client.invoke("txn",
					List.of(List.of(":r", Integer.valueOf(key))));
			final Object finalTokens = txnReadTokens(finalRead.get("value"));
			final String finalBody = finalTokens == null ? "<nil>" : String.valueOf(finalTokens);
			final List<String> missing = new ArrayList<>();
			for (String tok : ackTokens) {
				if (!finalBody.contains(tok)) {
					missing.add(tok);
				}
			}
			if (!missing.isEmpty()) {
				violations.add("lost-prefix final body=" + finalBody + " missingSample="
						+ missing.subList(0, Math.min(12, missing.size()))
						+ " missingCount=" + missing.size() + " ack=" + ackTokens.size());
			}
			assertTrue(violations.isEmpty(),
					() -> "key2 Jepsen-TCP visibility: " + violations
							+ " orchidSkips=" + orchidSkips.get());
		} finally {
			pool.shutdownNow();
		}
	}

	/**
	 * Evidence unclean-p0: plain UPDATE0+INSERT replaced the row (history kept only last token).
	 * ON CONFLICT append must concat on live seed / prior row.
	 */
	@Test
	void onConflictAppend_onSeed_retainsConcatTokens() throws Exception {
		bootWriterSqlAndSeed();
		final int key = 2;
		final Map<String, Object> a0 = client.invoke("txn",
				List.of(List.of(":append", Integer.valueOf(key), "t0")));
		assertTrue("ok".equals(a0.get("type")), () -> "append t0 failed: " + a0);
		final Map<String, Object> a1 = client.invoke("txn",
				List.of(List.of(":append", Integer.valueOf(key), "t1")));
		assertTrue("ok".equals(a1.get("type")), () -> "append t1 failed: " + a1);
		final Map<String, Object> read = client.invoke("txn",
				List.of(List.of(":r", Integer.valueOf(key))));
		final Object tokens = txnReadTokens(read.get("value"));
		assertTrue(tokens != null, () -> "Elle-nil after appends txn=" + read);
		final String body = String.valueOf(tokens);
		assertTrue(body.contains("t0"), () -> "missing t0 body=" + body);
		assertTrue(body.contains("t1"), () -> "missing t1 body=" + body);
	}

	/**
	 * Characterization: UPDATE0 + plain INSERT after RAM miss wipes prior token (no sealed).
	 * Documents unclean-p0 lost-prefix class; ON CONFLICT client avoids this when conflict visible.
	 */
	@Test
	void plainInsertAfterUpdate0_wipesPriorToken_characterization() throws Exception {
		bootWriterSqlAndSeed();
		final int key = 2;
		final TableStore store = engine.catalog().getStore(TABLE);
		engine.execute("UPDATE " + TABLE + " SET number = 't0keep', status = 'jepsen-append' WHERE id = "
				+ key);
		final byte[] keyBytes = store.keyBytesForPk(Integer.valueOf(key));
		store.processorForTest(keyBytes).evictCommitted(keyBytes);
		final long updated = engine.execute(
				"UPDATE " + TABLE + " SET number = number || ' ' || 't1' WHERE id = " + key)
				.rowsAffected();
		assertTrue(updated == 0L, "expected UPDATE0 after evict");
		engine.execute("INSERT INTO " + TABLE + " (id, number, status) VALUES ("
				+ key + ", 't1only', 'jepsen-append')");
		final Map<String, Object> read = client.invoke("txn",
				List.of(List.of(":r", Integer.valueOf(key))));
		final Object tokens = txnReadTokens(read.get("value"));
		final String body = tokens == null ? "<nil>" : String.valueOf(tokens);
		assertTrue(body.contains("t1only"), () -> "expected plain INSERT body=" + body);
		assertTrue(!body.contains("t0keep"),
				() -> "characterization broken: prior token still present body=" + body);
	}

	private void bootWriterSqlAndSeed() throws Exception {
		final int p1 = freePort();
		final int p2 = freePort();
		final int p3 = freePort();
		sqlPort = freePort();
		final GridConfigurationProperties propsA1 = asyncProps("a1", "dc-a", p1, tempDir.resolve("a1"),
				List.of(peer("a2", "dc-a", p2), peer("b1", "dc-b", p3)));
		propsA1.getReplication().getCrossDc().setLearners(List.of("b1"));
		propsA1.getReplication().getCrossDc().setVoters(List.of());
		final GridConfigurationProperties propsA2 = asyncProps("a2", "dc-a", p2, tempDir.resolve("a2"),
				List.of(peer("a1", "dc-a", p1), peer("b1", "dc-b", p3)));
		propsA2.getReplication().getCrossDc().setLearners(List.of("b1"));
		final GridConfigurationProperties propsB1 = asyncProps("b1", "dc-b", p3, tempDir.resolve("b1"),
				List.of(peer("a1", "dc-a", p1), peer("a2", "dc-a", p2)));
		propsB1.getReplication().getCrossDc().setLearners(List.of("b1"));
		a1 = new ReplicationCoordinator(propsA1);
		a2 = new ReplicationCoordinator(propsA2);
		b1 = new ReplicationCoordinator(propsB1);
		a1.start();
		a2.start();
		b1.start();
		waitSynced(a1, SYNC_MS);
		waitSynced(a2, SYNC_MS);
		waitWriterEligible(a1, a2, SYNC_MS);
		final ReplicationCoordinator writer = a1.isWriterEligible() ? a1 : a2;
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

	/**
	 * Jepsen txn ok value is a list of mops; read mop is [:r key tokens|null].
	 */
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

	private static GridConfigurationProperties asyncProps(
			String nodeId, String dc, int port, Path dataDir,
			List<ReplicationPeerProps> peers
	) {
		final GridConfigurationProperties props =
				ReplTestSupport.props(nodeId, "async-jepsen-tcp", dc, port, dataDir, peers);
		props.getReplication().getCrossDc().setMode(CrossDcMode.ASYNC_SHIP.name());
		props.getReplication().getCrossDc().setPhaseCoupling(false);
		props.getReplication().getCrossDc().setWriteAdmission(true);
		props.getReplication().getOrchid().setMaxProposeInFlight(16);
		props.getReplication().getRepair().setHomologousEnabled(true);
		props.getReplication().getHa().setMaxStaleLag(0);
		props.getReplication().getSwarm().setEnabled(false);
		return props;
	}

	private static ReplicationPeerProps peer(String id, String dc, int port) {
		return ReplTestSupport.peer(id, dc, port);
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
			long timeoutMs
	) throws Exception {
		final long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			if (a.isWriterEligible() || b.isWriterEligible()) {
				return;
			}
			Thread.sleep(20L);
		}
		fail("dc-a writerEligible timeout"
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