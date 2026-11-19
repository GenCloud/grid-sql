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

import org.genfork.grid.replication.snapshot.sealed.SealedGridMapReader;
import org.genfork.grid.replication.snapshot.sealed.SealedGridMapService;
import org.genfork.grid.replication.snapshot.sealed.SealedShardPack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LAZY bind-fail contract is {@code .gmap} only — orphan {@code .sbpt} after empty-clear
 * must not count as sealed map (lab: player_extended_drop_item#5 TPS/app death).
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
class SealedMapNodesBindContractTest {

	private static final String DOMAIN = "shared_game_server.player_extended_drop_item";
	private static final int SHARD = 5;

	@TempDir
	Path tempDir;

	@Test
	void orphanSbptAloneIsNotSealedMapNodes() throws Exception {
		final Path sealed = tempDir.resolve("sealed");
		Files.createDirectories(sealed);
		final String hex = SealedShardPack.domainHex(DOMAIN);
		Files.writeString(sealed.resolve(hex + "_" + SHARD + "_idx_owner_id.sbpt"), "shell");
		Files.writeString(
				sealed.resolve(hex + "_" + SHARD + "_idx_owner_id+item_id.sbpt"), "shell");

		final SealedGridMapService service = new SealedGridMapService(sealed);
		assertTrue(service.hasSealedShardArtifacts(DOMAIN, SHARD), "sbpt still inventory artifacts");
		assertFalse(SealedShardPack.hasMapNodes(sealed, DOMAIN, SHARD));
		assertFalse(service.hasSealedMapNodes(DOMAIN, SHARD),
				"orphan .sbpt must not trip LAZY bind-fail / hot-path rehydrate");
		assertNull(SealedGridMapReader.openShard(sealed, DOMAIN, SHARD));
	}

	@Test
	void gmapNodeCountsAsSealedMap() throws Exception {
		final Path sealed = tempDir.resolve("sealed");
		Files.createDirectories(sealed);
		final String hex = SealedShardPack.domainHex(DOMAIN);
		Files.writeString(sealed.resolve(hex + "_" + SHARD + "_n0.gmap"), "node");

		final SealedGridMapService service = new SealedGridMapService(sealed);
		assertTrue(service.hasSealedMapNodes(DOMAIN, SHARD));
		assertTrue(SealedShardPack.hasMapNodes(sealed, DOMAIN, SHARD));
	}

	@Test
	void nonNumericNodeGlobIsMapNodeButUnopenable() throws Exception {
		final Path sealed = tempDir.resolve("sealed");
		Files.createDirectories(sealed);
		final String hex = SealedShardPack.domainHex(DOMAIN);
		// Glob _n*.gmap matches; openShard requires _n(\\d+) — bind-fail contract input.
		Files.writeString(sealed.resolve(hex + "_" + SHARD + "_nX.gmap"), "bad");

		assertTrue(SealedShardPack.hasMapNodes(sealed, DOMAIN, SHARD));
		assertNull(SealedGridMapReader.openShard(sealed, DOMAIN, SHARD));
	}
}
