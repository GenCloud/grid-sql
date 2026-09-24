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

import org.genfork.grid.mem.index.IndexPointerRef;
import org.genfork.grid.mem.index.bitmap.GridBitmapIndex;
import org.genfork.grid.mem.index.btree.IndexOperationResult;
import org.genfork.grid.mem.index.btree.SingleTreeKey;
import org.genfork.grid.replication.snapshot.sealed.SealedBitmapService;
import org.genfork.grid.replication.snapshot.sealed.SealedShardPack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BITMAP sealed dump -> reload â†’ reload â†’ EQ query matches original postings.
 *
 * @author: GenCloud
 * @date: 2026/05
 * @since: 1.0
 */
public class SealedBitmapIT {
	@TempDir
	Path temporary;

	@Test
	void writeBitmapReloadQueryMatches() throws Exception {
		final String domain = "orders";
		final int shard = 0;
		final String property = "status";

		final GridBitmapIndex original = new GridBitmapIndex(property + "-BITMAP", 1 << 12);
		original.insert(key("open"), ptr("row-1"));
		original.insert(key("open"), ptr("row-2"));
		original.insert(key("closed"), ptr("row-3"));

		final SealedBitmapService service = new SealedBitmapService(temporary);
		service.dumpIndex(domain, shard, property, original);

		final Path file = service.indexFile(domain, shard, property);
		assertTrue(Files.isRegularFile(file));
		assertTrue(file.getFileName().toString().endsWith(".sbm"));

		final List<Path> listed = SealedShardPack.listShardFiles(temporary, domain, shard);
		assertTrue(listed.stream().anyMatch(p -> p.getFileName().toString().endsWith(".sbm")));

		final GridBitmapIndex hydrated = service.openIfPresent(domain, shard, property);
		assertNotNull(hydrated);

		assertKeys(hydrated, "open", "row-1", "row-2");
		assertKeys(hydrated, "closed", "row-3");
		assertEquals(0, hydrated.searchEq(key("missing")).getSize());
	}

	@Test
	void serializeDeserializeRoundTrip() {
		final GridBitmapIndex original = new GridBitmapIndex("flag-BITMAP", 256);
		original.insert(key("Y"), ptr("a"));
		original.insert(key("N"), ptr("b"));

		final GridBitmapIndex copy = GridBitmapIndex.deserialize("flag-BITMAP", original.serialize());
		assertKeys(copy, "Y", "a");
		assertKeys(copy, "N", "b");
	}

	private static void assertKeys(GridBitmapIndex index, String indexValue, String... rowKeys) {
		final IndexOperationResult result = index.searchEq(key(indexValue));
		final Set<String> found = new HashSet<>();
		for (IndexPointerRef pointer : result.pointersOrExpand()) {
			found.add(new String(pointer.resolveKey(), StandardCharsets.UTF_8));
		}
		assertEquals(Set.of(rowKeys), found);
	}

	private static SingleTreeKey key(String value) {
		return new SingleTreeKey(value.getBytes(StandardCharsets.UTF_8));
	}

	private static IndexPointerRef ptr(String rowKey) {
		return new IndexPointerRef(0L, rowKey.getBytes(StandardCharsets.UTF_8));
	}
}