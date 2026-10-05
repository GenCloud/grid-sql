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
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.SqlSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * ASYNC_SHIP history replay for GHA 37235523879 cell D (G-nonadjacent-item-realtime).
 * <p>
 * Smoking gun: p1 {@code :ok [:append 3 "t117"]} then later p0 {@code :ok [:r 3 nil]}
 * after earlier reads had seen tokens on the same key. Concurrent append-gen + delayed
 * reread of every ack'd key must not observe Elle-nil / lost tokens on the sticky writer.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
class AsyncShipGNonadjacentHistoryReplayIT {
	private static final String TABLE = "async_g_nonadj";
	private static final int KEY_LO = 2;
	private static final int KEY_HI = 16;
	private static final int THREADS = 8;
	private static final int TX_PER_THREAD = 80;
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
	}

	@Test
	void scenarioD_concurrentAppendGen_delayedRereadNoElleNil() throws Exception {
		bootCluster();
		final Map<Integer, Set<String>> committed = new ConcurrentHashMap<>();
		for (int k = KEY_LO; k <= KEY_HI; k++) {
			committed.put(Integer.valueOf(k), ConcurrentHashMap.newKeySet());
		}
		final List<String> violations = new CopyOnWriteArrayList<>();
		final AtomicInteger orchidSkips = new AtomicInteger();
		final AtomicReference<Throwable> firstErr = new AtomicReference<>();
		final ExecutorService pool = Executors.newFixedThreadPool(THREADS);
		final CountDownLatch start = new CountDownLatch(1);
		final CountDownLatch done = new CountDownLatch(THREADS);
		try {
			for (int ti = 0; ti < THREADS; ti++) {
				final int threadIdx = ti;
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
								final SqlSession session = engine.newSession();
								engine.execute(session, "BEGIN");
								upsertAppendInTx(session, appendKey, token);
								engine.execute(session,
										"SELECT number, status FROM " + TABLE + " WHERE id = " + readKey);
								engine.execute(session, "COMMIT");
								committed.get(Integer.valueOf(appendKey)).add(token);
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
					() -> "D-class G-nonadjacent visibility loss: " + violations
							+ " orchidSkips=" + orchidSkips.get());
		} finally {
			pool.shutdownNow();
		}
	}

	private void bootCluster() throws Exception {
		final int p1 = freePort();
		final int p2 = freePort();
		final int p3 = freePort();
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
		engine.execute("CREATE TABLE " + TABLE
				+ " (id INT PRIMARY KEY, number VARCHAR, status VARCHAR)");
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

	private static GridConfigurationProperties asyncProps(
			String nodeId, String dc, int port, Path dataDir,
			List<ReplicationPeerProps> peers
	) {
		final GridConfigurationProperties props =
				ReplTestSupport.props(nodeId, "async-gnonadj", dc, port, dataDir, peers);
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