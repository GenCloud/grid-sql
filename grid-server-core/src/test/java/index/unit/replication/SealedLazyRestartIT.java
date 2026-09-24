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
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.replication.snapshot.sealed.SealedGridMapWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LAZY hydrate: sealed mmap without full map preload; PK get via miss path.
 *
 * @author: GenCloud
 * @date: 2026/05
 * @since: 1.0
 */
class SealedLazyRestartIT {
	@TempDir
	Path tmp;

	@Test
	void lazyOpenSealedThenPkGet() throws Exception {
		final GridConfigurationProperties props = new GridConfigurationProperties();
		props.getDurability().setEnabled(true);
		props.getDurability().setHydrateMode("LAZY");
		props.getReplication().setEnabled(false);
		props.getReplication().getOpLog().setDataDir(tmp.resolve("repl").toString());
		props.getReplication().setClusterId("c1");
		props.getReplication().setNodeId("n1");

		final ReplicationCoordinator coord = new ReplicationCoordinator(props);
		final GridEntriesProcessor proc = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
		final String domain = "lazy_tbl";
		coord.registerDomain(domain, shard -> shard == 0 ? proc : null, true);

		final byte[] key = "pk1".getBytes(StandardCharsets.UTF_8);
		final byte[] val = "row1".getBytes(StandardCharsets.UTF_8);
		final Path sealedRoot = Path.of(
				props.getReplication().getOpLog().getDataDir(),
				props.getReplication().getClusterId(),
				props.getReplication().getNodeId())
				.resolve("sealed");
		SealedGridMapWriter.writeNodes(sealedRoot, domain, 0, 7L,
				List.of(new SealedGridMapWriter.Kv(key, val)));

		assertEquals(0, proc.mapSize());
		coord.ensureShardHydrated(domain, 0);
		assertTrue(proc.mapSize() <= 1, "LAZY must not preload full sealed into map");

		final byte[] got = proc.getCommitted(key);
		assertArrayEquals(val, got);
		assertTrue(proc.mapSize() >= 1);

		coord.stop();
	}
}
