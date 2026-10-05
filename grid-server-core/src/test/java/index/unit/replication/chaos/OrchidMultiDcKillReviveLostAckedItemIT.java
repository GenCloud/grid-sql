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
import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.context.config.GridConfigurationProperties;
import org.genfork.grid.replication.OrchidNotSyncedException;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.replication.crossdc.CrossDcMode;
import org.genfork.grid.replication.region.RegionRole;
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
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * E2E for Jepsen M claim-fence while Active DC is down (2011-r1 fact).
 * <p>
 * Evidence {@code 2026-10-04-2011-jepsen-M-p2-ff-r1}: after {@code kill-dc-a},
 * {@code b1/b2 writerEligible false}, then {@code b1 writerEligible true} while
 * {@code a*} still nil (Active still down). Claim path is silence/{@code pollPromotionState} only.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
public class OrchidMultiDcKillReviveLostAckedItemIT {

	private static final String TABLE = "jepsen_list";
	private static final String CLUSTER = "mdc-active-down";
	private static final String NODE_A1 = "a1";
	private static final String NODE_A2 = "a2";
	private static final String NODE_B1 = "b1";
	private static final String NODE_B2 = "b2";
	private static final String DC_A = "dc-a";
	private static final String DC_B = "dc-b";
	private static final int KEY_ID = 18;
	private static final int ACKED_TOKENS = 4;
	private static final long SYNC_TIMEOUT_MS = 20_000L;
	private static final long CLAIM_TIMEOUT_MS = 25_000L;
	private static final long CATALOG_TIMEOUT_MS = 15_000L;
	private static final long FENCE_WINDOW_MS = 2_000L;
	private static final long BOOT_EPOCH = 1L;
	private static final long CLAIM_SILENCE_MS = 200L;
	private static final long PARK_NANOS = TimeUnit.MILLISECONDS.toNanos(20L);
	private static final int REGION_QUORUM_SOLO = 1;
	private static final int SHARDS = 8;

	@TempDir(cleanup = CleanupMode.NEVER)
	Path tempDir;

