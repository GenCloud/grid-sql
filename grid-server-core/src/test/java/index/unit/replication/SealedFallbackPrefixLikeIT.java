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
import org.genfork.grid.mem.index.btree.GridPointerBPTree;
import org.genfork.grid.mem.index.btree.GridPointerCompositeBPTree;
import org.genfork.grid.mem.index.btree.IndexOperationResult;
import org.genfork.grid.mem.index.btree.SingleTreeKey;
import org.genfork.grid.mem.index.btree.comparator.ArraysComparator;
import org.genfork.grid.replication.snapshot.sealed.SealedBPTreeReader;
import org.genfork.grid.replication.snapshot.sealed.SealedBPTreeService;
import org.genfork.grid.replication.snapshot.sealed.SealedBPTreeWriter;
import org.genfork.grid.replication.snapshot.sealed.SealedCompositeIndexKey;
import org.genfork.grid.replication.snapshot.sealed.SealedCompositeIndexNames;
import org.genfork.grid.replication.snapshot.sealed.SealedFallbackCompositeIndex;
import org.genfork.grid.replication.snapshot.sealed.SealedFallbackIndex;
import org.genfork.grid.serial.SqlWireUtil;
import org.genfork.grid.serial.WireLikeMatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 2: sealed cold-path for composite left-prefix EQ and single-column LIKE under
 * cleared RAM (LAZY / working-set eviction stand-in).
 *
 * @author: GenCloud
 * @date: 2026/05
 * @since: 1.0
 */
class SealedFallbackPrefixLikeIT {
	private static final ArraysComparator COMPARATOR = new ArraysComparator();
	private static final String DOMAIN = "orders";
	private static final int SHARD = 0;

	@TempDir
	Path temporary;

	@Test
	void compositeLeftPrefixHitsSealedAfterRamClear() throws Exception {
		final List<String> columns = List.of("status", "region");
		final String sealedName = SealedCompositeIndexNames.of(columns);
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

		try (SealedBPTreeReader reader = service.open(DOMAIN, SHARD, sealedName)) {
			final SealedFallbackCompositeIndex fallback = new SealedFallbackCompositeIndex(ram);
			fallback.addReader(reader);
			assertEquals(1, fallback.sealedReaderCount());
			fallback.clear();

			final CompositeTreeKey prefix = new CompositeTreeKey(new byte[][]{bytes("open")}, 0);
			assertTrue(prefix.isPartial());
			final IndexOperationResult openPrefix = fallback.searchEq(prefix);
			assertEquals(2, openPrefix.getSize());
			assertKeys(openPrefix, "row-1", "row-2");

			final CompositeTreeKey multiPrefix = new CompositeTreeKey(
					new byte[][]{bytes("open"), bytes("eu")}, 0);
			assertEquals(1, fallback.searchEq(multiPrefix).getSize());
			assertKeys(fallback.searchEq(multiPrefix), "row-1");
		}
	}

	@Test
	void compositeMidColumnPartialHitsSealedAfterRamClear() throws Exception {
		final List<String> columns = List.of("status", "region");
		final String sealedName = SealedCompositeIndexNames.of(columns);
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

		try (SealedBPTreeReader reader = service.open(DOMAIN, SHARD, sealedName)) {
			final SealedFallbackCompositeIndex fallback = new SealedFallbackCompositeIndex(ram);
			fallback.addReader(reader);
			fallback.clear();

			final CompositeTreeKey regionEu = ram.createKey("region", bytes("eu"));
			assertTrue(regionEu.isPartial());
			assertEquals(1, regionEu.partialColumnIndex());
			final IndexOperationResult euRows = fallback.searchEq(regionEu);
			assertEquals(2, euRows.getSize());
			assertKeys(euRows, "row-1", "row-3");
		}
	}

