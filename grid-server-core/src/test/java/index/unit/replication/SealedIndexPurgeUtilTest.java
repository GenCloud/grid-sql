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

import org.genfork.grid.replication.snapshot.sealed.SealedBPTreeService;
import org.genfork.grid.replication.snapshot.sealed.SealedBitmapService;
import org.genfork.grid.replication.snapshot.sealed.SealedCompositeIndexNames;
import org.genfork.grid.replication.snapshot.sealed.SealedIndexPurgeUtil;
import org.genfork.grid.replication.snapshot.sealed.SealedShardPack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sealed DROP INDEX purge deletes catalog-name and composite-column {@code .sbpt}.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
class SealedIndexPurgeUtilTest {
	private static final String DOMAIN = "shared_game_server.item_data";
	private static final String CATALOG = "item_data_owner_item_location_uindex";
	private static final List<String> COLS = List.of("owner_id", "item_id", "location_data");
	private static final int SHARD = 0;

	@TempDir
	Path tempDir;

	@Test
	void purgeRemovesCatalogAndCompositeSbpt() throws Exception {
		final Path sealedRoot = tempDir.resolve("sealed");
		Files.createDirectories(sealedRoot);
		final SealedBPTreeService bptree = new SealedBPTreeService(sealedRoot);
		final SealedBitmapService bitmap = new SealedBitmapService(sealedRoot);
		final String hex = SealedShardPack.domainHex(DOMAIN);
		final Path catalogFile = sealedRoot.resolve(
				hex + "_" + SHARD + "_idx_" + CATALOG.toLowerCase() + ".sbpt");
		final Path compositeFile = sealedRoot.resolve(
				hex + "_" + SHARD + "_idx_" + SealedCompositeIndexNames.of(COLS) + ".sbpt");
		Files.writeString(catalogFile, "x");
		Files.writeString(compositeFile, "y");
		assertTrue(Files.isRegularFile(catalogFile));
		assertTrue(Files.isRegularFile(compositeFile));

		SealedIndexPurgeUtil.purge(
				sealedRoot, DOMAIN, CATALOG, COLS, bptree, bitmap, Set.of(SHARD));

		assertFalse(Files.exists(catalogFile));
		assertFalse(Files.exists(compositeFile));
		bptree.close();
		bitmap.close();
	}
}
