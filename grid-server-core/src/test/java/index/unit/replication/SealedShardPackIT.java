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

import org.genfork.grid.replication.snapshot.sealed.SealedBPTreeReader;
import org.genfork.grid.replication.snapshot.sealed.SealedBPTreeWriter;
import org.genfork.grid.replication.snapshot.sealed.SealedGridMapWriter;
import org.genfork.grid.replication.snapshot.sealed.SealedShardPack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sealed shard pack includes {@code .sbpt} alongside GMAP for peer ship/repair.
 *
 * @author: GenCloud
 * @date: 2026/05
 * @since: 1.0
 */
class SealedShardPackIT {
	@TempDir
	Path temporary;

	@Test
	void packsAndUnpacksGmapWithSbptReadableBySealedBPTreeReader() throws Exception {
		final Path source = temporary.resolve("source");
		final Path target = temporary.resolve("target");
		Files.createDirectories(source);

		final String domain = "orders";
		final int shard = 2;
		final String hex = SealedShardPack.domainHex(domain);
		final byte[] rowKey = "row-1".getBytes(StandardCharsets.UTF_8);
		final byte[] rowVal = "payload".getBytes(StandardCharsets.UTF_8);
		final byte[] indexKey = "status-open".getBytes(StandardCharsets.UTF_8);

		SealedGridMapWriter.writeNodes(source, domain, shard, 9L,
				List.of(new SealedGridMapWriter.Kv(rowKey, rowVal)));
		final Path sbpt = source.resolve(hex + "_" + shard + "_idx_status.sbpt");
		SealedBPTreeWriter.write(sbpt, domain, shard, "status",
				List.of(new SealedBPTreeWriter.Entry(indexKey, rowKey)));

		final List<Path> listed = SealedShardPack.listShardFiles(source, domain, shard);
		assertTrue(listed.stream().anyMatch(p -> p.getFileName().toString().endsWith(".gmap")));
		assertTrue(listed.stream().anyMatch(p -> p.getFileName().toString().endsWith(".sbpt")));

		final byte[] packed = SealedShardPack.pack(source, domain, shard);
		assertTrue(packed.length > 12);

		final List<Path> written = SealedShardPack.unpack(packed, target);
		assertEquals(listed.size(), written.size());

		final Path peerSbpt = target.resolve(hex + "_" + shard + "_idx_status.sbpt");
		assertTrue(Files.isRegularFile(peerSbpt));
		try (SealedBPTreeReader reader = SealedBPTreeReader.open(peerSbpt)) {
			final List<byte[]> eq = reader.searchEq(indexKey);
			assertEquals(1, eq.size());
			assertArrayEquals(rowKey, eq.getFirst());
		}
	}
}
