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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Restart mid-load: seal + hydrate restores map (OpLog may truncate through seal watermark).
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public class RestartMidLoadIT {

	@TempDir(cleanup = CleanupMode.NEVER)
	Path tempDir;

	@Test
	void restartHydratesCommittedOps() throws Exception {
		final int port = freePort();
		final Path data = tempDir.resolve("node");
		final GridConfigurationProperties props = ReplTestSupport.props(
				"mid-a", "mid-load", "dc-a", port, data, List.of()
		);
		final ReplicationCoordinator first = new ReplicationCoordinator(props);
		final GridScalableMap map1 = new GridScalableMap();
		final GridEntriesProcessor proc1 = new GridEntriesProcessor(0, map1, null, null);
		first.registerDomain("chaos.Mid", ReplTestSupport.singleShard(proc1), true);
		first.start();
		first.ensureOrchidSynced();

		for (int i = 0; i < 20; i++) {
			final byte[] key = new byte[]{(byte) i};
			final byte[] value = new byte[]{(byte) (i + 10)};
			final ReplicationOp op = OpLogCodec.withChecksum(new ReplicationOp(
					"chaos.Mid", 0, i + 1L, ReplicationOpType.UPSERT, key, value, 1L, 0L
			));
			final long seq = first.getOrchidNode().appendAndWaitCommit(op).get(5, TimeUnit.SECONDS);
			final ReplicationOp committed = OpLogCodec.withChecksum(new ReplicationOp(
					"chaos.Mid", 0, seq, ReplicationOpType.UPSERT, key, value, 1L, 0L
			));
			first.getOpLog().append(committed);
			first.getOrchidNode().confirmPersisted(seq);
			proc1.installCommitted(key, value, false);
			first.getNodeState().advanceApplied("chaos.Mid", 0, seq);
		}
		first.dumpDomainSnapshot("chaos.Mid");
		first.stop();

		final GridConfigurationProperties props2 = ReplTestSupport.props(
				"mid-a", "mid-load", "dc-a", freePort(), data, List.of()
		);
		final ReplicationCoordinator second = new ReplicationCoordinator(props2);
		final GridScalableMap map2 = new GridScalableMap();
		final GridEntriesProcessor proc2 = new GridEntriesProcessor(0, map2, null, null);
		second.registerDomain("chaos.Mid", ReplTestSupport.singleShard(proc2), true);
		second.start();
		final long lastSeq = second.getOpLog().lastSeq("chaos.Mid", 0);
		assertTrue(lastSeq >= 20 || proc2.get(new byte[]{0}) != null,
				"durable prefix via OpLog and/or sealed hydrate; lastSeq=" + lastSeq);
		assertNotNull(proc2.get(new byte[]{0}));
		assertArrayEquals(new byte[]{10}, proc2.get(new byte[]{0}));
		assertArrayEquals(new byte[]{29}, proc2.get(new byte[]{19}));

		second.stop();
	}

	private static int freePort() throws Exception {
		try (ServerSocket s = new ServerSocket(0)) {
			return s.getLocalPort();
		}
	}
}