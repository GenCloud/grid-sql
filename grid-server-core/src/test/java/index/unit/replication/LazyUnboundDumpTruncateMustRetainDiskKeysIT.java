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

import org.genfork.grid.fs.GridFs;
import org.genfork.grid.mem.GridScalableMap;
import org.genfork.grid.mem.stage.GridEntriesProcessor;
import org.genfork.grid.replication.ReplicationNodeState;
import org.genfork.grid.replication.log.OpLog;
import org.genfork.grid.replication.snapshot.sealed.SealedGridMapReader;
import org.genfork.grid.replication.snapshot.sealed.SealedGridMapService;
import org.genfork.grid.replication.snapshot.sealed.SealedShardPack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Periodic/at-risk dump ({@code truncateOpLog=true}) must still merge disk sealed when unbound.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
@DisplayName("LazyUnboundDumpTruncateMustRetainDiskKeys")
class LazyUnboundDumpTruncateMustRetainDiskKeysIT {
	private static final String DOMAIN = "lazy_unbound_trunc";
	private static final int SHARD_ZERO = 0;
	private static final byte[] KEY_SEALED_ONLY = "sealed-only".getBytes(StandardCharsets.UTF_8);
	private static final byte[] KEY_RAM_ONLY = "ram-only".getBytes(StandardCharsets.UTF_8);
	private static final byte[] VAL_SEALED = "from-sealed".getBytes(StandardCharsets.UTF_8);
	private static final byte[] VAL_RAM = "from-ram".getBytes(StandardCharsets.UTF_8);

	@TempDir
	Path tmp;

	@Test
	@DisplayName("dump_truncateTrue_withNullReader_keepsDiskSealedKeys")
	void dump_truncateTrue_withNullReader_keepsDiskSealedKeys() throws Exception {
		final Path sealedRoot = tmp.resolve("sealed");
		final Path oplogRoot = tmp.resolve("oplog");
		GridFs.createDirs(sealedRoot);
		GridFs.createDirs(oplogRoot);
		final OpLog opLog = new OpLog(oplogRoot, false);
		final ReplicationNodeState state = new ReplicationNodeState("n1", "c", "dc-a", 1L);
		final SealedGridMapService service = new SealedGridMapService(sealedRoot);
		final GridEntriesProcessor processor = new GridEntriesProcessor(
				SHARD_ZERO, new GridScalableMap(), null, null);
		service.bindProcessor(DOMAIN, shard -> shard == SHARD_ZERO ? processor : null);

		processor.installCommitted(KEY_SEALED_ONLY, VAL_SEALED, false);
		assertTrue(service.dumpDomain(DOMAIN, opLog, state, null, true) >= 1);
		assertTrue(SealedShardPack.hasMapNodes(sealedRoot, DOMAIN, SHARD_ZERO));

		service.closeAll();
		assertNull(service.reader(DOMAIN, SHARD_ZERO));

		service.bindProcessor(DOMAIN, shard -> shard == SHARD_ZERO ? processor : null);
		processor.evictCommitted(KEY_SEALED_ONLY);
		processor.installCommitted(KEY_RAM_ONLY, VAL_RAM, false);

		final int rows = service.dumpDomain(DOMAIN, opLog, state, null, true);
		assertEquals(2, rows);

		service.openShardLazy(DOMAIN, SHARD_ZERO);
		final SealedGridMapReader after = service.reader(DOMAIN, SHARD_ZERO);
		assertNotNull(after);
		assertArrayEquals(VAL_SEALED, after.get(KEY_SEALED_ONLY));
		assertArrayEquals(VAL_RAM, after.get(KEY_RAM_ONLY));
		service.closeAll();
		opLog.close();
	}
}
