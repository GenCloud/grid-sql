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
import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.context.config.GridConfigurationProperties;
import org.genfork.grid.replication.OrchidNotSyncedException;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.SqlSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * E2E characterization for Jepsen multidc-unclean-revive Elle G-single / incompatible-order.
 * <p>
 * Evidence {@code 2026-10-04-unclean-I-rfix-r2}: after unclean proposer kill, sticky writer
 * read key as empty then Jepsen {@code UPDATE; if 0 rows INSERT} forked a new list, losing
 * previously {@code :ok} tokens.
 * <p>
 * No survivor OpLog ship helper (that masks lag). Lagging B is partitioned during acked
 * appends on A (+C), A is killed, B-C heal; Jepsen-style upsert-append must keep acked tokens.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
public class OrchidUncleanLaggingSurvivorForkIT {

	private static final String TABLE = "list_append_fork";
	private static final String CLUSTER = "ulfork";
	private static final String NODE_A = "ulfork-a";
	private static final String NODE_B = "ulfork-b";
	private static final String NODE_C = "ulfork-c";
	private static final int KEY_ID = 14;
	private static final int ACKED_BEFORE_KILL = 4;
	private static final int APPEND_AFTER_HEAL = 8;
	private static final long SYNC_TIMEOUT_MS = 15_000L;
	private static final long FAILOVER_TIMEOUT_MS = 20_000L;
	private static final long CATALOG_TIMEOUT_MS = 10_000L;
	private static final long PARK_NANOS = TimeUnit.MILLISECONDS.toNanos(50L);

	@TempDir(cleanup = CleanupMode.NEVER)
	Path tempDir;

