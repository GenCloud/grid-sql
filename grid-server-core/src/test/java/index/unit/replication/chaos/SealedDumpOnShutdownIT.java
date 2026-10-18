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
import org.genfork.grid.replication.MutationRecorder;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.replication.ReplicationNodeState;
import org.genfork.grid.replication.apply.ReplicaApplier;
import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.log.OpLog;
import org.genfork.grid.replication.snapshot.sealed.SealedDumpSafetyUtil;
import org.genfork.grid.replication.snapshot.sealed.SealedGridMapService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Graceful stop runs sealed dump (bypass fsync-pressure); open-TX shards stay deferred.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
@DisplayName("SealedDumpOnShutdown")
public class SealedDumpOnShutdownIT {

	private static final String DOMAIN = "chaos.SealShutdown";
	private static final String CLUSTER = "seal-stop";
	private static final String NODE = "seal-stop-1";
	private static final int SHARD = 0;
	private static final long DUMP_INTERVAL_MS = 60_000L;
	private static final byte[] KEY = "item-1".getBytes(StandardCharsets.UTF_8);
	private static final byte[] VAL_A = "PAPER_DOLL".getBytes(StandardCharsets.UTF_8);
	private static final byte[] VAL_B = "INVENTORY".getBytes(StandardCharsets.UTF_8);
	private static final byte[] TX_KEY = "1".getBytes(StandardCharsets.UTF_8);

	@TempDir(cleanup = CleanupMode.NEVER)
	Path tempDir;

	@Test
	@DisplayName("stop_dumpsSealed_reopenKeepsAckedValue")
	void stop_dumpsSealed_reopenKeepsAckedValue() throws Exception {
		final Path dataRoot = tempDir.resolve("node");
		final GridConfigurationProperties props = ReplTestSupport.safetyProps(
				NODE, CLUSTER, "dc-a", freePort(), dataRoot, List.of()
		);
		props.getReplication().getOrchid().setOrderThreshold(0.0);
		props.getDurability().setHydrateMode("FULL");
		props.getDurability().setSealedDumpIntervalMs(DUMP_INTERVAL_MS);

		final GridEntriesProcessor proc = new GridEntriesProcessor(SHARD, new GridScalableMap(), null, null);
		final ReplicationCoordinator coord = new ReplicationCoordinator(props);
		final MutationRecorder recorder = coord.registerDomain(DOMAIN, ReplTestSupport.singleShard(proc), true);
		coord.start();
		waitSynced(coord, 5_000);
		assertEquals(DUMP_INTERVAL_MS, coord.sealedDumpIntervalMsForTest());

		recorder.recordCommittedBlocking(SHARD, new GridEntriesProcessor.AddEntry(null, KEY, VAL_A));
		recorder.recordCommittedBlocking(SHARD, new GridEntriesProcessor.AddEntry(null, KEY, VAL_B));
		assertArrayEquals(VAL_B, proc.get(KEY));
		coord.stop();

		assertTrue(hasSealedGmap(dataRoot), "graceful stop must produce sealed .gmap");

		final GridConfigurationProperties props2 = ReplTestSupport.safetyProps(
				NODE, CLUSTER, "dc-a", freePort(), dataRoot, List.of()
		);
		props2.getReplication().getOrchid().setOrderThreshold(0.0);
		props2.getDurability().setHydrateMode("FULL");
		final GridEntriesProcessor proc2 = new GridEntriesProcessor(SHARD, new GridScalableMap(), null, null);
		final ReplicationCoordinator coord2 = new ReplicationCoordinator(props2);
		coord2.registerDomain(DOMAIN, ReplTestSupport.singleShard(proc2), true);
		coord2.start();
		try {
			assertArrayEquals(VAL_B, proc2.get(KEY));
		} finally {
			coord2.stop();
		}
	}

	@Test
	@DisplayName("openTx_deferGate_skipsShardOnDump")
	void openTx_deferGate_skipsShardOnDump() throws Exception {
		final Path sealedRoot = tempDir.resolve("sealed");
		final Path oplogRoot = tempDir.resolve("oplog");
		Files.createDirectories(sealedRoot);
		Files.createDirectories(oplogRoot);

		final ReplicationNodeState state = new ReplicationNodeState(NODE, CLUSTER, "dc-a", 1L);
		final OpLog opLog = new OpLog(oplogRoot, false);
		final GridEntriesProcessor proc = new GridEntriesProcessor(SHARD, new GridScalableMap(), null, null);
		final SealedGridMapService sealed = new SealedGridMapService(sealedRoot);
		sealed.bindProcessor(DOMAIN, shard -> shard == SHARD ? proc : null);
		final ReplicaApplier applier = new ReplicaApplier(state, opLog, shard -> proc, null, true);

		applier.apply(OpLogCodec.withChecksum(new ReplicationOp(
				DOMAIN, SHARD, 1L, ReplicationOpType.UPSERT, KEY, VAL_A, 1L, 0L
		)), false);
		assertTrue(sealed.dumpDomain(DOMAIN, opLog, state, SealedDumpSafetyUtil.deferGate(applier)) > 0);

		applier.apply(OpLogCodec.withChecksum(new ReplicationOp(
				DOMAIN, SHARD, 2L, ReplicationOpType.TX_BEGIN, TX_KEY, null, 1L, 0L
		)), false);
		applier.apply(OpLogCodec.withChecksum(new ReplicationOp(
				DOMAIN, SHARD, 3L, ReplicationOpType.UPSERT, KEY, VAL_B, 1L, 0L
		)), false);
		assertEquals(0, sealed.dumpDomain(DOMAIN, opLog, state, SealedDumpSafetyUtil.deferGate(applier)),
				"open TX must defer sealed dump for shard");

		applier.apply(OpLogCodec.withChecksum(new ReplicationOp(
				DOMAIN, SHARD, 4L, ReplicationOpType.TX_COMMIT, TX_KEY, null, 1L, 0L
		)), false);
		assertTrue(sealed.dumpDomain(DOMAIN, opLog, state, SealedDumpSafetyUtil.deferGate(applier)) > 0);
		opLog.close();
		sealed.closeAll();
	}

	private boolean hasSealedGmap(Path dataRoot) throws Exception {
		final Path sealedDir = dataRoot.resolve(CLUSTER).resolve(NODE).resolve("sealed");
		if (!Files.isDirectory(sealedDir)) {
			return false;
		}
		try (Stream<Path> paths = Files.list(sealedDir)) {
			return paths.anyMatch(p -> p.getFileName().toString().endsWith(".gmap"));
		}
	}

	private static void waitSynced(ReplicationCoordinator coord, long timeoutMs) throws InterruptedException {
		final long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline && !coord.getOrchidNode().isSynced()) {
			Thread.sleep(20L);
		}
		assertTrue(coord.getOrchidNode().isSynced());
	}

	private static int freePort() throws Exception {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}
}
