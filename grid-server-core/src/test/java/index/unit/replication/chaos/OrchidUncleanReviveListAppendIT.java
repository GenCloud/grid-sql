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
import org.genfork.grid.replication.codec.OpLogSegment;
import org.genfork.grid.replication.util.OpLogStreamKeyUtil;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Characterization for Jepsen unclean-revive Elle G0: SQL concat list-append must not lose
 * an acknowledged token after phase-ranked proposer kill (survivors keep writing).
 * <p>
 * Evidence stamp {@code 2026-09-29-jepsen-unclean-revive}: key 16 token {@code t164} was
 * {@code :ok} mid kill-window then permanently absent from later reads.
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
public class OrchidUncleanReviveListAppendIT {

	private static final String TABLE = "list_append";
	private static final String CLUSTER = "ula";
	private static final String NODE_A = "ula-a";
	private static final String NODE_B = "ula-b";
	private static final String NODE_C = "ula-c";
	private static final int KEY_ID = 16;
	private static final int APPEND_ROUNDS_BEFORE_KILL = 4;
	private static final int APPEND_ROUNDS_AFTER_KILL = 16;
	private static final long SYNC_TIMEOUT_MS = 15_000L;
	private static final long FAILOVER_TIMEOUT_MS = 12_000L;
	private static final long CATALOG_TIMEOUT_MS = 10_000L;
	private static final long PARK_NANOS = TimeUnit.MILLISECONDS.toNanos(50L);

	@TempDir(cleanup = CleanupMode.NEVER)
	Path tempDir;

