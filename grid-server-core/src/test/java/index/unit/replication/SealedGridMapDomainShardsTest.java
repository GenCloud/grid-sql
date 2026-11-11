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

import org.genfork.grid.replication.snapshot.sealed.SealedGridMapService;
import org.genfork.grid.replication.snapshot.sealed.SealedShardPack;
import org.genfork.grid.replication.util.OpLogStreamKeyUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Disk∪registered domain#shard enumeration via {@link SealedGridMapService#forEachDomainShards}.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
class SealedGridMapDomainShardsTest {

	private static final String DOMAIN = "inv_castle";
	private static final int SHARD = 3;
	private static final int HOLE_SHARD = 1;

	@TempDir
	Path tempDir;

	@Test
	void forEachDomainShardsEmitsUniqueDiskShards() throws Exception {
		final Path sealed = tempDir.resolve("sealed");
		Files.createDirectories(sealed);
		final String hex = SealedShardPack.domainHex(DOMAIN);
		Files.writeString(sealed.resolve(hex + "_" + SHARD + "_n0.gmap"), "x");
		Files.writeString(sealed.resolve(hex + "_" + SHARD + "_n1.gmap"), "y");
		Files.writeString(sealed.resolve(hex + "_" + HOLE_SHARD + "_n0.gmap"), "z");

		final SealedGridMapService service = new SealedGridMapService(sealed);
		final Set<String> seen = new HashSet<>();
		service.forEachDomainShards(List.of(DOMAIN), (domain, shard) ->
				seen.add(OpLogStreamKeyUtil.format(domain, shard)));

		assertEquals(2, seen.size());
		assertTrue(seen.contains(OpLogStreamKeyUtil.format(DOMAIN, SHARD)));
		assertTrue(seen.contains(OpLogStreamKeyUtil.format(DOMAIN, HOLE_SHARD)));
	}
}