	@Test
	@Timeout(90)
	void laggingSurvivorMustNotForkAckedListAfterUncleanKill() throws Exception {
		final int portA = freePort();
		final int portB = freePort();
		final int portC = freePort();
		final GridConfigurationProperties propsA = props(NODE_A, portA, tempDir.resolve("a"),
				List.of(peer(NODE_B, portB), peer(NODE_C, portC)));
		final GridConfigurationProperties propsB = props(NODE_B, portB, tempDir.resolve("b"),
				List.of(peer(NODE_A, portA), peer(NODE_C, portC)));
		final GridConfigurationProperties propsC = props(NODE_C, portC, tempDir.resolve("c"),
				List.of(peer(NODE_A, portA), peer(NODE_B, portB)));
		propsA.getReplication().getHa().setMaxStaleLag(0L);
		propsB.getReplication().getHa().setMaxStaleLag(0L);
		propsC.getReplication().getHa().setMaxStaleLag(0L);

		final ReplicationCoordinator a = new ReplicationCoordinator(propsA);
		final ReplicationCoordinator b = new ReplicationCoordinator(propsB);
		final ReplicationCoordinator c = new ReplicationCoordinator(propsC);
		final SqlEngine engineA = new SqlEngine(new TableCatalog(tempDir.resolve("a-cat")), a, 4);
		final SqlEngine engineB = new SqlEngine(new TableCatalog(tempDir.resolve("b-cat")), b, 4);
		final SqlEngine engineC = new SqlEngine(new TableCatalog(tempDir.resolve("c-cat")), c, 4);

		final List<String> ackedTokens = new CopyOnWriteArrayList<>();
		try {
			a.start();
			b.start();
			c.start();
			waitThreeSyncedWithProposer(a, b, c, NODE_A, SYNC_TIMEOUT_MS);

			executeUntilSynced(engineA, "CREATE TABLE " + TABLE
					+ " (id INT PRIMARY KEY, number VARCHAR, status VARCHAR)", CATALOG_TIMEOUT_MS);
			waitCatalog(engineA, engineB, engineC, TABLE, CATALOG_TIMEOUT_MS);
			executeUntilSynced(engineA, "INSERT INTO " + TABLE
					+ " (id, number, status) VALUES (" + KEY_ID + ", '', 'seed')", CATALOG_TIMEOUT_MS);
			waitTipCaughtUp(c, a, CATALOG_TIMEOUT_MS);

			isolateB(a, b, c);
			assertTrue(a.isWriterEligible(), "A must remain writer after isolating B");
			for (int i = 0; i < ACKED_BEFORE_KILL; i++) {
				jepsenUpsertAppendAcked(engineA, "pre" + i, ackedTokens);
			}
			assertFalse(ackedTokens.isEmpty(), "must have acked tokens before kill");
			waitTipCaughtUp(c, a, FAILOVER_TIMEOUT_MS);
			assertTrue(readTokens(engineA, KEY_ID).containsAll(ackedTokens), "A must retain acked tokens");

			final long tipCAtKill = c.getOrchidNode().getLastCommittedSeq();
			final long tipBAtKill = b.getOrchidNode().getLastCommittedSeq();
			assertTrue(tipBAtKill < tipCAtKill,
					() -> "B must lag C before kill tipB=" + tipBAtKill + " tipC=" + tipCAtKill);

			b.isolatePeer(NODE_A);
			c.isolatePeer(NODE_A);
			a.isolatePeer(NODE_B);
			a.isolatePeer(NODE_C);
			stopQuietly(a);

			healBc(b, c);

			// Race window: before first phase tip from C, lex-smaller lagging B can be eligible.
			final String raceDetail = observeEligibleWhileTipBehind(b, c, engineB, ackedTokens, 2_000L);
			assertTrue(raceDetail == null,
					() -> "unclean heal race (G-single class): " + raceDetail);

			waitPeerTipAdvertised(b, c, FAILOVER_TIMEOUT_MS);
			waitTipCaughtUp(b, c, FAILOVER_TIMEOUT_MS);

			waitSurvivorWriter(b, c, FAILOVER_TIMEOUT_MS);
			for (int i = 0; i < APPEND_AFTER_HEAL; i++) {
				final SqlEngine writer = phaseRankedWriter(b, c, engineB, engineC);
				if (writer == null) {
					LockSupport.parkNanos(PARK_NANOS);
					continue;
				}
				jepsenUpsertAppendAcked(writer, "post" + i, ackedTokens);
			}

			final SqlEngine reader = phaseRankedWriter(b, c, engineB, engineC);
			assertTrue(reader != null, "survivor writer required for final read");
			final List<String> missing = missingTokens(ackedTokens, readTokens(reader, KEY_ID));
			assertTrue(missing.isEmpty(),
					() -> "lost ack'd tokens after unclean missing=" + missing
							+ " acked=" + ackedTokens
							+ " tipB=" + b.getOrchidNode().getLastCommittedSeq()
							+ " tipC=" + c.getOrchidNode().getLastCommittedSeq());
		} finally {
			stopQuietly(a);
			stopQuietly(b);
			stopQuietly(c);
		}
	}

	private static GridConfigurationProperties props(
			String nodeId,
			int port,
			Path dataDir,
			List<GridConfigurationProperties.ReplicationPeerProps> peers) {
		return ReplTestSupport.props(nodeId, CLUSTER, "dc-a", port, dataDir, peers);
	}

	private static GridConfigurationProperties.ReplicationPeerProps peer(String id, int port) {
		return ReplTestSupport.peer(id, "dc-a", port);
	}

	private static void isolateB(ReplicationCoordinator a, ReplicationCoordinator b, ReplicationCoordinator c) {
		a.isolatePeer(NODE_B);
		c.isolatePeer(NODE_B);
		b.isolatePeer(NODE_A);
		b.isolatePeer(NODE_C);
	}

	private static void healBc(ReplicationCoordinator b, ReplicationCoordinator c) {
		b.reconnectPeer(NODE_C);
		c.reconnectPeer(NODE_B);
	}

