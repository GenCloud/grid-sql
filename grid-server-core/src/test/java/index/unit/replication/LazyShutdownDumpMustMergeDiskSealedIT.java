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
 * LAZY shutdown-style dump with no installed reader must merge on-disk {@code .gmap}.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
@DisplayName("LazyShutdownDumpMustMergeDiskSealed")
class LazyShutdownDumpMustMergeDiskSealedIT {
	private static final String DOMAIN = "lazy_shutdown_merge";
	private static final int SHARD_ZERO = 0;
	private static final byte[] KEY_SEALED_ONLY = "sealed-only".getBytes(StandardCharsets.UTF_8);
	private static final byte[] KEY_RAM_ONLY = "ram-only".getBytes(StandardCharsets.UTF_8);
	private static final byte[] VAL_SEALED = "from-sealed".getBytes(StandardCharsets.UTF_8);
	private static final byte[] VAL_RAM = "from-ram".getBytes(StandardCharsets.UTF_8);

	@TempDir
	Path tmp;

	@Test
	@DisplayName("dump_truncateFalse_withNullReader_keepsDiskSealedKeys")
	void dump_truncateFalse_withNullReader_keepsDiskSealedKeys() throws Exception {
		assertUnboundDumpRetainsDiskKey(false);
	}

	private void assertUnboundDumpRetainsDiskKey(boolean truncateOpLog) throws Exception {
		final Path sealedRoot = tmp.resolve(truncateOpLog ? "sealed-trunc" : "sealed-notrunc");
		GridFs.createDirs(sealedRoot);
		final SealedGridMapService service = new SealedGridMapService(sealedRoot);
		final GridEntriesProcessor processor = new GridEntriesProcessor(
				SHARD_ZERO, new GridScalableMap(), null, null);
		service.bindProcessor(DOMAIN, shard -> shard == SHARD_ZERO ? processor : null);

		processor.installCommitted(KEY_SEALED_ONLY, VAL_SEALED, false);
		assertTrue(service.dumpDomain(DOMAIN, null, null, null, truncateOpLog) >= 1);
		assertTrue(SealedShardPack.hasMapNodes(sealedRoot, DOMAIN, SHARD_ZERO));

		service.closeAll();
		assertNull(service.reader(DOMAIN, SHARD_ZERO), "reader unbound after closeAll (LAZY cold)");

		service.bindProcessor(DOMAIN, shard -> shard == SHARD_ZERO ? processor : null);
		processor.evictCommitted(KEY_SEALED_ONLY);
		processor.installCommitted(KEY_RAM_ONLY, VAL_RAM, false);

		final int rows = service.dumpDomain(DOMAIN, null, null, null, truncateOpLog);
		assertEquals(2, rows, "disk sealed key + RAM key must both survive null-reader dump");

		service.openShardLazy(DOMAIN, SHARD_ZERO);
		final SealedGridMapReader after = service.reader(DOMAIN, SHARD_ZERO);
		assertNotNull(after);
		assertArrayEquals(VAL_SEALED, after.get(KEY_SEALED_ONLY),
				"sealed-only key must not be wiped by LAZY unbound dump");
		assertArrayEquals(VAL_RAM, after.get(KEY_RAM_ONLY));
		service.closeAll();
	}
}
