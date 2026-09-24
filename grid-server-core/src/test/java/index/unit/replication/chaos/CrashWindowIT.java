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
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Crash window: durable OpLog+orchid seq survive stop/start; map hydrates without fork.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public class CrashWindowIT {

	@TempDir(cleanup = CleanupMode.NEVER)
	Path tempDir;

	@Test
	void crashAfterCommitHydratesSameSeq() throws Exception {
		final Path dataDir = tempDir.resolve("solo");
		final int port = freePort();
		final GridConfigurationProperties props = ReplTestSupport.safetyProps(
				"crash-1", "crash", "dc-a", port, dataDir, List.of()
		);
		props.getReplication().getOrchid().setOrderThreshold(0.0);

		final GridScalableMap map1 = new GridScalableMap();
		final GridEntriesProcessor proc1 = new GridEntriesProcessor(0, map1, null, null);
		ReplicationCoordinator c1 = new ReplicationCoordinator(props);
		c1.registerDomain("chaos.Crash", ReplTestSupport.singleShard(proc1), true);
		c1.start();

		final long deadline = System.currentTimeMillis() + 5_000;
		while (System.currentTimeMillis() < deadline && !c1.getOrchidNode().isSynced()) {
			Thread.sleep(20);
		}
		assertTrue(c1.getOrchidNode().isSynced());

		final byte[] key = new byte[]{4, 4};
		final byte[] value = new byte[]{5, 5, 5};
		final ReplicationOp op = OpLogCodec.withChecksum(new ReplicationOp(
				"chaos.Crash", 0, 1L, ReplicationOpType.UPSERT, key, value, 1L, 0L
		));
		final long seq = c1.getOrchidNode().appendAndWaitCommit(op).get(5, TimeUnit.SECONDS);
		final ReplicationOp committed = OpLogCodec.withChecksum(new ReplicationOp(
				op.domainType(), op.shard(), seq, op.type(), op.key(), op.value(), op.schemaEpoch(), 0L
		));
		c1.getOpLog().append(committed);
		c1.getOrchidNode().confirmPersisted(seq);
		proc1.installCommitted(key, value, false);
		c1.dumpDomainSnapshot("chaos.Crash");
		c1.stop();

		final GridScalableMap map2 = new GridScalableMap();
		final GridEntriesProcessor proc2 = new GridEntriesProcessor(0, map2, null, null);
		final GridConfigurationProperties props2 = ReplTestSupport.safetyProps(
				"crash-1", "crash", "dc-a", freePort(), dataDir, List.of()
		);
		props2.getReplication().getOrchid().setOrderThreshold(0.0);
		final ReplicationCoordinator c2 = new ReplicationCoordinator(props2);
		c2.registerDomain("chaos.Crash", ReplTestSupport.singleShard(proc2), true);
		c2.start();

		assertTrue(c2.getOrchidNode().getLastCommittedSeq() >= seq,
				"orchid seq must survive crash; was " + c2.getOrchidNode().getLastCommittedSeq());
		assertArrayEquals(value, proc2.get(key));
		c2.stop();
	}

	private static int freePort() throws Exception {
		try (ServerSocket s = new ServerSocket(0)) {
			return s.getLocalPort();
		}
	}
}