	private static String observeEligibleWhileTipBehind(
			ReplicationCoordinator lagging,
			ReplicationCoordinator ahead,
			SqlEngine laggingEngine,
			List<String> ackedTokens,
			long windowMs) {
		final long deadline = System.currentTimeMillis() + windowMs;
		while (System.currentTimeMillis() < deadline) {
			final long tipLag = lagging.getOrchidNode().getLastCommittedSeq();
			final long tipAhead = ahead.getOrchidNode().getLastCommittedSeq();
			final long maxSeen = lagging.getOrchidNode().maxSeenPeerCommittedSeq();
			if (lagging.isWriterEligible() && tipLag < tipAhead) {
				final String probe = probeLaggingUpsert(laggingEngine);
				if (probe.startsWith("success")) {
					final List<String> body = readTokens(laggingEngine, KEY_ID);
					final List<String> missing = missingTokens(ackedTokens, body);
					if (!missing.isEmpty()) {
						return "forked missing=" + missing + " body=" + body
								+ " tipLag=" + tipLag + " tipAhead=" + tipAhead + " maxSeen=" + maxSeen;
					}
					return "eligible tip-behind tipLag=" + tipLag + " tipAhead=" + tipAhead
							+ " maxSeen=" + maxSeen + " probe=success-but-tokens-kept";
				}
				return "eligible tip-behind tipLag=" + tipLag + " tipAhead=" + tipAhead
						+ " maxSeen=" + maxSeen + " probe=" + probe;
			}
			LockSupport.parkNanos(PARK_NANOS);
		}
		return null;
	}
	private static String probeLaggingUpsert(SqlEngine engine) {
		try {
			jepsenUpsertAppend(engine, "fork-probe");
			return "success";
		} catch (RuntimeException ex) {
			if (isOrchid(ex)) {
				return "orchid-fence:" + ex.getClass().getSimpleName();
			}
			return "other:" + ex;
		}
	}

	private static void jepsenUpsertAppend(SqlEngine engine, String token) {
		final SqlSession session = engine.newSession();
		try {
			engine.execute(session, "BEGIN");
			final SqlResult updated = engine.execute(session,
					"UPDATE " + TABLE + " SET number = number || ' ' || '" + token
							+ "', status = 'jepsen-append' WHERE id = " + KEY_ID);
			if (updated.rowsAffected() == 0L) {
				engine.execute(session, "INSERT INTO " + TABLE
						+ " (id, number, status) VALUES (" + KEY_ID + ", '" + token + "', 'jepsen-append')");
			}
			engine.execute(session, "COMMIT");
		} catch (RuntimeException ex) {
			try {
				engine.execute(session, "ROLLBACK");
			} catch (RuntimeException ignored) {
				// best-effort
			}
			throw ex;
		}
	}

	private static void jepsenUpsertAppendAcked(SqlEngine engine, String token, List<String> acked) {
		try {
			jepsenUpsertAppend(engine, token);
			acked.add(token);
		} catch (OrchidNotSyncedException fence) {
			// Fail-closed mid-flap is Elle-safe.
		} catch (RuntimeException ex) {
			if (isOrchid(ex)) {
				return;
			}
			throw ex;
		}
	}

	private static List<String> readTokensOnWriter(
			ReplicationCoordinator b,
			ReplicationCoordinator c,
			SqlEngine engineB,
			SqlEngine engineC) {
		final SqlEngine writer = phaseRankedWriter(b, c, engineB, engineC);
		if (writer == null) {
			fail("no writer for post-probe read tipB=" + b.getOrchidNode().getLastCommittedSeq()
					+ " tipC=" + c.getOrchidNode().getLastCommittedSeq());
		}
		return readTokens(writer, KEY_ID);
	}

	private static List<String> missingTokens(List<String> expected, List<String> actual) {
		final List<String> missing = new ArrayList<>();
		for (String token : expected) {
			if (!actual.contains(token)) {
				missing.add(token);
			}
		}
		return missing;
	}

	private static List<String> readTokens(SqlEngine engine, int keyId) {
		final SqlResult row = engine.execute("SELECT number FROM " + TABLE + " WHERE id = " + keyId);
		if (row.rows().isEmpty() || row.rows().get(0)[0] == null) {
			return List.of();
		}
		final String number = String.valueOf(row.rows().get(0)[0]).trim();
		if (number.isEmpty()) {
			return List.of();
		}
		return Arrays.asList(number.split("\\s+"));
	}

