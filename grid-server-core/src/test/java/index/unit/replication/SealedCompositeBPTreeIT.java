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
import org.genfork.grid.mem.index.btree.CompositeTreeKey;
import org.genfork.grid.mem.index.btree.GridPointerCompositeBPTree;
import org.genfork.grid.mem.index.btree.IndexOperationResult;
import org.genfork.grid.mem.index.btree.comparator.ArraysComparator;
import org.genfork.grid.replication.snapshot.sealed.SealedBPTreeReader;
import org.genfork.grid.replication.snapshot.sealed.SealedBPTreeService;
import org.genfork.grid.replication.snapshot.sealed.SealedBPTreeWriter;
import org.genfork.grid.replication.snapshot.sealed.SealedCompositeIndexKey;
import org.genfork.grid.replication.snapshot.sealed.SealedCompositeIndexNames;
import org.genfork.grid.replication.snapshot.sealed.SealedFallbackCompositeIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1.7: composite BPTree sealed {@code .sbpt} dump/bind roundtrip via {@link SealedBPTreeWriter}.
 *
 * @author: GenCloud
 * @date: 2026/05
 * @since: 1.0
 */
class SealedCompositeBPTreeIT {
	private static final ArraysComparator COMPARATOR = new ArraysComparator();
	private static final String DOMAIN = "orders";
	private static final int SHARD = 0;

	@TempDir
	Path temporary;

	@Test
	void dumpBindRoundTripFullEqAfterRamClear() throws Exception {
		final List<String> columns = List.of("status", "region");
		final String sealedName = SealedCompositeIndexNames.of(columns);
		assertEquals("status+region", sealedName);

		final GridPointerCompositeBPTree ram =
				new GridPointerCompositeBPTree(columns, columns + "-LAX", false);
		ram.insert(compositeKey("open", "eu"), ptr("row-1"));
		ram.insert(compositeKey("open", "us"), ptr("row-2"));
		ram.insert(compositeKey("closed", "eu"), ptr("row-3"));

		final List<SealedBPTreeWriter.Entry> entries = new ArrayList<>();
		ram.forEachLeafEntry((indexKey, rowKey) ->
				entries.add(new SealedBPTreeWriter.Entry(SealedCompositeIndexKey.encode(indexKey), rowKey)));
		entries.sort((left, right) -> COMPARATOR.compare(left.indexKey(), right.indexKey()));

		final SealedBPTreeService service = new SealedBPTreeService(temporary);
		service.dumpIndex(DOMAIN, SHARD, sealedName, entries);

		final Path file = service.indexFile(DOMAIN, SHARD, sealedName);
		assertTrue(Files.isRegularFile(file));
		assertTrue(file.getFileName().toString().contains("idx_status+region"));
		assertTrue(file.getFileName().toString().endsWith(".sbpt"));

		final byte[][] roundTrip = SealedCompositeIndexKey.decode(
				SealedCompositeIndexKey.encode(new byte[][]{bytes("open"), bytes("eu")}));
		assertArrayEquals(bytes("open"), roundTrip[0]);
		assertArrayEquals(bytes("eu"), roundTrip[1]);

		try (SealedBPTreeReader reader = service.open(DOMAIN, SHARD, sealedName)) {
			final SealedFallbackCompositeIndex fallback = new SealedFallbackCompositeIndex(ram);
			fallback.addReader(reader);
			fallback.clear();

			final IndexOperationResult openEu = fallback.searchEq(compositeKey("open", "eu"));
			assertEquals(1, openEu.getSize());
			assertArrayEquals(bytes("row-1"), openEu.getPointers().iterator().next().resolveKey());

			final IndexOperationResult openUs = fallback.searchEq(compositeKey("open", "us"));
			assertEquals(1, openUs.getSize());
			assertArrayEquals(bytes("row-2"), openUs.getPointers().iterator().next().resolveKey());

			assertEquals(0, fallback.searchEq(compositeKey("missing", "eu")).getSize());
		}
	}

	private static CompositeTreeKey compositeKey(String status, String region) {
		return new CompositeTreeKey(new byte[][]{bytes(status), bytes(region)});
	}

	private static IndexPointerRef ptr(String rowKey) {
		return new IndexPointerRef(0L, bytes(rowKey));
	}

	private static byte[] bytes(String value) {
		return value.getBytes(StandardCharsets.UTF_8);
	}
}
