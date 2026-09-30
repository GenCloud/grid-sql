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
import org.genfork.grid.context.config.GridConfigurationProperties;
import org.genfork.grid.mem.GridScalableMap;
import org.genfork.grid.mem.stage.GridEntriesProcessor;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.orchid.FileDurableOrchidStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpLog prefix intact while {@code orchid/state.bin} tip is wiped/lagging — reopen hydrates
 * from OpLog, advances tip, and admits a successor commit without fork.
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
public class OrchidTipBehindRestartIT {

	private static final String DOMAIN = "chaos.TipBehind";
	private static final String CLUSTER = "tipb";
	private static final String NODE = "tipb-1";

	@TempDir(cleanup = CleanupMode.NEVER)
	Path tempDir;

	@Test
	void tipWipedOpLogSurvivesReopenAndCatchUp() throws Exception {
		final Path dataRoot = tempDir.resolve("solo");
		final int port = freePort();
		final GridConfigurationProperties props = ReplTestSupport.safetyProps(
				NODE, CLUSTER, "dc-a", port, dataRoot, List.of()
		);
		props.getReplication().getOrchid().setOrderThreshold(0.0);

		final GridScalableMap map1 = new GridScalableMap();
		final GridEntriesProcessor proc1 = new GridEntriesProcessor(0, map1, null, null);
		final ReplicationCoordinator c1 = new ReplicationCoordinator(props);
		c1.registerDomain(DOMAIN, ReplTestSupport.singleShard(proc1), true);
		c1.start();
		waitSynced(c1, 5_000);

		final byte[] key = new byte[]{4, 4};
		final byte[] value = new byte[]{5, 5, 5};
		final long seq = commitLocal(c1, proc1, key, value, 1L);
		c1.stop();

		final Path orchidDir = dataRoot.resolve(CLUSTER).resolve(NODE).resolve("orchid");
		assertTrue(Files.isDirectory(orchidDir), "orchid dir must exist after commit");
		try (FileDurableOrchidStore tipStore = new FileDurableOrchidStore(orchidDir, false)) {
			assertTrue(tipStore.loadLastCommittedSeq() >= seq);
		}
		// Simulate tip lag / wipe after OpLog force (crash between OpLog and tip persist).
		Files.deleteIfExists(orchidDir.resolve("state.bin"));

		final GridScalableMap map2 = new GridScalableMap();
		final GridEntriesProcessor proc2 = new GridEntriesProcessor(0, map2, null, null);
		final GridConfigurationProperties props2 = ReplTestSupport.safetyProps(
				NODE, CLUSTER, "dc-a", freePort(), dataRoot, List.of()
		);
		props2.getReplication().getOrchid().setOrderThreshold(0.0);
		final ReplicationCoordinator c2 = new ReplicationCoordinator(props2);
		c2.registerDomain(DOMAIN, ReplTestSupport.singleShard(proc2), true);
		c2.start();
		waitSynced(c2, 5_000);

		assertArrayEquals(value, proc2.get(key), "map must hydrate from OpLog despite wiped tip");
		assertTrue(c2.getOpLog().lastSeq(DOMAIN, 0) >= seq, "OpLog prefix must survive");

		// Catch tip up to OpLog and mint a successor — proves tip-behind does not fork the slot.
		c2.getOrchidNode().advanceCommittedTip(c2.getOpLog().lastSeq(DOMAIN, 0));
		final byte[] key2 = new byte[]{6, 6};
		final byte[] value2 = new byte[]{7, 7};
		final long seq2 = commitLocal(c2, proc2, key2, value2, seq + 1L);
		assertTrue(seq2 > seq);
		assertArrayEquals(value2, proc2.get(key2));
		assertTrue(c2.getOrchidNode().getLastCommittedSeq() >= seq2);
		c2.stop();
	}

	private static long commitLocal(ReplicationCoordinator coord,
	                                GridEntriesProcessor proc,
	                                byte[] key,
	                                byte[] value,
	                                long hintSeq) throws Exception {
		final ReplicationOp op = OpLogCodec.withChecksum(new ReplicationOp(
				DOMAIN, 0, hintSeq, ReplicationOpType.UPSERT, key, value, 1L, 0L
		));
		final long seq = coord.getOrchidNode().appendAndWaitCommit(op).get(5, TimeUnit.SECONDS);
		final ReplicationOp committed = OpLogCodec.withChecksum(new ReplicationOp(
				DOMAIN, 0, seq, ReplicationOpType.UPSERT, key, value, 1L, 0L
		));
		coord.getOpLog().append(committed);
		coord.getOrchidNode().confirmPersisted(seq);
		proc.installCommitted(key, value, false);
		coord.getNodeState().advanceApplied(DOMAIN, 0, seq);
		return seq;
	}

	private static void waitSynced(ReplicationCoordinator coord, long timeoutMs) throws InterruptedException {
		final long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline && !coord.getOrchidNode().isSynced()) {
			Thread.sleep(20);
		}
		assertTrue(coord.getOrchidNode().isSynced());
	}

	private static int freePort() throws Exception {
		try (ServerSocket s = new ServerSocket(0)) {
			return s.getLocalPort();
		}
	}
}
