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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dirty volume reopen: torn OpLog tail on disk, no purge — coordinator boots and keeps intact prefix.
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
public class DirtyVolumeCoordinatorReopenIT {

	private static final String DOMAIN = "chaos.Dirty";
	private static final String CLUSTER = "dirty";
	private static final String NODE = "dirty-1";
	private static final int TORN_ZERO_HEADER_BYTES = 8;

	@TempDir(cleanup = CleanupMode.NEVER)
	Path tempDir;

	@Test
	void tornTailReopenWithoutPurgeKeepsCommittedKey() throws Exception {
		final Path dataRoot = tempDir.resolve("node");
		final GridConfigurationProperties props = ReplTestSupport.safetyProps(
				NODE, CLUSTER, "dc-a", freePort(), dataRoot, List.of()
		);
		props.getReplication().getOrchid().setOrderThreshold(0.0);

		final GridScalableMap map1 = new GridScalableMap();
		final GridEntriesProcessor proc1 = new GridEntriesProcessor(0, map1, null, null);
		final ReplicationCoordinator c1 = new ReplicationCoordinator(props);
		c1.registerDomain(DOMAIN, ReplTestSupport.singleShard(proc1), true);
		c1.start();
		waitSynced(c1, 5_000);

		final byte[] key = new byte[]{1};
		final byte[] value = new byte[]{9};
		final ReplicationOp op = OpLogCodec.withChecksum(new ReplicationOp(
				DOMAIN, 0, 1L, ReplicationOpType.UPSERT, key, value, 1L, 0L
		));
		final long seq = c1.getOrchidNode().appendAndWaitCommit(op).get(5, TimeUnit.SECONDS);
		final ReplicationOp committed = OpLogCodec.withChecksum(new ReplicationOp(
				DOMAIN, 0, seq, ReplicationOpType.UPSERT, key, value, 1L, 0L
		));
		c1.getOpLog().append(committed);
		c1.getOrchidNode().confirmPersisted(seq);
		proc1.installCommitted(key, value, false);
		c1.getNodeState().advanceApplied(DOMAIN, 0, seq);
		final long lastSeq = c1.getOpLog().lastSeq(DOMAIN, 0);
		c1.stop();

		final Path oplogDir = dataRoot.resolve(CLUSTER).resolve(NODE).resolve("oplog");
		assertTrue(Files.isDirectory(oplogDir));
		Path logFile = null;
		try (Stream<Path> paths = Files.list(oplogDir)) {
			logFile = paths.filter(p -> p.getFileName().toString().endsWith(".log"))
					.findFirst()
					.orElse(null);
		}
		assertTrue(logFile != null && Files.exists(logFile), "expected OpLog .log under " + oplogDir);
		try (FileChannel ch = FileChannel.open(logFile, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
			final ByteBuffer buf = ByteBuffer.allocate(TORN_ZERO_HEADER_BYTES);
			buf.putInt(0);
			buf.putInt(0);
			buf.flip();
			ch.write(buf);
		}
		Files.writeString(Path.of(logFile + ".wpos"), Long.toString(Files.size(logFile)));

		final GridScalableMap map2 = new GridScalableMap();
		final GridEntriesProcessor proc2 = new GridEntriesProcessor(0, map2, null, null);
		final GridConfigurationProperties props2 = ReplTestSupport.safetyProps(
				NODE, CLUSTER, "dc-a", freePort(), dataRoot, List.of()
		);
		props2.getReplication().getOrchid().setOrderThreshold(0.0);
		final ReplicationCoordinator c2 = new ReplicationCoordinator(props2);
		c2.registerDomain(DOMAIN, ReplTestSupport.singleShard(proc2), true);
		c2.start();

		assertTrue(c2.getOpLog().lastSeq(DOMAIN, 0) >= lastSeq,
				"intact OpLog prefix must survive dirty reopen; last=" + c2.getOpLog().lastSeq(DOMAIN, 0));
		assertArrayEquals(value, proc2.get(key), "committed key must hydrate without purge");
		c2.stop();
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