	/**
	 * PR13-M: after claim + Active revive, Hold must not clear await on same-DC Hold tips when
	 * the Active link flickers up before a remote-DC tip is advertised. Sticky Hold :ok nil
	 * forged G-single against pre-kill acked tokens.
	 */
	@Test
	@Timeout(120)
	void holdMustStayNonEligibleUntilRemoteDcTipAfterActiveRevive() throws Exception {
		final int portA1 = freePort();
		final int portA2 = freePort();
		final int portB1 = freePort();
		final int portB2 = freePort();

		final Path dataA1 = tempDir.resolve("revive-a1");
		final Path dataA2 = tempDir.resolve("revive-a2");
		final Path dataB1 = tempDir.resolve("revive-b1");
		final Path dataB2 = tempDir.resolve("revive-b2");

		final GridConfigurationProperties propsA1 = activeProps(NODE_A1, portA1, dataA1,
				List.of(ReplTestSupport.peer(NODE_A2, DC_A, portA2),
						ReplTestSupport.peer(NODE_B1, DC_B, portB1),
						ReplTestSupport.peer(NODE_B2, DC_B, portB2)));
		final GridConfigurationProperties propsA2 = activeProps(NODE_A2, portA2, dataA2,
				List.of(ReplTestSupport.peer(NODE_A1, DC_A, portA1),
						ReplTestSupport.peer(NODE_B1, DC_B, portB1),
						ReplTestSupport.peer(NODE_B2, DC_B, portB2)));
		final GridConfigurationProperties propsB1 = holdProps(NODE_B1, portB1, dataB1,
				List.of(ReplTestSupport.peer(NODE_A1, DC_A, portA1),
						ReplTestSupport.peer(NODE_A2, DC_A, portA2),
						ReplTestSupport.peer(NODE_B2, DC_B, portB2)));
		final GridConfigurationProperties propsB2 = holdProps(NODE_B2, portB2, dataB2,
				List.of(ReplTestSupport.peer(NODE_A1, DC_A, portA1),
						ReplTestSupport.peer(NODE_A2, DC_A, portA2),
						ReplTestSupport.peer(NODE_B1, DC_B, portB1)));
		propsB2.getReplication().getRegion().setClaimTimeoutMs(TimeUnit.HOURS.toMillis(1L));

		ReplicationCoordinator a1 = new ReplicationCoordinator(propsA1);
		ReplicationCoordinator a2 = new ReplicationCoordinator(propsA2);
		final ReplicationCoordinator b1 = new ReplicationCoordinator(propsB1);
		final ReplicationCoordinator b2 = new ReplicationCoordinator(propsB2);
		SqlEngine engineA1 = new SqlEngine(new TableCatalog(tempDir.resolve("revive-a1-cat")), a1, SHARDS);
		SqlEngine engineA2 = new SqlEngine(new TableCatalog(tempDir.resolve("revive-a2-cat")), a2, SHARDS);
		final SqlEngine engineB1 = new SqlEngine(new TableCatalog(tempDir.resolve("revive-b1-cat")), b1, SHARDS);
		final SqlEngine engineB2 = new SqlEngine(new TableCatalog(tempDir.resolve("revive-b2-cat")), b2, SHARDS);

		final List<String> ackedTokens = new ArrayList<>();
		try {
			a1.start();
			a2.start();
			b1.start();
			b2.start();
			waitActiveWriter(a1, a2, SYNC_TIMEOUT_MS);

			final SqlEngine activeEngine = a1.isWriterEligible() ? engineA1 : engineA2;
			executeUntilSynced(activeEngine,
					"CREATE TABLE " + TABLE + " (id INT PRIMARY KEY, number VARCHAR, status VARCHAR)",
					CATALOG_TIMEOUT_MS);
			waitCatalog(engineA1, engineA2, engineB1, engineB2, TABLE, CATALOG_TIMEOUT_MS);
			executeUntilSynced(activeEngine,
					"INSERT INTO " + TABLE + " (id, number, status) VALUES (" + KEY_ID + ", '', 'seed')",
					CATALOG_TIMEOUT_MS);
			for (int i = 0; i < ACKED_TOKENS; i++) {
				jepsenAppendAcked(activeEngine, "t" + i, ackedTokens);
			}
			assertTrue(!ackedTokens.isEmpty(), "must ack tokens before kill-dc-a");

			a1.isolatePeer(NODE_A2);
			a2.isolatePeer(NODE_A1);
			b1.isolatePeer(NODE_A1);
			b1.isolatePeer(NODE_A2);
			b2.isolatePeer(NODE_A1);
			b2.isolatePeer(NODE_A2);
			a1.isolatePeer(NODE_B1);
			a1.isolatePeer(NODE_B2);
			a2.isolatePeer(NODE_B1);
			a2.isolatePeer(NODE_B2);
			SqlBenchHelper.closeAllStores(engineA1);
			SqlBenchHelper.closeAllStores(engineA2);
			stopQuietly(a1);
			stopQuietly(a2);

			waitHoldClaimedActive(b1, CLAIM_TIMEOUT_MS);
			final String leakDown = observeEligibleWhileActiveDown(b1, b2, FENCE_WINDOW_MS);
			assertTrue(leakDown == null,
					() -> "Active-down leak before revive: " + leakDown);

			// Revive Active (same ports/data) — link flickers up; Hold must stay fenced until
			// a live remote-DC tip is advertised (not same-DC Hold tip match).
			a1 = new ReplicationCoordinator(propsA1);
			a2 = new ReplicationCoordinator(propsA2);
			engineA1 = new SqlEngine(new TableCatalog(tempDir.resolve("revive-a1-cat2")), a1, SHARDS);
			engineA2 = new SqlEngine(new TableCatalog(tempDir.resolve("revive-a2-cat2")), a2, SHARDS);
			b1.reconnectPeer(NODE_A1);
			b1.reconnectPeer(NODE_A2);
			b2.reconnectPeer(NODE_A1);
			b2.reconnectPeer(NODE_A2);
			a1.start();
			a2.start();

			final String leakRevive = observeEligibleWhileRemoteDcTipMissing(b1, b2, FENCE_WINDOW_MS * 3L);
			assertTrue(leakRevive == null,
					() -> "PR13-M class: Hold writerEligible after Active revive before remote-DC tip: "
							+ leakRevive
							+ " remoteTipB1=" + b1.getOrchidNode().maxSeenLiveRemoteDcPeerCommittedSeq()
							+ " tipB1=" + b1.getOrchidNode().getLastCommittedSeq());
		} finally {
			SqlBenchHelper.closeAllStores(engineA1);
			SqlBenchHelper.closeAllStores(engineA2);
			SqlBenchHelper.closeAllStores(engineB1);
			SqlBenchHelper.closeAllStores(engineB2);
			stopQuietly(a1);
			stopQuietly(a2);
			stopQuietly(b1);
			stopQuietly(b2);
		}
	}

