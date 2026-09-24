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
package index.unit.replication;

import org.genfork.grid.context.config.GridConfigurationProperties;
import org.genfork.grid.mem.GridScalableMap;
import org.genfork.grid.mem.stage.GridEntriesProcessor;
import org.genfork.grid.mem.stage.GridEntriesProcessor.AddEntry;
import org.genfork.grid.replication.MutationRecorder;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0 IT: restart hydrate + fail-closed map/ORCHID (no orphan on consensus fail).
 */
public class RestartHydrateAndAtomicityIT {

	@TempDir
	Path tempDir;

	@Test
	void restartHydratesMapFromOpLog() throws Exception {
		final int port = freePort();
		final Path dataDir = tempDir.resolve("hydrate-node");
		final String domain = "demo.Hydrate";

		final ReplicationCoordinator first = new ReplicationCoordinator(ReplTestSupport.props(
				"h1", "hydrate", "dc-a", port, dataDir, List.of()
		));
		final GridEntriesProcessor proc1 = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
		final MutationRecorder recorder = first.registerDomain(domain, ReplTestSupport.singleShard(proc1), true);
		proc1.setCommitListener((shard, entry) -> recorder.recordCommittedBlocking(shard, entry));
		first.start();

		final byte[] key = new byte[]{42};
		final byte[] value = new byte[]{1, 2, 3};
		proc1.process(List.of(new AddEntry(null, key, value)));
		assertArrayEquals(value, proc1.get(key));
		assertTrue(first.getOpLog().lastSeq(domain, 0) >= 1);
		first.stop();

		final ReplicationCoordinator second = new ReplicationCoordinator(ReplTestSupport.props(
				"h1", "hydrate", "dc-a", freePort(), dataDir, List.of()
		));
		final GridEntriesProcessor proc2 = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
		assertNull(proc2.get(key));
		second.registerDomain(domain, ReplTestSupport.singleShard(proc2), true);
		assertArrayEquals(value, proc2.get(key));
		second.stop();
	}

	@Test
	void orchidFailLeavesNoOrphanInMap() throws Exception {
		final GridEntriesProcessor proc = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
		final byte[] key = new byte[]{7};
		final byte[] value = new byte[]{9};

		proc.setCommitListener((shard, entry) -> {
			throw new IllegalStateException("ORCHID commit refused");
		});
		// Fail-closed: listener failure re-queues staging and skips map put (no throw to caller).
		proc.process(List.of(new AddEntry(null, key, value)));
		assertNull(proc.get(key));
	}

	private static int freePort() throws Exception {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}
}
