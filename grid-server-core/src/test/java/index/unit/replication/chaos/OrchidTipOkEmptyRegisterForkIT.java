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
import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.orchid.OrchidNode;
import org.genfork.grid.replication.orchid.OrchidTransport.OrchidCommitMessage;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.SqlSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
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
 * E2E for GHA 37346665476 cell I (multidc-unclean-revive): live tip-ok + empty register.
 * <p>
 * Distinct from {@link OrchidAsyncShipTipOkEmptyMapForkIT} (synthetic {@code advanceCommittedTip}).
 * Proves the live {@code onCommit} entrance: contiguous commit for an unregistered domain must
 * not advance tip (pre-fix: silent no-op listeners left tip-ok empty-map). Then unclean kill/heal
 * like lagging-survivor — survivor writer must keep the acked list prefix (no Elle G-single fork).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
public class OrchidTipOkEmptyRegisterForkIT {

	private static final String TABLE = "list_append_empty_reg";
	private static final String GHOST_DOMAIN = "list_append_empty_reg_ghost";
	private static final String CLUSTER = "emptyreg";
	private static final String NODE_A = "emptyreg-a";
	private static final String NODE_B = "emptyreg-b";
	private static final String NODE_C = "emptyreg-c";
	private static final int KEY_ID = 4;
	private static final int ACKED_BEFORE_KILL = 4;
	private static final long SYNC_TIMEOUT_MS = 15_000L;
	private static final long FAILOVER_TIMEOUT_MS = 20_000L;
	private static final long CATALOG_TIMEOUT_MS = 10_000L;
	private static final long INJECT_TIMEOUT_MS = 10_000L;
	private static final long PARK_NANOS = TimeUnit.MILLISECONDS.toNanos(50L);
	private static final long ELIGIBLE_WINDOW_MS = 3_000L;
	private static final byte[] INJECT_KEY = "k4".getBytes(StandardCharsets.UTF_8);
	private static final byte[] INJECT_VAL = "ghost".getBytes(StandardCharsets.UTF_8);

	@TempDir(cleanup = CleanupMode.NEVER)
	Path tempDir;

