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
package index.unit.query;

import index.sql.SqlBenchHelper;
import org.genfork.grid.catalog.IndexDef;
import org.genfork.grid.catalog.SqlType;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.mem.index.IndexType;
import org.genfork.grid.mem.index.btree.AbstractBPTree;
import org.genfork.grid.mem.index.btree.IndexOperationResult;
import org.genfork.grid.mem.index.btree.SingleTreeKey;
import org.genfork.grid.serial.SqlWireUtil;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.store.TableStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LAX writer upsert must not leave duplicate {@code IndexPointerRef} for the same PK.
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
class LaxIndexUpsertDedupIT {
	private static final String TABLE = "lax_upsert_dedup";

	private SqlEngine engine;
	private TableStore store;

	@BeforeEach
	void setUp() {
		engine = SqlBenchHelper.createEngine(2);
		final TableSchema schema = TableSchema.builder(TABLE)
				.primaryKey("id", SqlType.VARCHAR)
				.column("bucket", SqlType.INT)
				.index(IndexDef.of("idx_bucket", IndexType.LAX, "bucket"))
				.build();
		SqlBenchHelper.ensureIndexedTable(engine, schema);
		store = SqlBenchHelper.store(engine, TABLE);
	}

	@AfterEach
	void tearDown() {
		SqlBenchHelper.closeAllStores(engine);
	}

	@Test
	void writerScanCountEqualsUniqueKeysAfterReUpsertSamePk() {
		store.putIndexed("pk-1", 10);
		store.putIndexed("pk-2", 10);
		store.putIndexed("pk-1", 20);

		final AbstractBPTree<byte[], SingleTreeKey> bucketIndex = requireBucketIndex();
		final byte[] bucket20 = SqlWireUtil.toGenericArray(20);
		final IndexOperationResult eq20 = bucketIndex.searchEq(new SingleTreeKey(bucket20));
		assertNotNull(eq20);
		assertEquals(1, eq20.getSize(), "re-upsert must replace prior LAX pointer for pk-1");

		final byte[] bucket10 = SqlWireUtil.toGenericArray(10);
		final IndexOperationResult eq10 = bucketIndex.searchEq(new SingleTreeKey(bucket10));
		assertNotNull(eq10);
		assertEquals(1, eq10.getSize(), "pk-2 must remain under old bucket");

		final IndexOperationResult all = bucketIndex.searchAll();
		assertNotNull(all);
		assertEquals(2, all.getSize(), "writer scan count must equal unique keys after re-upsert");

		final byte[] pk1 = SqlWireUtil.toGenericArray("pk-1");
		final byte[] pk2 = SqlWireUtil.toGenericArray("pk-2");
		final Set<Integer> keyHashes = new HashSet<>();
		all.getPointers().forEach(ptr -> keyHashes.add(java.util.Arrays.hashCode(ptr.resolveKey())));
		assertEquals(2, keyHashes.size());
		assertTrue(all.getPointers().stream().anyMatch(p -> java.util.Arrays.equals(p.resolveKey(), pk1)));
		assertTrue(all.getPointers().stream().anyMatch(p -> java.util.Arrays.equals(p.resolveKey(), pk2)));
	}

	private AbstractBPTree<byte[], SingleTreeKey> requireBucketIndex() {
		final AtomicReference<AbstractBPTree<byte[], SingleTreeKey>> found = new AtomicReference<>();
		store.index().forEachSingleColumnBPTree((name, tree) -> {
			if ("bucket".equals(name)) {
				found.set(tree);
			}
		});
		final AbstractBPTree<byte[], SingleTreeKey> tree = found.get();
		assertNotNull(tree, "bucket LAX index");
		return tree;
	}
}