	@Test
	@Timeout(90)
	void holdMustStayNonEligibleWhileActiveDcDownAfterClaim() throws Exception {
		final int portA1 = freePort();
		final int portA2 = freePort();
		final int portB1 = freePort();
		final int portB2 = freePort();

		final Path dataA1 = tempDir.resolve("a1");
		final Path dataA2 = tempDir.resolve("a2");
		final Path dataB1 = tempDir.resolve("b1");
		final Path dataB2 = tempDir.resolve("b2");

		final GridConfigurationProperties propsA1 = activeProps(NODE_A1, portA1, dataA1,
				List.of(ReplTestSupport.peer(NODE_A2, DC_A, portA2),
						ReplTestSupport.peer(NODE_B1, DC_B, portB1),
						ReplTestSupport.peer(NODE_B2, DC_B, portB2)));
		final GridConfigurationProperties propsA2 = activeProps(NODE_A2, portA2, dataA2,
				List.of(ReplTestSupport.peer(NODE_A1, DC_A, portA1),
						ReplTestSupport.peer(NODE_B1, DC_B, portB1),
						ReplTestSupport.peer(NODE_B2, DC_B, portB2)));
		final GridConfigurationProperties propsB1 = holdProps(NODE_B1, portB1, dataB1,
				List.of(ReplTestSupport.peer(NODE_A1, DC_A, portA1),
						ReplTestSupport.peer(NODE_A2, DC_A, portA2),
						ReplTestSupport.peer(NODE_B2, DC_B, portB2)));
		final GridConfigurationProperties propsB2 = holdProps(NODE_B2, portB2, dataB2,
				List.of(ReplTestSupport.peer(NODE_A1, DC_A, portA1),
						ReplTestSupport.peer(NODE_A2, DC_A, portA2),
						ReplTestSupport.peer(NODE_B1, DC_B, portB1)));
		propsB2.getReplication().getRegion().setClaimTimeoutMs(TimeUnit.HOURS.toMillis(1L));

		final ReplicationCoordinator a1 = new ReplicationCoordinator(propsA1);
		final ReplicationCoordinator a2 = new ReplicationCoordinator(propsA2);
		final ReplicationCoordinator b1 = new ReplicationCoordinator(propsB1);
		final ReplicationCoordinator b2 = new ReplicationCoordinator(propsB2);
		final SqlEngine engineA1 = new SqlEngine(new TableCatalog(tempDir.resolve("a1-cat")), a1, SHARDS);
		final SqlEngine engineA2 = new SqlEngine(new TableCatalog(tempDir.resolve("a2-cat")), a2, SHARDS);
		final SqlEngine engineB1 = new SqlEngine(new TableCatalog(tempDir.resolve("b1-cat")), b1, SHARDS);
		final SqlEngine engineB2 = new SqlEngine(new TableCatalog(tempDir.resolve("b2-cat")), b2, SHARDS);

		final List<String> ackedTokens = new ArrayList<>();
		try {
			a1.start();
			a2.start();
			b1.start();
			b2.start();
			waitActiveWriter(a1, a2, SYNC_TIMEOUT_MS);

			final SqlEngine activeEngine = a1.isWriterEligible() ? engineA1 : engineA2;
			executeUntilSynced(activeEngine,
					"CREATE TABLE " + TABLE + " (id INT PRIMARY KEY, number VARCHAR, status VARCHAR)",
					CATALOG_TIMEOUT_MS);
			waitCatalog(engineA1, engineA2, engineB1, engineB2, TABLE, CATALOG_TIMEOUT_MS);
			executeUntilSynced(activeEngine,
					"INSERT INTO " + TABLE + " (id, number, status) VALUES (" + KEY_ID + ", '', 'seed')",
					CATALOG_TIMEOUT_MS);

			for (int i = 0; i < ACKED_TOKENS; i++) {
				jepsenAppendAcked(activeEngine, "t" + i, ackedTokens);
			}
			assertTrue(!ackedTokens.isEmpty(), "must ack tokens before kill-dc-a");

			// 2011-r1: kill Active, no revive in this assertion window.
			a1.isolatePeer(NODE_A2);
			a2.isolatePeer(NODE_A1);
			b1.isolatePeer(NODE_A1);
			b1.isolatePeer(NODE_A2);
			b2.isolatePeer(NODE_A1);
			b2.isolatePeer(NODE_A2);
			a1.isolatePeer(NODE_B1);
			a1.isolatePeer(NODE_B2);
			a2.isolatePeer(NODE_B1);
			a2.isolatePeer(NODE_B2);
			SqlBenchHelper.closeAllStores(engineA1);
			SqlBenchHelper.closeAllStores(engineA2);
			stopQuietly(a1);
			stopQuietly(a2);

			waitHoldClaimedActive(b1, CLAIM_TIMEOUT_MS);

			final String leak = observeEligibleWhileActiveDown(b1, b2, FENCE_WINDOW_MS);
			assertTrue(leak == null,
					() -> "2011-r1 class: Hold writerEligible while Active DC down: " + leak
							+ " tipB1=" + b1.getOrchidNode().getLastCommittedSeq()
							+ " tipB2=" + b2.getOrchidNode().getLastCommittedSeq()
							+ " maxSeenB1=" + b1.getOrchidNode().maxSeenPeerCommittedSeq()
							+ " awaitTipB1=" + b1.getOrchidNode().awaitsPeerTipAdvertisement()
							+ " roleB1=" + b1.regionRoleWire());
		} finally {
			SqlBenchHelper.closeAllStores(engineA1);
			SqlBenchHelper.closeAllStores(engineA2);
			SqlBenchHelper.closeAllStores(engineB1);
			SqlBenchHelper.closeAllStores(engineB2);
			stopQuietly(a1);
			stopQuietly(a2);
			stopQuietly(b1);
			stopQuietly(b2);
		}
	}