	@Test
	@Timeout(90)
	void liveTipOkEmptyRegisterMustNotForkAckedList() throws Exception {
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
			waitTipCaughtUp(b, a, CATALOG_TIMEOUT_MS);

			isolateB(a, b, c);
			assertTrue(a.isWriterEligible(), "A must remain writer after isolating B");
			for (int i = 0; i < ACKED_BEFORE_KILL; i++) {
				jepsenUpsertAppendAcked(engineA, "t" + (2 + i * 9), ackedTokens);
			}
			assertFalse(ackedTokens.isEmpty(), "must have acked tokens before kill");
			waitTipCaughtUp(c, a, FAILOVER_TIMEOUT_MS);
			assertTrue(readTokens(engineA, KEY_ID).containsAll(ackedTokens), "A must retain acked tokens");

			final long tipA = a.getOrchidNode().getLastCommittedSeq();
			final long tipBBefore = b.getOrchidNode().getLastCommittedSeq();
			assertTrue(tipBBefore < tipA,
					() -> "B must lag tip before ghost commits tipB=" + tipBBefore + " tipA=" + tipA);

			// Live tip entrance: contiguous onCommit for an unregistered domain must not tip-advance
			// (GHA 37346665476: missing registerDomain + tip-before-apply → tip-ok empty map).
			injectGhostDomainCommits(b, tipBBefore, tipBBefore + 1L);
			awaitQueueSettled(INJECT_TIMEOUT_MS);
			final long tipAfterInject = b.getOrchidNode().getLastCommittedSeq();
			assertTrue(tipAfterInject == tipBBefore,
					() -> "ghost onCommit must not advance tip tipAfter="
							+ tipAfterInject + " tipBefore=" + tipBBefore
							+ " catchUp=" + b.getOrchidNode().testingInstallCatchUpRequired());

			b.isolatePeer(NODE_A);
			c.isolatePeer(NODE_A);
			a.isolatePeer(NODE_B);
			a.isolatePeer(NODE_C);
			stopQuietly(a);

			healBc(b, c);
			waitPeerTipAdvertised(b, c, FAILOVER_TIMEOUT_MS);
			waitTipCaughtUp(b, c, FAILOVER_TIMEOUT_MS);

			assertFalse(waitTipOkEmptyWriter(b, engineB, ackedTokens, ELIGIBLE_WINDOW_MS),
					() -> "tip-ok empty-register B must not become writerEligible tipB="
							+ b.getOrchidNode().getLastCommittedSeq()
							+ " b.el=" + b.isWriterEligible()
							+ " catchUp=" + b.getOrchidNode().testingInstallCatchUpRequired());

			waitSurvivorWriter(b, c, FAILOVER_TIMEOUT_MS);
			final SqlEngine writerEngine = phaseRankedWriter(b, c, engineB, engineC);
			assertTrue(writerEngine != null, "survivor writer engine required");
			final List<String> bodyBeforeAppend = readTokens(writerEngine, KEY_ID);
			assertTrue(bodyBeforeAppend.containsAll(ackedTokens),
					() -> "survivor writer must hold acked prefix before append body="
							+ bodyBeforeAppend + " acked=" + ackedTokens
							+ " b.el=" + b.isWriterEligible()
							+ " c.el=" + c.isWriterEligible());

			jepsenUpsertAppendAcked(writerEngine, "t59", ackedTokens);

			final List<String> bodyWriter = readTokens(writerEngine, KEY_ID);
			final List<String> missingOnWriter = missingTokens(ackedTokens, bodyWriter);
			assertTrue(missingOnWriter.isEmpty(),
					() -> "GHA-37346665476 tip-ok empty-register fork: missing=" + missingOnWriter
							+ " acked=" + ackedTokens
							+ " bodyWriter=" + bodyWriter
							+ " tipB=" + b.getOrchidNode().getLastCommittedSeq()
							+ " tipC=" + c.getOrchidNode().getLastCommittedSeq()
							+ " b.el=" + b.isWriterEligible()
							+ " c.el=" + c.isWriterEligible());
		} finally {
			stopQuietly(a);
			stopQuietly(b);
			stopQuietly(c);
		}
	}

	/**
	 * Replay tip gap as TABLE UPSERT commits B cannot apply (no registerDomain).
	 */
	private static void injectGhostDomainCommits(ReplicationCoordinator lagging, long tipBefore, long tipTarget) {
		final OrchidNode orchid = lagging.getOrchidNode();
		for (long seq = tipBefore + 1L; seq <= tipTarget; seq++) {
			final ReplicationOp raw = new ReplicationOp(
					GHOST_DOMAIN, 0, seq, ReplicationOpType.UPSERT, INJECT_KEY, INJECT_VAL, 1L, 0L);
			final ReplicationOp op = OpLogCodec.withChecksum(raw);
			orchid.onCommit(new OrchidCommitMessage(
					NODE_A, seq, OrchidNode.digestOf(op), seq - 1L, seq, op));
		}
	}

	private static void awaitQueueSettled(long timeoutMs) {
		final long settleMs = Math.min(timeoutMs, 500L);
		final long deadline = System.currentTimeMillis() + settleMs;
		while (System.currentTimeMillis() < deadline) {
			LockSupport.parkNanos(PARK_NANOS);
		}
	}


	private static boolean waitTipOkEmptyWriter(
			ReplicationCoordinator lagging,
			SqlEngine laggingEngine,
			List<String> ackedTokens,
			long windowMs) {
		final long deadline = System.currentTimeMillis() + windowMs;
		while (System.currentTimeMillis() < deadline) {
			final List<String> missing = missingTokens(ackedTokens, readTokens(laggingEngine, KEY_ID));
			if (lagging.isWriterEligible()
					&& lagging.getOrchidNode().isPhaseRankedProposer()
					&& !missing.isEmpty()
					&& !lagging.getOrchidNode().awaitsPeerTipAdvertisement()
					&& !lagging.getOrchidNode().testingInstallCatchUpRequired()) {
				return true;
			}
			LockSupport.parkNanos(PARK_NANOS);
		}
		return false;
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
		try {
			final SqlResult row = engine.execute("SELECT number FROM " + TABLE + " WHERE id = " + keyId);
			if (row.rows().isEmpty() || row.rows().get(0)[0] == null) {
				return List.of();
			}
			final String number = String.valueOf(row.rows().get(0)[0]).trim();
			if (number.isEmpty()) {
				return List.of();
			}
			return Arrays.asList(number.split("\\s+"));
		} catch (RuntimeException ex) {
			if (isOrchid(ex)) {
				return List.of();
			}
			throw ex;
		}
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
