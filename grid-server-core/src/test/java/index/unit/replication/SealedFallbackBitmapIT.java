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

import org.genfork.grid.catalog.IndexDef;
import org.genfork.grid.catalog.SqlType;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.mem.index.AbstractIndexOperation;
import org.genfork.grid.mem.index.GridCompositeIndex;
import org.genfork.grid.mem.index.IndexPointerRef;
import org.genfork.grid.mem.index.IndexType;
import org.genfork.grid.mem.index.bitmap.GridBitmapIndex;
import org.genfork.grid.mem.index.btree.IndexOperationResult;
import org.genfork.grid.mem.index.btree.SingleTreeKey;
import org.genfork.grid.replication.snapshot.sealed.SealedFallbackBitmap;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BITMAP sealed cold-merge fallback (RAM + {@code .sbm}), not full replace.
 *
 * @author: GenCloud
 * @date: 2026/05
 * @since: 1.0
 */
class SealedFallbackBitmapIT {
	private static final String TABLE = "bitmap_fallback_t";
	private static final String PROPERTY = "status";
	private static final int SHARD = 0;
	private static final int BITMAP_CAPACITY = 1 << 10;

	@Test
	void coldMergeKeepsRamAndSealedPostings() {
		final GridBitmapIndex ram = new GridBitmapIndex(PROPERTY + "-BITMAP", BITMAP_CAPACITY);
		ram.insert(key("open"), ptr("ram-1"));

		final GridBitmapIndex sealed = new GridBitmapIndex(PROPERTY + "-BITMAP", BITMAP_CAPACITY);
		sealed.insert(key("open"), ptr("sealed-1"));
		sealed.insert(key("closed"), ptr("sealed-2"));

		final SealedFallbackBitmap fallback = new SealedFallbackBitmap(ram);
		fallback.addSealed(SHARD, sealed);

		assertKeys(fallback.searchEq(key("open")), "ram-1", "sealed-1");
		assertKeys(fallback.searchEq(key("closed")), "sealed-2");
		assertEquals(0, fallback.searchEq(key("missing")).getSize());
	}

	@Test
	void installSealedBitmapFallbackOnCompositeIndex() {
		final TableSchema schema = TableSchema.builder(TABLE)
				.primaryKey("id", SqlType.VARCHAR)
				.column(PROPERTY, SqlType.VARCHAR)
				.index(IndexDef.of("idx_status", IndexType.BITMAP, PROPERTY))
				.build();
		final GridCompositeIndex composite = new GridCompositeIndex(schema);

		composite.resolveSingleColumnIndex(PROPERTY).insert(key("open"), ptr("ram-1"));

		final GridBitmapIndex sealed = new GridBitmapIndex(PROPERTY + "-BITMAP", BITMAP_CAPACITY);
		sealed.insert(key("open"), ptr("cold-1"));
		sealed.insert(key("closed"), ptr("cold-2"));
		composite.installSealedBitmapFallback(PROPERTY, SHARD, sealed);

		final AbstractIndexOperation<byte[], SingleTreeKey> index =
				composite.resolveSingleColumnIndex(PROPERTY);
		assertInstanceOf(SealedFallbackBitmap.class, index);
		assertTrue(SealedFallbackBitmap.isBitmapIndex(index));
		assertKeys(index.searchEq(key("open")), "ram-1", "cold-1");
		assertKeys(index.searchEq(key("closed")), "cold-2");
	}

	private static void assertKeys(IndexOperationResult result, String... rowKeys) {
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