	/**
	 * Returns detail if Hold becomes writerEligible while Active remains down.
	 * Active coordinators are stopped by the test; window is the 2011-r1 leak window.
	 */
	private static String observeEligibleWhileActiveDown(
			ReplicationCoordinator b1,
			ReplicationCoordinator b2,
			long windowMs) {
		final long deadline = System.currentTimeMillis() + windowMs;
		while (System.currentTimeMillis() < deadline) {
			if (b1.isWriterEligible()) {
				return "b1.eligible tip=" + b1.getOrchidNode().getLastCommittedSeq()
						+ " maxSeen=" + b1.getOrchidNode().maxSeenPeerCommittedSeq()
						+ " awaitTip=" + b1.getOrchidNode().awaitsPeerTipAdvertisement()
						+ " proposer=" + b1.getOrchidNode().isPhaseRankedProposer();
			}
			if (b2.isWriterEligible()) {
				return "b2.eligible tip=" + b2.getOrchidNode().getLastCommittedSeq()
						+ " maxSeen=" + b2.getOrchidNode().maxSeenPeerCommittedSeq();
			}
			LockSupport.parkNanos(PARK_NANOS);
		}
		return null;
	}

	/**
	 * Leak window after Active revive: eligible while no live remote-DC tip is advertised yet
	 * (PR13-M clear-on-Hold-tip class).
	 */
	private static String observeEligibleWhileRemoteDcTipMissing(
			ReplicationCoordinator b1,
			ReplicationCoordinator b2,
			long windowMs) {
		final long deadline = System.currentTimeMillis() + windowMs;
		while (System.currentTimeMillis() < deadline) {
			final long remoteB1 = b1.getOrchidNode().maxSeenLiveRemoteDcPeerCommittedSeq();
			final long remoteB2 = b2.getOrchidNode().maxSeenLiveRemoteDcPeerCommittedSeq();
			if (b1.isWriterEligible() && remoteB1 <= 0L) {
				return "b1.eligible remoteDcTip=0 tip=" + b1.getOrchidNode().getLastCommittedSeq()
						+ " maxSeen=" + b1.getOrchidNode().maxSeenPeerCommittedSeq();
			}
			if (b2.isWriterEligible() && remoteB2 <= 0L) {
				return "b2.eligible remoteDcTip=0 tip=" + b2.getOrchidNode().getLastCommittedSeq()
						+ " maxSeen=" + b2.getOrchidNode().maxSeenPeerCommittedSeq();
			}
			LockSupport.parkNanos(PARK_NANOS);
		}
		return null;
	}

