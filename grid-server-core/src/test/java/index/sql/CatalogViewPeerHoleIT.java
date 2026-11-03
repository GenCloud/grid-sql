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
package index.sql;

import index.unit.replication.ReplTestSupport;
import org.genfork.grid.catalog.CatalogDdlCompactor;
import org.genfork.grid.catalog.CatalogFingerprintUtil;
import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.context.config.GridConfigurationProperties;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.replication.netty.codec.CatalogDdlSnapshotRpcCodec.CatalogDdlSnapshot;
import org.genfork.grid.replication.util.CatalogTruncateGateUtil;
import org.genfork.grid.sql.SqlEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * N=3 catalog VIEW hole: sealed/wm gap + compact journal reconcile restores ViewDef.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
public class CatalogViewPeerHoleIT {
	private static final String BASE = "cvph_base";
	private static final String VIEW = "cvph_rankers";
	private static final long SYNC_WAIT_MS = 15_000L;
	private static final long POLL_MS = 50L;

	@TempDir
	Path tempDir;

	@Test
	void journalReconcileHealsMissingViewAfterSealGap() throws Exception {
		assertFalse(CatalogTruncateGateUtil.allowTruncate(10L, 10L, 0L, 2),
				"multi-voter unknown ACK must deny _catalog truncate");
		assertTrue(CatalogTruncateGateUtil.allowTruncate(10L, 10L, 10L, 2));

		final int portA = freePort();
		final int portB = freePort();
		final int portC = freePort();
		final GridConfigurationProperties propsA = ReplTestSupport.props(
				"cvph-a", "cvph", "dc-a", portA, tempDir.resolve("a"),
				List.of(
						ReplTestSupport.peer("cvph-b", "dc-a", portB),
						ReplTestSupport.peer("cvph-c", "dc-a", portC)));
		final GridConfigurationProperties propsB = ReplTestSupport.props(
				"cvph-b", "cvph", "dc-a", portB, tempDir.resolve("b"),
				List.of(
						ReplTestSupport.peer("cvph-a", "dc-a", portA),
						ReplTestSupport.peer("cvph-c", "dc-a", portC)));
		final GridConfigurationProperties propsC = ReplTestSupport.props(
				"cvph-c", "cvph", "dc-a", portC, tempDir.resolve("c"),
				List.of(
						ReplTestSupport.peer("cvph-a", "dc-a", portA),
						ReplTestSupport.peer("cvph-b", "dc-a", portB)));

		final ReplicationCoordinator a = new ReplicationCoordinator(propsA);
		final ReplicationCoordinator b = new ReplicationCoordinator(propsB);
		final ReplicationCoordinator c = new ReplicationCoordinator(propsC);
		a.start();
		b.start();
		c.start();
		try {
			awaitLive(a, b, c);
			final Path catA = tempDir.resolve("a-cat");
			final Path catC = tempDir.resolve("c-cat");
			final SqlEngine engineA = new SqlEngine(new TableCatalog(catA), a, 4);
			final SqlEngine engineB = new SqlEngine(new TableCatalog(tempDir.resolve("b-cat")), b, 4);
			final SqlEngine engineC = new SqlEngine(new TableCatalog(catC), c, 4);

			final SqlEngine writer = a.isWriterEligible() ? engineA
					: (b.isWriterEligible() ? engineB : engineC);
			final SqlEngine holePeer = writer == engineC ? engineA : engineC;
			final Path holeCatDir = writer == engineC ? catA : catC;

			writer.execute("CREATE TABLE " + BASE + " (id INT PRIMARY KEY, score INT)");
			writer.execute(
					"CREATE VIEW " + VIEW
							+ " AS SELECT id, score, RANK() OVER (ORDER BY score DESC) AS rnk FROM " + BASE);
			awaitView(engineA, VIEW);
			awaitView(engineB, VIEW);
			awaitView(engineC, VIEW);

			// Simulate sealed gap hole: drop ViewDef while keeping high applied epoch (duplicate-skip trap).
			final long applied = holePeer.catalog().maxAppliedDdlEpoch();
			holePeer.catalog().dropView(VIEW, false);
			holePeer.catalog().noteAppliedDdlEpoch(Math.max(applied, 1L));
			assertNull(holePeer.catalog().getView(VIEW));
			holePeer.catalog().rewriteCompactedDdl();

			final long writerFp = CatalogFingerprintUtil.fingerprint(writer.catalog());
			final long holeFp = CatalogFingerprintUtil.fingerprint(holePeer.catalog());
			assertTrue(writerFp != holeFp, "fingerprints must diverge after VIEW hole");

			final List<String> compact = CatalogDdlCompactor.compactLines(writer.catalog());
			holePeer.applyCatalogJournalSnapshot(new CatalogDdlSnapshot(
					"writer", writerFp, holePeer.catalog().maxAppliedDdlEpoch(), compact));

			assertNotNull(holePeer.catalog().getView(VIEW), "reconcile must restore VIEW");
			final Path ddlFile = holeCatDir.resolve("catalog").resolve("ddl.sql");
			assertTrue(Files.exists(ddlFile), "expected compacted ddl at " + ddlFile);
			final String ddlBody = Files.readString(ddlFile);
			assertTrue(ddlBody.toLowerCase().contains(VIEW.toLowerCase()),
					"compacted ddl.sql must contain VIEW after reconcile");
		} finally {
			a.stop();
			b.stop();
			c.stop();
		}
	}

	private static void awaitLive(ReplicationCoordinator a, ReplicationCoordinator b, ReplicationCoordinator c)
			throws InterruptedException {
		final long deadline = System.currentTimeMillis() + SYNC_WAIT_MS;
		while (System.currentTimeMillis() < deadline
				&& (a.getOrchidNode().livePeerCount() < 2
				|| b.getOrchidNode().livePeerCount() < 2
				|| c.getOrchidNode().livePeerCount() < 2
				|| (!a.isWriterEligible() && !b.isWriterEligible() && !c.isWriterEligible()))) {
			Thread.sleep(POLL_MS);
		}
		assertTrue(a.getOrchidNode().livePeerCount() >= 2);
		assertTrue(a.isWriterEligible() || b.isWriterEligible() || c.isWriterEligible());
	}

	private static void awaitView(SqlEngine engine, String view) throws InterruptedException {
		final long deadline = System.currentTimeMillis() + SYNC_WAIT_MS;
		while (System.currentTimeMillis() < deadline && engine.catalog().getView(view) == null) {
			Thread.sleep(POLL_MS);
		}
		assertNotNull(engine.catalog().getView(view), "VIEW missing on peer: " + view);
	}

	private static int freePort() throws Exception {
		try (ServerSocket socket = new ServerSocket(0)) {
			socket.setReuseAddress(true);
			return socket.getLocalPort();
		}
	}
}
