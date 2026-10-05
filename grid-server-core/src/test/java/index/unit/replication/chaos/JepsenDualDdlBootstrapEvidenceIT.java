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

import index.sql.SqlBenchHelper;
import index.unit.replication.ReplTestSupport;
import org.genfork.grid.context.config.GridConfigurationProperties;
import org.genfork.grid.context.config.GridConfigurationProperties.ReplicationPeerProps;
import org.genfork.grid.replication.OrchidNotSyncedException;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.replication.crossdc.CrossDcMode;
import org.genfork.grid.sql.SqlEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Evidence IT for GHA 37323133691 cell D dual-DDL / stale-schema context.
 * <p>
 * GHA: a1/a2/a3 all logged {@code Jepsen SQL DDL ready}; a2 threw
 * {@code stale schema epoch localApplied=3 remote=2}. Tip-stuck RC is covered by
 * {@link index.unit.replication.OrchidCommitOneHoldbackDrainIT}; this IT checks whether
 * concurrent Jepsen-style bootstrap still breaks writerEligible / tip equality after P0.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
public class JepsenDualDdlBootstrapEvidenceIT {
	private static final String TABLE_REGISTER = "jepsen_register";
	private static final String TABLE_PARENT = "jepsen_parent";
	private static final String TABLE_CHILD = "jepsen_child";
	private static final int SEED_ID_FROM = 2;
	private static final int SEED_ID_TO = 8;
	private static final int PARENT_ID_OFFSET = 100;
	private static final int SHARDS = 8;
	private static final long SYNC_MS = 30_000L;
	private static final long SETTLE_MS = 15_000L;
	private static final long POLL_MS = 50L;

	@TempDir
	Path tempDir;

	private ReplicationCoordinator a1;
	private ReplicationCoordinator a2;
	private ReplicationCoordinator b1;

	@AfterEach
	void tearDown() {
		stopQuietly(a1);
		stopQuietly(a2);
		stopQuietly(b1);
	}

	@Test
	@Timeout(90)
	void concurrentJepsenStyleBootstrap_mustKeepWriterAndEqualTips() throws Exception {
		bootCluster();
		final SqlEngine engineA1 = SqlBenchHelper.createEngine(SHARDS, a1);
		final SqlEngine engineA2 = SqlBenchHelper.createEngine(SHARDS, a2);
		final AtomicInteger staleSchemaHits = new AtomicInteger();
		final List<String> applyErrors = new CopyOnWriteArrayList<>();
		a1.getOrchidNode().addApplyListener(op -> {
			// listener itself does not throw; errors surface on peer apply path below
		});
		a2.getOrchidNode().addApplyListener(op -> {
		});

		final CountDownLatch start = new CountDownLatch(1);
		final CountDownLatch done = new CountDownLatch(2);
		final List<String> bootstrapErrors = new CopyOnWriteArrayList<>();
		final Thread t1 = new Thread(() -> {
			try {
				start.await();
				// Ungated — mirrors current JepsenSqlBootstrap (no writerEligible check).
				bootstrapUngated(engineA1, staleSchemaHits, bootstrapErrors);
			} catch (Exception ex) {
				bootstrapErrors.add("a1:" + ex);
			} finally {
				done.countDown();
			}
		}, "bootstrap-a1");
		final Thread t2 = new Thread(() -> {
			try {
				start.await();
				bootstrapUngated(engineA2, staleSchemaHits, bootstrapErrors);
			} catch (Exception ex) {
				bootstrapErrors.add("a2:" + ex);
			} finally {
				done.countDown();
			}
		}, "bootstrap-a2");
		t1.start();
		t2.start();
		start.countDown();
		assertTrue(done.await(60L, TimeUnit.SECONDS), "bootstrap threads timed out");

		final long deadline = System.currentTimeMillis() + SETTLE_MS;
		boolean writerOk = false;
		boolean tipsEqual = false;
		while (System.currentTimeMillis() < deadline) {
			writerOk = a1.isWriterEligible() || a2.isWriterEligible();
			final long tip1 = a1.getOrchidNode().getLastCommittedSeq();
			final long tip2 = a2.getOrchidNode().getLastCommittedSeq();
			tipsEqual = tip1 == tip2 && tip1 > 0L;
			if (writerOk && tipsEqual) {
				break;
			}
			Thread.sleep(POLL_MS);
		}

		final long tip1 = a1.getOrchidNode().getLastCommittedSeq();
		final long tip2 = a2.getOrchidNode().getLastCommittedSeq();
		assertTrue(writerOk,
				() -> "GHA-D dual-DDL class: no writerEligible after concurrent bootstrap"
						+ " tipA1=" + tip1 + " tipA2=" + tip2
						+ " staleHits=" + staleSchemaHits.get()
						+ " bootstrapErrors=" + bootstrapErrors
						+ " applyErrors=" + applyErrors);
		assertEquals(tip1, tip2,
				() -> "GHA-D dual-DDL class: tip divergence after concurrent bootstrap"
						+ " tipA1=" + tip1 + " tipA2=" + tip2
						+ " staleHits=" + staleSchemaHits.get()
						+ " bootstrapErrors=" + bootstrapErrors);
		assertEquals(0, staleSchemaHits.get(),
				() -> "GHA-D dual-DDL class: stale schema epoch during concurrent bootstrap"
						+ " bootstrapErrors=" + bootstrapErrors
						+ " tipA1=" + tip1 + " tipA2=" + tip2);
	}

