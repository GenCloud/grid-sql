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

import org.genfork.grid.replication.snapshot.sealed.SealedShardPack;
import org.genfork.grid.replication.util.SealedIntegritySensorUtil;
import org.genfork.grid.replication.util.SealedIntegritySensorUtil.Probe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Single-DirectoryStream {@link SealedShardPack#listShardFiles} contract.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
class SealedShardPackListOneStreamTest {
	private static final String DOMAIN = "sensor_list_dom";
	private static final int SHARD = 3;

	@TempDir
	Path tempDir;

	@Test
	void listsGmapSbptSbmInOrder() throws Exception {
		final String hex = SealedShardPack.domainHex(DOMAIN);
		final String prefix = hex + "_" + SHARD;
		Files.write(tempDir.resolve(prefix + "_n1.gmap"), new byte[] {1});
		Files.write(tempDir.resolve(prefix + "_n0.gmap"), new byte[] {1});
		Files.write(tempDir.resolve(prefix + "_idx_id.sbpt"), new byte[] {1});
		Files.write(tempDir.resolve(prefix + "_idx_bm.sbm"), new byte[] {1});
		Files.write(tempDir.resolve(prefix + "_noise.tmp"), new byte[] {1});

		final List<Path> files = SealedShardPack.listShardFiles(tempDir, DOMAIN, SHARD);
		assertEquals(4, files.size());
		assertTrue(files.get(0).getFileName().toString().endsWith("_n0.gmap"));
		assertTrue(files.get(1).getFileName().toString().endsWith("_n1.gmap"));
		assertTrue(files.get(2).getFileName().toString().endsWith(".sbpt"));
		assertTrue(files.get(3).getFileName().toString().endsWith(".sbm"));
	}

	@Test
	void probeListedMatchesProbeAll() throws Exception {
		final String hex = SealedShardPack.domainHex(DOMAIN);
		final Path gmap = tempDir.resolve(hex + "_" + SHARD + "_n0.gmap");
		final byte[] header = new byte[SealedIntegritySensorUtil.GMAP_FIXED_HEADER_BYTES];
		Files.write(gmap, header);
		final List<Path> listed = SealedShardPack.listShardFiles(tempDir, DOMAIN, SHARD);
		assertEquals(
				SealedIntegritySensorUtil.probeAllGmapHeaders(tempDir, DOMAIN, SHARD),
				SealedIntegritySensorUtil.probeAllGmapHeadersListed(listed));
		assertTrue(SealedIntegritySensorUtil.needsPeerPack(
				SealedIntegritySensorUtil.probeAllGmapHeadersListed(listed)));
		assertEquals(Probe.MISSING, SealedIntegritySensorUtil.probeAllGmapHeadersListed(List.of()));
	}
}