	private static GridConfigurationProperties activeProps(
			String nodeId, int port, Path dataDir,
			List<GridConfigurationProperties.ReplicationPeerProps> peers) {
		final GridConfigurationProperties props = ReplTestSupport.props(nodeId, CLUSTER, DC_A, port, dataDir, peers);
		configureCrossDc(props);
		props.getReplication().getRegion().setEnabled(true);
		props.getReplication().getRegion().setRole(RegionRole.ACTIVE);
		props.getReplication().getRegion().setEpoch(BOOT_EPOCH);
		props.getReplication().getRegion().setQuorumSize(REGION_QUORUM_SOLO);
		props.getReplication().getRegion().setClaimTimeoutMs(CLAIM_SILENCE_MS);
		return props;
	}

	private static GridConfigurationProperties holdProps(
			String nodeId, int port, Path dataDir,
			List<GridConfigurationProperties.ReplicationPeerProps> peers) {
		final GridConfigurationProperties props = ReplTestSupport.props(nodeId, CLUSTER, DC_B, port, dataDir, peers);
		configureCrossDc(props);
		props.getReplication().getRegion().setEnabled(true);
		props.getReplication().getRegion().setRole(RegionRole.HOLD);
		props.getReplication().getRegion().setEpoch(BOOT_EPOCH);
		props.getReplication().getRegion().setQuorumSize(REGION_QUORUM_SOLO);
		props.getReplication().getRegion().setClaimTimeoutMs(CLAIM_SILENCE_MS);
		return props;
	}

	private static void configureCrossDc(GridConfigurationProperties props) {
		props.getReplication().getCrossDc().setMode(CrossDcMode.ASYNC_SHIP.name());
		props.getReplication().getCrossDc().setPhaseCoupling(false);
		props.getReplication().getCrossDc().setWriteAdmission(true);
		props.getReplication().getCrossDc().setLearners(List.of(NODE_B1, NODE_B2));
		props.getReplication().getHa().setMaxStaleLag(10_000L);
	}

	private static void jepsenAppendAcked(SqlEngine engine, String token, List<String> acked) {
		try {
			final SqlSession session = engine.newSession();
			engine.execute(session, "BEGIN");
			final SqlResult updated = engine.execute(session,
					"UPDATE " + TABLE + " SET number = number || ' ' || '" + token
							+ "', status = 'jepsen-append' WHERE id = " + KEY_ID);
			if (updated.rowsAffected() == 0L) {
				engine.execute(session,
						"INSERT INTO " + TABLE + " (id, number, status) VALUES ("
								+ KEY_ID + ", '" + token + "', 'jepsen-append')");
			}
			engine.execute(session, "COMMIT");
			acked.add(token);
		} catch (OrchidNotSyncedException ignored) {
		} catch (RuntimeException ex) {
			if (!isOrchid(ex)) {
				throw ex;
			}
		}
	}

	private static void waitActiveWriter(ReplicationCoordinator a1, ReplicationCoordinator a2, long timeoutMs) {
		final long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			if ((a1.isWriterEligible() && a1.getOrchidNode().isPhaseRankedProposer())
					|| (a2.isWriterEligible() && a2.getOrchidNode().isPhaseRankedProposer())) {
				return;
			}
			LockSupport.parkNanos(PARK_NANOS);
		}
		fail("no Active writerEligible");
	}

	private static void waitHoldClaimedActive(ReplicationCoordinator hold, long timeoutMs) {
		final long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			if (hold.regionRoleWire() == RegionRole.ACTIVE.wireCode()) {
				return;
			}
			LockSupport.parkNanos(PARK_NANOS);
		}
		fail("Hold never claimed Active role=" + hold.regionRoleWire());
	}

	private static void waitCatalog(SqlEngine a, SqlEngine b, SqlEngine c, SqlEngine d, String table, long timeoutMs) {
		final long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			if (a.catalog().exists(table) && b.catalog().exists(table)
					&& c.catalog().exists(table) && d.catalog().exists(table)) {
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
			} catch (OrchidNotSyncedException ex) {
				last = ex;
				LockSupport.parkNanos(PARK_NANOS);
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
		fail("executeUntilSynced timeout");
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