	private static void waitPeerTipAdvertised(ReplicationCoordinator lagging, ReplicationCoordinator ahead, long timeoutMs) {
		final long aheadTip = ahead.getOrchidNode().getLastCommittedSeq();
		final long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			if (lagging.getOrchidNode().maxSeenPeerCommittedSeq() >= aheadTip
					&& lagging.getOrchidNode().liveLocalPeerCount() >= 1) {
				return;
			}
			LockSupport.parkNanos(PARK_NANOS);
		}
		fail("lagging node never saw ahead tip via phase after heal maxSeen="
				+ lagging.getOrchidNode().maxSeenPeerCommittedSeq()
				+ " aheadTip=" + aheadTip
				+ " live=" + lagging.getOrchidNode().liveLocalPeerCount());
	}

	private static void waitTipCaughtUp(ReplicationCoordinator behind, ReplicationCoordinator ahead, long timeoutMs) {
		final long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			if (behind.getOrchidNode().getLastCommittedSeq() >= ahead.getOrchidNode().getLastCommittedSeq()
					&& ahead.getOrchidNode().getLastCommittedSeq() > 0L) {
				return;
			}
			LockSupport.parkNanos(PARK_NANOS);
		}
		fail("tip catch-up timeout tipBehind=" + behind.getOrchidNode().getLastCommittedSeq()
				+ " tipAhead=" + ahead.getOrchidNode().getLastCommittedSeq());
	}

	private static SqlEngine phaseRankedWriter(
			ReplicationCoordinator b,
			ReplicationCoordinator c,
			SqlEngine engineB,
			SqlEngine engineC) {
		if (b.isWriterEligible() && b.getOrchidNode().isPhaseRankedProposer()) {
			return engineB;
		}
		if (c.isWriterEligible() && c.getOrchidNode().isPhaseRankedProposer()) {
			return engineC;
		}
		return null;
	}

	private static void waitSurvivorWriter(ReplicationCoordinator b, ReplicationCoordinator c, long timeoutMs) {
		final long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			if ((b.isWriterEligible() && b.getOrchidNode().isPhaseRankedProposer())
					|| (c.isWriterEligible() && c.getOrchidNode().isPhaseRankedProposer())) {
				return;
			}
			LockSupport.parkNanos(PARK_NANOS);
		}
		fail("no survivor writerEligible after heal"
				+ " tipB=" + b.getOrchidNode().getLastCommittedSeq()
				+ " tipC=" + c.getOrchidNode().getLastCommittedSeq()
				+ " b.el=" + b.isWriterEligible()
				+ " c.el=" + c.isWriterEligible());
	}

	private static void waitThreeSyncedWithProposer(
			ReplicationCoordinator a,
			ReplicationCoordinator b,
			ReplicationCoordinator c,
			String expectedProposer,
			long timeoutMs) {
		final long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			if (a.getOrchidNode().isSynced() && b.getOrchidNode().isSynced() && c.getOrchidNode().isSynced()
					&& expectedProposer.equals(a.getOrchidNode().getPhaseRankedProposerId())
					&& a.isWriterEligible()) {
				return;
			}
			LockSupport.parkNanos(PARK_NANOS);
		}
		fail("expected proposer " + expectedProposer
				+ " a.prop=" + a.getOrchidNode().getPhaseRankedProposerId()
				+ " a.el=" + a.isWriterEligible());
	}

	private static void waitCatalog(SqlEngine a, SqlEngine b, SqlEngine c, String table, long timeoutMs) {
		final long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			if (a.catalog().exists(table) && b.catalog().exists(table) && c.catalog().exists(table)) {
				return;
			}
			LockSupport.parkNanos(PARK_NANOS);
		}
		fail("catalog missing table=" + table);
	}

	private static void executeUntilSynced(SqlEngine engine, String sql, long timeoutMs) {
		final long deadline = System.currentTimeMillis() + timeoutMs;
		RuntimeException last = null;
		while (System.currentTimeMillis() < deadline) {
			try {
				engine.execute(sql);
				return;
			} catch (RuntimeException ex) {
				if (!isOrchid(ex)) {
					throw ex;
				}
				last = ex;
				LockSupport.parkNanos(PARK_NANOS);
			}
		}
		if (last != null) {
			throw last;
		}
		fail("executeUntilSynced timeout sql=" + sql);
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

	private static void stopQuietly(ReplicationCoordinator coord) {
		if (coord == null) {
			return;
		}
		try {
			coord.stop();
		} catch (Exception ignored) {
		}
	}

	private static int freePort() throws Exception {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}
}