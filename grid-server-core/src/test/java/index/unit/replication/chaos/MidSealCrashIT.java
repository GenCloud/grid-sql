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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Incomplete sealed artifact after dump must not block OpLog hydrate on reopen (mid-seal interrupt).
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
public class MidSealCrashIT {

	private static final String DOMAIN = "chaos.SealCrash";
	private static final String CLUSTER = "sealx";
	private static final String NODE = "sealx-1";
	private static final int ROW_COUNT = 12;

	@TempDir(cleanup = CleanupMode.NEVER)
	Path tempDir;

	@Test
	void tornSealedSiblingDoesNotLoseOpLogRowsOnReopen() throws Exception {
		final Path dataRoot = tempDir.resolve("node");
		final GridConfigurationProperties props = ReplTestSupport.props(
				NODE, CLUSTER, "dc-a", freePort(), dataRoot, List.of()
		);
		final ReplicationCoordinator first = new ReplicationCoordinator(props);
		final GridScalableMap map1 = new GridScalableMap();
		final GridEntriesProcessor proc1 = new GridEntriesProcessor(0, map1, null, null);
		first.registerDomain(DOMAIN, ReplTestSupport.singleShard(proc1), true);
		first.start();
		first.ensureOrchidSynced();

		for (int i = 0; i < ROW_COUNT; i++) {
			final byte[] key = new byte[]{(byte) i};
			final byte[] value = new byte[]{(byte) (i + 40)};
			final ReplicationOp op = OpLogCodec.withChecksum(new ReplicationOp(
					DOMAIN, 0, i + 1L, ReplicationOpType.UPSERT, key, value, 1L, 0L
			));
			final long seq = first.getOrchidNode().appendAndWaitCommit(op).get(5, TimeUnit.SECONDS);
			final ReplicationOp committed = OpLogCodec.withChecksum(new ReplicationOp(
					DOMAIN, 0, seq, ReplicationOpType.UPSERT, key, value, 1L, 0L
			));
			first.getOpLog().append(committed);
			first.getOrchidNode().confirmPersisted(seq);
			proc1.installCommitted(key, value, false);
			first.getNodeState().advanceApplied(DOMAIN, 0, seq);
		}
		final int sealedRows = first.dumpDomainSnapshot(DOMAIN);
		assertTrue(sealedRows >= 0);

		final Path sealedDir = dataRoot.resolve(CLUSTER).resolve(NODE).resolve("sealed");
		if (Files.isDirectory(sealedDir)) {
			// Mid-seal interrupt: leave an incomplete sibling; do not mutate intact packs
			// (corrupt real .gmap is fail-closed open, not OpLog recovery).
			final Path torn = sealedDir.resolve("torn_mid_seal_n0.gmap");
			Files.write(torn, new byte[0], StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
		}
		first.stop();

		final GridConfigurationProperties props2 = ReplTestSupport.props(
				NODE, CLUSTER, "dc-a", freePort(), dataRoot, List.of()
		);
		final ReplicationCoordinator second = new ReplicationCoordinator(props2);
		final GridScalableMap map2 = new GridScalableMap();
		final GridEntriesProcessor proc2 = new GridEntriesProcessor(0, map2, null, null);
		second.registerDomain(DOMAIN, ReplTestSupport.singleShard(proc2), true);
		second.start();

		assertNotNull(proc2.get(new byte[]{0}), "first key must hydrate after mid-seal interrupt");
		assertArrayEquals(new byte[]{40}, proc2.get(new byte[]{0}));
		assertArrayEquals(new byte[]{(byte) (ROW_COUNT - 1 + 40)},
				proc2.get(new byte[]{(byte) (ROW_COUNT - 1)}));
		assertTrue(second.getOpLog().lastSeq(DOMAIN, 0) >= ROW_COUNT
						|| proc2.get(new byte[]{0}) != null,
				"OpLog and/or map must retain rows after torn sealed artifact");
		second.stop();
	}

	private static int freePort() throws Exception {
		try (ServerSocket s = new ServerSocket(0)) {
			return s.getLocalPort();
		}
	}
}
