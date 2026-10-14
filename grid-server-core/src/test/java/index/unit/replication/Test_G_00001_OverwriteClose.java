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
import org.genfork.grid.replication.snapshot.sealed.SealedBPTreeReader;
import org.genfork.grid.replication.snapshot.sealed.SealedBPTreeService;
import org.genfork.grid.replication.snapshot.sealed.SealedBPTreeWriter;
import org.genfork.grid.replication.snapshot.sealed.SealedGridMapReader;
import org.genfork.grid.replication.snapshot.sealed.SealedGridMapService;
import org.genfork.grid.replication.snapshot.sealed.SealedShardPack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IT-A/B/C: Windows REPLACE after {@code closeForOverwrite} (G-00001 fail-closed unmap).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
@DisplayName("Test_G_00001_OverwriteClose")
class Test_G_00001_OverwriteClose {
	private static final String DOMAIN_GMAP = "g00001_ow_gmap";
	private static final String DOMAIN_SBPT = "g00001_ow_sbpt";
	private static final String DOMAIN_DUMP = "g00001_ow_dump";
	private static final int SHARD = 0;
	private static final String INDEX = "id";
	private static final byte[] VALUE_V1 = "v1".getBytes(StandardCharsets.UTF_8);
	private static final byte[] VALUE_V2 = "v2".getBytes(StandardCharsets.UTF_8);
	private static final byte[] REPLACE_BYTES = "replaced".getBytes(StandardCharsets.UTF_8);

	@TempDir
	Path tmp;

	@Test
	@DisplayName("IT-A_gmap_closeForOverwrite_then_moveAtomic_doesNotThrow")
	void itA_gmapCloseForOverwriteThenMoveAtomic() throws Exception {
		final Path sealedRoot = tmp.resolve("ow-gmap");
		GridFs.createDirs(sealedRoot);
		final SealedGridMapService service = new SealedGridMapService(sealedRoot);
		final GridEntriesProcessor processor = new GridEntriesProcessor(
				SHARD, new GridScalableMap(), null, null);
		service.bindProcessor(DOMAIN_GMAP, shard -> shard == SHARD ? processor : null);
		final byte[] key = "ow-key".getBytes(StandardCharsets.UTF_8);
		processor.installCommitted(key, VALUE_V1, false);
		assertTrue(service.dumpDomain(DOMAIN_GMAP, null, null) > 0);

		final SealedGridMapReader live = service.reader(DOMAIN_GMAP, SHARD);
		assertNotNull(live);
		final Path target = firstGmap(sealedRoot, DOMAIN_GMAP);
		live.closeForOverwrite();

		final Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
		Files.write(temporary, REPLACE_BYTES);
		assertDoesNotThrow(() -> GridFs.moveAtomic(temporary, target));
		assertArrayEquals(REPLACE_BYTES, Files.readAllBytes(target));
		service.closeAll();
	}

	@Test
	@DisplayName("IT-B_sbpt_closeForOverwrite_then_moveAtomic_doesNotThrow")
	void itB_sbptCloseForOverwriteThenMoveAtomic() throws Exception {
		final Path sealedRoot = tmp.resolve("ow-sbpt");
		GridFs.createDirs(sealedRoot);
		final SealedBPTreeService service = new SealedBPTreeService(sealedRoot);
		final byte[] indexKey = "1".getBytes(StandardCharsets.UTF_8);
		final byte[] rowKey = "row-1".getBytes(StandardCharsets.UTF_8);
		service.dumpIndex(DOMAIN_SBPT, SHARD, INDEX,
				List.of(new SealedBPTreeWriter.Entry(indexKey, rowKey)));
		final SealedBPTreeReader live = service.open(DOMAIN_SBPT, SHARD, INDEX);
		assertNotNull(live);
		final Path target = service.indexFile(DOMAIN_SBPT, SHARD, INDEX);
		live.closeForOverwrite();

		final Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
		Files.write(temporary, REPLACE_BYTES);
		assertDoesNotThrow(() -> GridFs.moveAtomic(temporary, target));
		assertArrayEquals(REPLACE_BYTES, Files.readAllBytes(target));
		service.close();
	}

	@Test
	@DisplayName("IT-C_dump_unifiedRelease_doubleDumpAndUnpack_doesNotThrow")
	void itC_dumpUnifiedReleaseDoubleDumpAndUnpack() throws Exception {
		final Path sealedRoot = tmp.resolve("ow-dump");
		GridFs.createDirs(sealedRoot);
		final SealedGridMapService service = new SealedGridMapService(sealedRoot);
		final GridEntriesProcessor processor = new GridEntriesProcessor(
				SHARD, new GridScalableMap(), null, null);
		service.bindProcessor(DOMAIN_DUMP, shard -> shard == SHARD ? processor : null);
		final byte[] key = "dump-key".getBytes(StandardCharsets.UTF_8);
		processor.installCommitted(key, VALUE_V1, false);
		assertTrue(service.dumpDomain(DOMAIN_DUMP, null, null) > 0);
		assertNotNull(service.reader(DOMAIN_DUMP, SHARD));

		processor.installCommitted(key, VALUE_V2, false);
		assertDoesNotThrow(() -> service.dumpDomain(DOMAIN_DUMP, null, null));
		final SealedGridMapReader afterDump = service.reader(DOMAIN_DUMP, SHARD);
		assertNotNull(afterDump);
		assertArrayEquals(VALUE_V2, afterDump.get(key));

		final byte[] packed = SealedShardPack.pack(sealedRoot, DOMAIN_DUMP, SHARD);
		assertDoesNotThrow(() -> service.unpackShardArtifacts(DOMAIN_DUMP, SHARD, packed));
		service.openShardLazy(DOMAIN_DUMP, SHARD);
		assertArrayEquals(VALUE_V2, service.reader(DOMAIN_DUMP, SHARD).get(key));
		service.closeAll();
	}

	private static Path firstGmap(Path sealedRoot, String domain) throws Exception {
		final List<Path> files = SealedShardPack.listShardFiles(sealedRoot, domain, SHARD);
		for (Path file : files) {
			final String name = file.getFileName().toString();
			if (name.endsWith(".gmap")) {
				return file;
			}
		}
		throw new IllegalStateException("no .gmap for " + domain);
	}
}