	@Test
	void singleColumnLikeTrailingPercentHitsSealedAfterRamClear() throws Exception {
		assertNotNull(WireLikeMatcher.trailingPercentLiteral(
				WireLikeMatcher.utf8Payload(SqlWireUtil.toGenericArray("foo%"))));

		final Path file = temporary.resolve("status-like.sbpt");
		final byte[] foo = SqlWireUtil.toGenericArray("foo");
		final byte[] foobar = SqlWireUtil.toGenericArray("foobar");
		final byte[] bar = SqlWireUtil.toGenericArray("bar");
		final List<SealedBPTreeWriter.Entry> entries = List.of(
				new SealedBPTreeWriter.Entry(foo, bytes("row-foo")),
				new SealedBPTreeWriter.Entry(foobar, bytes("row-foobar")),
				new SealedBPTreeWriter.Entry(bar, bytes("row-bar")));
		final List<SealedBPTreeWriter.Entry> sorted = new ArrayList<>(entries);
		sorted.sort((left, right) -> COMPARATOR.compare(left.indexKey(), right.indexKey()));
		SealedBPTreeWriter.write(file, DOMAIN, SHARD, "status", sorted);

		final GridPointerBPTree ram = new GridPointerBPTree("status-LAX", false);
		ram.insert(new SingleTreeKey(foo), new IndexPointerRef(0, bytes("row-foo")));
		ram.insert(new SingleTreeKey(foobar), new IndexPointerRef(0, bytes("row-foobar")));
		ram.insert(new SingleTreeKey(bar), new IndexPointerRef(0, bytes("row-bar")));

		try (SealedBPTreeReader reader = SealedBPTreeReader.open(file)) {
			final SealedFallbackIndex fallback = new SealedFallbackIndex(ram);
			fallback.addReader(reader);
			assertEquals(1, fallback.sealedReaderCount());
			fallback.clear();

			final IndexOperationResult likeFoo = fallback.searchLike(
					new SingleTreeKey(SqlWireUtil.toGenericArray("foo%")));
			assertEquals(2, likeFoo.getSize());
			assertKeys(likeFoo, "row-foo", "row-foobar");

			final IndexOperationResult likeMid = fallback.searchLike(
					new SingleTreeKey(SqlWireUtil.toGenericArray("%oo%")));
			assertEquals(2, likeMid.getSize());
			assertKeys(likeMid, "row-foo", "row-foobar");
		}
	}

	@Test
	void compositeSearchLikeHitsSealedAfterRamClear() throws Exception {
		final List<String> columns = List.of("status", "region");
		final String sealedName = SealedCompositeIndexNames.of(columns);
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

		try (SealedBPTreeReader reader = service.open(DOMAIN, SHARD, sealedName)) {
			final SealedFallbackCompositeIndex fallback = new SealedFallbackCompositeIndex(ram);
			fallback.addReader(reader);
			fallback.clear();
			final IndexOperationResult like = fallback.searchLike(
					new CompositeTreeKey(new byte[][]{SqlWireUtil.toGenericArray("op%")}));
			assertEquals(2, like.getSize());
			assertKeys(like, "row-1", "row-2");
		}
	}

	@Test
	void singleColumnOpenRangeHitsSealedAfterRamClear() throws Exception {
		final Path file = temporary.resolve("status-gt.sbpt");
		final byte[] a = SqlWireUtil.toGenericArray("a");
		final byte[] b = SqlWireUtil.toGenericArray("b");
		final byte[] c = SqlWireUtil.toGenericArray("c");
		final List<SealedBPTreeWriter.Entry> entries = List.of(
				new SealedBPTreeWriter.Entry(a, bytes("row-a")),
				new SealedBPTreeWriter.Entry(b, bytes("row-b")),
				new SealedBPTreeWriter.Entry(c, bytes("row-c")));
		final List<SealedBPTreeWriter.Entry> sorted = new ArrayList<>(entries);
		sorted.sort((left, right) -> COMPARATOR.compare(left.indexKey(), right.indexKey()));
		SealedBPTreeWriter.write(file, DOMAIN, SHARD, "status", sorted);

		final GridPointerBPTree ram = new GridPointerBPTree("status-LAX", false);
		ram.insert(new SingleTreeKey(a), new IndexPointerRef(0, bytes("row-a")));
		ram.insert(new SingleTreeKey(b), new IndexPointerRef(0, bytes("row-b")));
		ram.insert(new SingleTreeKey(c), new IndexPointerRef(0, bytes("row-c")));

		try (SealedBPTreeReader reader = SealedBPTreeReader.open(file)) {
			final SealedFallbackIndex fallback = new SealedFallbackIndex(ram);
			fallback.addReader(reader);
			fallback.clear();
			final IndexOperationResult gt = fallback.searchGreaterThan(new SingleTreeKey(a));
			assertEquals(2, gt.getSize());
			assertKeys(gt, "row-b", "row-c");
		}
	}

	private static void assertKeys(IndexOperationResult result, String... rowKeys) {
		final Set<String> found = new HashSet<>();
		for (IndexPointerRef pointer : result.pointersOrExpand()) {
			final byte[] key = pointer.resolveKey();
			assertNotNull(key);
			found.add(new String(key, StandardCharsets.UTF_8));
		}
		assertEquals(Set.of(rowKeys), found);
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