	@Test
	void uncleanProposerKillDoesNotLoseAckedConcatTokens() throws Exception {
		final int portA = freePort();
		final int portB = freePort();
		final int portC = freePort();
		final GridConfigurationProperties propsA = ReplTestSupport.props(
				NODE_A, CLUSTER, "dc-a", portA, tempDir.resolve("a"),
				List.of(ReplTestSupport.peer(NODE_B, "dc-a", portB), ReplTestSupport.peer(NODE_C, "dc-a", portC)));
		final GridConfigurationProperties propsB = ReplTestSupport.props(
				NODE_B, CLUSTER, "dc-a", portB, tempDir.resolve("b"),
				List.of(ReplTestSupport.peer(NODE_A, "dc-a", portA), ReplTestSupport.peer(NODE_C, "dc-a", portC)));
		final GridConfigurationProperties propsC = ReplTestSupport.props(
				NODE_C, CLUSTER, "dc-a", portC, tempDir.resolve("c"),
				List.of(ReplTestSupport.peer(NODE_A, "dc-a", portA), ReplTestSupport.peer(NODE_B, "dc-a", portB)));

		final ReplicationCoordinator a = new ReplicationCoordinator(propsA);
		final ReplicationCoordinator b = new ReplicationCoordinator(propsB);
		final ReplicationCoordinator c = new ReplicationCoordinator(propsC);
		final SqlEngine engineA = new SqlEngine(new TableCatalog(tempDir.resolve("a-cat")), a, 4);
		final SqlEngine engineB = new SqlEngine(new TableCatalog(tempDir.resolve("b-cat")), b, 4);
		final SqlEngine engineC = new SqlEngine(new TableCatalog(tempDir.resolve("c-cat")), c, 4);

		final List<String> ackedTokens = new CopyOnWriteArrayList<>();
		final AtomicInteger dualEligibleHits = new AtomicInteger();
		try {
			a.start();
			b.start();
			c.start();
			waitThreeSyncedWithProposer(a, b, c, NODE_A, SYNC_TIMEOUT_MS);
			assertTrue(a.isWriterEligible(), "seed proposer must be writer-eligible");

			executeUntilSynced(engineA, "CREATE TABLE " + TABLE + " (id INT PRIMARY KEY, number VARCHAR)", CATALOG_TIMEOUT_MS);
			waitCatalog(engineA, engineB, engineC, TABLE, CATALOG_TIMEOUT_MS);
			executeUntilSynced(engineA, "INSERT INTO " + TABLE + " (id, number) VALUES (" + KEY_ID + ", '')", CATALOG_TIMEOUT_MS);

			for (int i = 0; i < APPEND_ROUNDS_BEFORE_KILL; i++) {
				appendAcked(engineA, "pre" + i, ackedTokens);
			}

			b.isolatePeer(NODE_A);
			c.isolatePeer(NODE_A);
			a.isolatePeer(NODE_B);
			a.isolatePeer(NODE_C);
			stopQuietly(a);
			b.isolatePeer(NODE_A);
			c.isolatePeer(NODE_A);
			shipSurvivorTipGap(b, c);
			waitTipsEqual(b, c, FAILOVER_TIMEOUT_MS);
			waitSurvivorProposer(b, c, NODE_B, FAILOVER_TIMEOUT_MS);

			for (int i = 0; i < APPEND_ROUNDS_AFTER_KILL; i++) {
				noteDualEligible(b, c, dualEligibleHits);
				final SqlEngine writer = phaseRankedWriter(b, c, engineB, engineC);
				if (writer == null) {
					LockSupport.parkNanos(PARK_NANOS);
					continue;
				}
				appendAcked(writer, "post" + i, ackedTokens);
			}

			waitSurvivorProposer(b, c, NODE_B, FAILOVER_TIMEOUT_MS);
			final SqlEngine reader = phaseRankedWriter(b, c, engineB, engineC);
			assertTrue(reader != null, "survivor must be writer-eligible for final read");
			final SqlResult row = reader.execute("SELECT number FROM " + TABLE + " WHERE id = " + KEY_ID);
			assertTrue(row.rows().size() == 1, "row must exist");
			final String number = String.valueOf(row.rows().get(0)[0]);
			final List<String> parts = number == null || number.isBlank()
					? List.of()
					: Arrays.asList(number.trim().split("\\s+"));
			final List<String> missing = new ArrayList<>();
			for (String token : ackedTokens) {
				if (!parts.contains(token)) {
					missing.add(token);
				}
			}
			assertTrue(missing.isEmpty(),
					() -> "lost ack'd concat tokens (G0): missing=" + missing
							+ " acked=" + ackedTokens
							+ " body=" + number
							+ " dualEligibleHits=" + dualEligibleHits.get()
							+ " b.proposer=" + b.getOrchidNode().getPhaseRankedProposerId()
							+ " c.proposer=" + c.getOrchidNode().getPhaseRankedProposerId());
		} finally {
			stopQuietly(a);
			stopQuietly(b);
			stopQuietly(c);
		}
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
				Throwable cur = ex;
				boolean orchid = false;
				while (cur != null) {
					if (cur instanceof OrchidNotSyncedException) {
						orchid = true;
						last = ex;
						break;
					}
					cur = cur.getCause();
				}
				if (!orchid) {
					throw ex;
				}
				LockSupport.parkNanos(PARK_NANOS);
			}
		}
		if (last != null) {
			throw last;
		}
		fail("executeUntilSynced timed out sql=" + sql);
	}

	private static void appendAcked(SqlEngine writer, String token, List<String> ackedTokens) {
		try {
			writer.execute("UPDATE " + TABLE + " SET number = number || ' ' || '" + token
					+ "' WHERE id = " + KEY_ID);
			ackedTokens.add(token);
		} catch (OrchidNotSyncedException fence) {
			// Fail-closed during flap is Elle-safe; only :ok tokens must remain visible.
		} catch (RuntimeException ex) {
			Throwable cur = ex;
			while (cur != null) {
				if (cur instanceof OrchidNotSyncedException) {
					return;
				}
				cur = cur.getCause();
			}
			throw ex;
		}
	}

	private static void noteDualEligible(ReplicationCoordinator b, ReplicationCoordinator c, AtomicInteger hits) {
		if (b.isWriterEligible() && c.isWriterEligible()) {
			hits.incrementAndGet();
		}
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

	private static void waitCatalog(SqlEngine a, SqlEngine b, SqlEngine c, String table, long timeoutMs) {
		final long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			if (a.catalog().exists(table) && b.catalog().exists(table) && c.catalog().exists(table)) {
				return;
			}
			LockSupport.parkNanos(PARK_NANOS);
		}
		fail("catalog did not replicate table=" + table
				+ " a=" + a.catalog().exists(table)
				+ " b=" + b.catalog().exists(table)
				+ " c=" + c.catalog().exists(table));
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
					&& a.getOrchidNode().isPhaseRankedProposer()
					&& a.isWriterEligible()) {
				return;
			}
			LockSupport.parkNanos(PARK_NANOS);
		}
		fail("expected proposer " + expectedProposer
				+ " a.prop=" + a.getOrchidNode().getPhaseRankedProposerId()
				+ " a.el=" + a.isWriterEligible()
				+ " a.R=" + a.getOrchidNode().orderParameterR()
				+ " b.R=" + b.getOrchidNode().orderParameterR()
				+ " c.R=" + c.getOrchidNode().orderParameterR());
	}

	private static void waitSurvivorProposer(
			ReplicationCoordinator b,
			ReplicationCoordinator c,
			String expectedProposer,
			long timeoutMs) {
		final long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			if (b.getOrchidNode().isSynced() && c.getOrchidNode().isSynced()
					&& b.getOrchidNode().liveLocalPeerCount() >= 1
					&& expectedProposer.equals(b.getOrchidNode().getPhaseRankedProposerId())
					&& b.getOrchidNode().isPhaseRankedProposer()
					&& b.isWriterEligible()) {
				return;
			}
			LockSupport.parkNanos(PARK_NANOS);
		}
		fail("expected survivor proposer " + expectedProposer
				+ " b.prop=" + b.getOrchidNode().getPhaseRankedProposerId()
				+ " c.prop=" + c.getOrchidNode().getPhaseRankedProposerId()
				+ " b.el=" + b.isWriterEligible()
				+ " c.el=" + c.isWriterEligible()
				+ " b.live=" + b.getOrchidNode().liveLocalPeerCount()
				+ " c.live=" + c.getOrchidNode().liveLocalPeerCount()
				+ " b.R=" + b.getOrchidNode().orderParameterR()
				+ " c.R=" + c.getOrchidNode().orderParameterR());
	}


	private static void shipSurvivorTipGap(ReplicationCoordinator b, ReplicationCoordinator c) {
		final long tipB = b.getOrchidNode().getLastCommittedSeq();
		final long tipC = c.getOrchidNode().getLastCommittedSeq();
		if (tipB == tipC) {
			return;
		}
		if (tipB > tipC) {
			shipAllStreams(b, NODE_C);
		} else {
			shipAllStreams(c, NODE_B);
		}
	}

	private static void shipAllStreams(ReplicationCoordinator ahead, String behindId) {
		for (String streamKey : ahead.getOpLog().streamKeys()) {
			final OpLogStreamKeyUtil.StreamKey parsed = OpLogStreamKeyUtil.parse(streamKey);
			if (parsed == null) {
				continue;
			}
			final List<ReplicationOp> ops = ahead.getOpLog().readFrom(parsed.domain(), parsed.shard(), 1L, 10_000);
			if (ops.isEmpty()) {
				continue;
			}
			ahead.getNettyTransport().pushSegment(behindId, new OpLogSegment(
					parsed.domain(), parsed.shard(),
					ops.getFirst().opSeq(), ops.getLast().opSeq(), ops,
					OpLogCodec.segmentChecksum(ops)));
		}
	}

	private static void waitTipsEqual(ReplicationCoordinator b, ReplicationCoordinator c, long timeoutMs) {
		final long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			if (b.getOrchidNode().getLastCommittedSeq() == c.getOrchidNode().getLastCommittedSeq()) {
				return;
			}
			shipSurvivorTipGap(b, c);
			LockSupport.parkNanos(PARK_NANOS);
		}
		fail("survivor tips diverged; b.tip=" + b.getOrchidNode().getLastCommittedSeq()
				+ " c.tip=" + c.getOrchidNode().getLastCommittedSeq());
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
		try (ServerSocket s = new ServerSocket(0)) {
			return s.getLocalPort();
		}
	}
}
