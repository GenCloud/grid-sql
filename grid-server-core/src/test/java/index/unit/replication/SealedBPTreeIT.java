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
import org.genfork.grid.mem.index.btree.GridPointerBPTree;
import org.genfork.grid.mem.index.btree.IndexOperationResult;
import org.genfork.grid.mem.index.btree.SingleTreeKey;
import org.genfork.grid.mem.index.btree.comparator.ArraysComparator;
import org.genfork.grid.replication.snapshot.sealed.SealedBPTreeReader;
import org.genfork.grid.replication.snapshot.sealed.SealedBPTreeWriter;
import org.genfork.grid.replication.snapshot.sealed.SealedFallbackIndex;
import org.genfork.grid.replication.snapshot.sealed.SealedMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author: GenCloud
 * @date: 2026/05
 * @since: 1.0
 */
class SealedBPTreeIT {
	private static final ArraysComparator COMPARATOR = new ArraysComparator();

	@TempDir
	Path temporary;

	@Test
	void readsEqAndRangeAcrossFixedPages() throws Exception {
		final Path file = temporary.resolve("sealed").resolve("abcd_2_idx_status.sbpt");
		final List<SealedBPTreeWriter.Entry> entries = new ArrayList<>();
		for (int i = 0; i < 2_000; i++) {
			entries.add(new SealedBPTreeWriter.Entry(indexKey(i), rowKey(i, 0)));
			if (i == 777) {
				entries.add(new SealedBPTreeWriter.Entry(indexKey(i), rowKey(i, 1)));
			}
		}
		entries.sort((left, right) -> COMPARATOR.compare(left.indexKey(), right.indexKey()));
		SealedBPTreeWriter.write(file, "orders", 2, "status", entries);

		SealedMetrics.reset();
		try (SealedBPTreeReader reader = SealedBPTreeReader.open(file)) {
			final List<byte[]> equal = reader.searchEq(indexKey(777));
			assertEquals(2, equal.size());
			assertArrayEquals(rowKey(777, 0), equal.get(0));
			assertArrayEquals(rowKey(777, 1), equal.get(1));

			final List<byte[]> range = reader.searchRange(indexKey(995), indexKey(1_005));
			assertEquals(11, range.size());
			assertArrayEquals(rowKey(995, 0), range.getFirst());
			assertArrayEquals(rowKey(1_005, 0), range.getLast());
			assertTrue(SealedMetrics.SEALED_INDEX_HIT.get() >= 2);
			assertTrue(SealedMetrics.SEALED_INDEX_PAGE_FAULT.get() >= 0);
		}
	}

	@Test
	void fallsBackAfterRamTreeIsCleared() throws Exception {
		final byte[] indexKey = indexKey(42);
		final byte[] rowKey = rowKey(42, 0);
		final Path file = temporary.resolve("fallback.sbpt");
		SealedBPTreeWriter.write(file, "orders", 0, "status",
				List.of(new SealedBPTreeWriter.Entry(indexKey, rowKey)));

		final GridPointerBPTree ram = new GridPointerBPTree("status-LAX", false);
		ram.insert(new SingleTreeKey(indexKey), new IndexPointerRef(0, rowKey));
		try (SealedBPTreeReader reader = SealedBPTreeReader.open(file)) {
			final SealedFallbackIndex fallback = new SealedFallbackIndex(ram);
			fallback.addReader(reader);
			fallback.clear();
			final IndexOperationResult result = fallback.searchEq(new SingleTreeKey(indexKey));
			assertEquals(1, result.getSize());
			assertArrayEquals(rowKey, result.getPointers().iterator().next().resolveKey());
		}
	}

	@Test
	void lazySearchAllSeesSealedOnlyKeysWithoutRamHydrate() throws Exception {
		final byte[] sealedOnlyIndex = indexKey(100);
		final byte[] sealedOnlyRow = rowKey(100, 0);
		final byte[] ramIndex = indexKey(200);
		final byte[] ramRow = rowKey(200, 0);
		final Path file = temporary.resolve("lazy-search-all.sbpt");
		SealedBPTreeWriter.write(file, "orders", 0, "status",
				List.of(
						new SealedBPTreeWriter.Entry(sealedOnlyIndex, sealedOnlyRow),
						new SealedBPTreeWriter.Entry(ramIndex, ramRow)));

		final GridPointerBPTree ram = new GridPointerBPTree("status-LAX", false);
		ram.insert(new SingleTreeKey(ramIndex), new IndexPointerRef(0, ramRow));
		try (SealedBPTreeReader reader = SealedBPTreeReader.open(file)) {
			final SealedFallbackIndex fallback = new SealedFallbackIndex(ram);
			fallback.addReader(reader);
			// LAZY: ramAuthoritative stays false — do not hydrate sealed into RAM BPTree.
			assertEquals(1, fallback.delegate().searchAll().getSize());

			final IndexOperationResult all = fallback.searchAll();
			assertEquals(2, all.getSize());
			assertKeys(all, sealedOnlyRow, ramRow);

			final IndexOperationResult notEq = fallback.searchNotEq(new SingleTreeKey(ramIndex));
			assertEquals(1, notEq.getSize());
			assertArrayEquals(sealedOnlyRow, notEq.getPointers().iterator().next().resolveKey());

			final List<byte[]> walked = new ArrayList<>();
			reader.forEachLeafEntry((indexKey, rowKey) -> walked.add(rowKey));
			assertEquals(2, walked.size());
		}
	}

	private static void assertKeys(IndexOperationResult result, byte[]... expected) {
		final List<byte[]> actual = new ArrayList<>();
		for (IndexPointerRef pointer : result.getPointers()) {
			actual.add(pointer.resolveKey());
		}
		assertEquals(expected.length, actual.size());
		for (byte[] key : expected) {
			boolean found = false;
			for (byte[] candidate : actual) {
				if (Arrays.equals(key, candidate)) {
					found = true;
					break;
				}
			}
			assertTrue(found, "missing key " + new String(key, StandardCharsets.UTF_8));
		}
	}

	private static byte[] indexKey(int value) {
		return String.format("%08d", value).getBytes(StandardCharsets.UTF_8);
	}

	private static byte[] rowKey(int value, int duplicate) {
		return String.format("row-%08d-%d", value, duplicate).getBytes(StandardCharsets.UTF_8);
	}
}