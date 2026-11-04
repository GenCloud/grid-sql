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

import org.genfork.grid.replication.snapshot.sealed.SealedBPTreeWriter;
import org.genfork.grid.replication.snapshot.sealed.SealedGridMapService;
import org.genfork.grid.replication.snapshot.sealed.SealedShardPack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Empty {@code .sbpt} shells must not satisfy {@link SealedGridMapService#hasUsableSealedIndex}.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
class SealedHasUsableIndexTest {

	private static final String DOMAIN = "usable_idx_dom";
	private static final String INDEX = "acct";
	private static final int SHARD = 0;

	@TempDir
	Path tempDir;

	@Test
	void emptyShellIsAbsentForHasUsable() throws Exception {
		final Path sealed = tempDir.resolve("sealed");
		Files.createDirectories(sealed);
		final SealedGridMapService service = new SealedGridMapService(sealed);
		final String hex = SealedShardPack.domainHex(DOMAIN);
		final Path emptyShell = sealed.resolve(hex + "_" + SHARD + "_idx_" + INDEX + ".sbpt");
		SealedBPTreeWriter.write(emptyShell, DOMAIN, SHARD, INDEX, List.of());

		assertFalse(service.hasUsableSealedIndex(DOMAIN, SHARD));
		assertFalse(service.hasAnySealedIndex(DOMAIN, SHARD));
	}

	@Test
	void nonEmptySbptIsUsable() throws Exception {
		final Path sealed = tempDir.resolve("sealed");
		Files.createDirectories(sealed);
		final SealedGridMapService service = new SealedGridMapService(sealed);
		final String hex = SealedShardPack.domainHex(DOMAIN);
		final Path healthy = sealed.resolve(hex + "_" + SHARD + "_idx_" + INDEX + ".sbpt");
		SealedBPTreeWriter.write(
				healthy,
				DOMAIN,
				SHARD,
				INDEX,
				List.of(new SealedBPTreeWriter.Entry(
						"acc1".getBytes(StandardCharsets.UTF_8),
						new byte[]{0, 0, 0, 1})));

		assertTrue(service.hasUsableSealedIndex(DOMAIN, SHARD));
		assertTrue(service.hasAnySealedIndex(DOMAIN, SHARD));
	}
}
