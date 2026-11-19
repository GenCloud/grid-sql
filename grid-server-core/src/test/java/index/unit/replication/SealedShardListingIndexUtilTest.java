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

import org.genfork.grid.replication.snapshot.sealed.SealedShardListingIndexUtil;
import org.genfork.grid.replication.snapshot.sealed.SealedShardPack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Durable sealed listing index vs DirectoryStream fallback.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
class SealedShardListingIndexUtilTest {
	private static final String DOMAIN = "listing_idx_t";
	private static final int SHARD = 2;

	@TempDir
	Path temp;

	@AfterEach
	void tearDown() {
		SealedShardListingIndexUtil.invalidate();
	}

	@Test
	void rebuildInstallLookupMatchesUnindexedOrder() throws Exception {
		final Path sealed = temp.resolve("sealed");
		Files.createDirectories(sealed);
		final String hex = SealedShardPack.domainHex(DOMAIN);
		Files.write(sealed.resolve(hex + "_" + SHARD + "_n1.gmap"), new byte[]{1, 2, 3});
		Files.write(sealed.resolve(hex + "_" + SHARD + "_n0.gmap"), new byte[]{4});
		Files.writeString(sealed.resolve(hex + "_" + SHARD + "_idx.sbpt"), "i");
		Files.writeString(sealed.resolve(hex + "_" + SHARD + "_bm.sbm"), "b");
		Files.writeString(sealed.resolve("noise.txt"), "x");

		final List<Path> unindexed = SealedShardListingIndexUtil.listShardFilesUnindexed(sealed, DOMAIN, SHARD);
		final int prefixes = SealedShardListingIndexUtil.rebuildAndInstall(sealed);
		assertTrue(prefixes >= 1);
		final List<Path> indexed = SealedShardListingIndexUtil.lookupShardFiles(sealed, DOMAIN, SHARD);
		assertNotNull(indexed);
		assertEquals(unindexed, indexed);
		assertEquals(unindexed, SealedShardPack.listShardFiles(sealed, DOMAIN, SHARD));
	}

	@Test
	void invalidateClearsLookup() throws Exception {
		final Path sealed = temp.resolve("sealed");
		Files.createDirectories(sealed);
		final String hex = SealedShardPack.domainHex(DOMAIN);
		Files.write(sealed.resolve(hex + "_" + SHARD + "_n0.gmap"), new byte[]{1});
		SealedShardListingIndexUtil.rebuildAndInstall(sealed);
		assertNotNull(SealedShardListingIndexUtil.lookupShardFiles(sealed, DOMAIN, SHARD));
		SealedShardListingIndexUtil.invalidate();
		assertNull(SealedShardListingIndexUtil.lookupShardFiles(sealed, DOMAIN, SHARD));
	}

	@Test
	void shardPrefixOfParsesNodeAndIndexNames() {
		assertEquals("aa_3", SealedShardListingIndexUtil.shardPrefixOf("aa_3_n0.gmap"));
		assertEquals("aa_3", SealedShardListingIndexUtil.shardPrefixOf("aa_3_idx.sbpt"));
		assertNull(SealedShardListingIndexUtil.shardPrefixOf("noise.txt"));
		assertNull(SealedShardListingIndexUtil.shardPrefixOf("aa_x_n0.gmap"));
	}
}