	/**
	 * Same CREATE/seed shape as {@code JepsenSqlBootstrap} without writerEligible gate.
	 */
	private static void bootstrapUngated(
			SqlEngine engine,
			AtomicInteger staleSchemaHits,
			List<String> bootstrapErrors
	) {
		try {
			engine.execute("CREATE TABLE IF NOT EXISTS " + TABLE_REGISTER + """
					 (
					  id INT PRIMARY KEY,
					  number VARCHAR,
					  status VARCHAR
					)
					""");
			engine.execute("CREATE TABLE IF NOT EXISTS " + TABLE_PARENT + """
					 (
					  id INT PRIMARY KEY,
					  name VARCHAR
					)
					""");
			engine.execute("CREATE TABLE IF NOT EXISTS " + TABLE_CHILD + """
					 (
					  id INT PRIMARY KEY,
					  parent_id INT,
					  number VARCHAR,
					  status VARCHAR
					)
					""");
			for (int id = SEED_ID_FROM; id <= SEED_ID_TO; id++) {
				try {
					engine.execute("INSERT INTO " + TABLE_REGISTER
							+ " (id, number, status) VALUES (" + id + ", '', 'seed')");
				} catch (RuntimeException ex) {
					noteStale(ex, staleSchemaHits, bootstrapErrors);
					if (isOrchid(ex)) {
						return;
					}
				}
				try {
					final int parentId = id + PARENT_ID_OFFSET;
					engine.execute("INSERT INTO " + TABLE_PARENT
							+ " (id, name) VALUES (" + parentId + ", 'p" + id + "')");
					engine.execute("INSERT INTO " + TABLE_CHILD
							+ " (id, parent_id, number, status) VALUES ("
							+ id + ", " + parentId + ", '', 'seed')");
				} catch (RuntimeException ex) {
					noteStale(ex, staleSchemaHits, bootstrapErrors);
					if (isOrchid(ex)) {
						return;
					}
				}
			}
		} catch (RuntimeException ex) {
			noteStale(ex, staleSchemaHits, bootstrapErrors);
			if (!isOrchid(ex)) {
				bootstrapErrors.add(ex.toString());
			}
		}
	}

	private static void noteStale(
			RuntimeException ex,
			AtomicInteger staleSchemaHits,
			List<String> bootstrapErrors
	) {
		Throwable cur = ex;
		while (cur != null) {
			final String msg = cur.getMessage();
			if (msg != null && msg.contains("stale schema epoch")) {
				staleSchemaHits.incrementAndGet();
				bootstrapErrors.add(msg);
				return;
			}
			cur = cur.getCause();
		}
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
	}

	private static GridConfigurationProperties asyncProps(
			String nodeId,
			String dc,
			int port,
			Path dataDir,
			List<ReplicationPeerProps> peers
	) {
		final GridConfigurationProperties props =
				ReplTestSupport.props(nodeId, "dual-ddl-ev", dc, port, dataDir, peers);
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
			Thread.sleep(POLL_MS);
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
			Thread.sleep(POLL_MS);
		}
		fail("dc-a writerEligible timeout");
	}

	private static void stopQuietly(ReplicationCoordinator c) {
		if (c == null) {
			return;
		}
		try {
			c.stop();
		} catch (Exception ignored) {
		}
	}

	private static int freePort() throws Exception {
		try (ServerSocket s = new ServerSocket(0)) {
			return s.getLocalPort();
		}
	}
}
