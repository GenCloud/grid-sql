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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.query.adaptive.QueryHeaviness;
import org.genfork.grid.query.distributed.DistributedKeyFanOut;
import org.genfork.grid.query.distributed.ShardPartitionPlanner;
import org.genfork.grid.serial.SqlWireUtil;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.store.TableStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain-shard partition + distributed map-reduce admission.
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
public class ShardPartitionMapReduceIT {
	private static final String TABLE = "spm_t";
	private static final int SHARDS = 4;
	private static final int KEY_COUNT = 64;
	private static final boolean NOT_INDEXED = false;
	private static final boolean FULLY_INDEXED = true;
	private static final int SERIAL_MAPPER_INVOCATIONS = 1;

	@TempDir
	Path dataDir;

	private SqlEngine engine;
	private TableStore store;
	private List<byte[]> keys;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(dataDir), null, SHARDS);
		engine.execute("CREATE TABLE " + TABLE + " (id INT PRIMARY KEY, val INT)");
		keys = new ArrayList<>(KEY_COUNT);
		for (int i = 0; i < KEY_COUNT; i++) {
			engine.execute("INSERT INTO " + TABLE + " (id, val) VALUES (" + i + ", " + i + ")");
			keys.add(SqlWireUtil.toGenericArray(i));
		}
		store = engine.catalog().getStore(TABLE);
	}

	@Test
	void partitionByDomainShardMatchesStoreShardOf() {
		final List<List<byte[]>> buckets = ShardPartitionPlanner.partitionByDomainShard(keys, store);
		int total = 0;
		for (List<byte[]> bucket : buckets) {
			assertTrue(!bucket.isEmpty());
			final int expectedShard = store.shardOf(bucket.getFirst());
			for (byte[] key : bucket) {
				assertEquals(expectedShard, store.shardOf(key));
				total++;
			}
		}
		assertEquals(KEY_COUNT, total);
	}

	@Test
	void distributedHeavyInvokesMapperPerNonEmptyShard() {
		final QueryHeaviness heavy = QueryHeaviness.estimate(
				QueryHeaviness.distributedCandidateThreshold(),
				NOT_INDEXED
		);
		assertTrue(heavy.isDistributedHeavy(store.shardCount()));

		final List<List<byte[]>> buckets = ShardPartitionPlanner.partitionByDomainShard(keys, store);
		final int expectedCalls = buckets.size();
		final AtomicInteger invocations = new AtomicInteger();
		final Function<List<byte[]>, List<byte[]>> mapper = chunk -> {
			invocations.incrementAndGet();
			return new ArrayList<>(chunk);
		};

		final List<byte[]> out = DistributedKeyFanOut.mapReduceByShard(keys, store, mapper, heavy);
		assertEquals(KEY_COUNT, out.size());
		assertEquals(expectedCalls, invocations.get());
	}

	@Test
	void tinyHeavinessUsesSingleMapperCall() {
		final QueryHeaviness light = QueryHeaviness.estimate(0, FULLY_INDEXED);
		final AtomicInteger invocations = new AtomicInteger();
		final Function<List<byte[]>, List<byte[]>> mapper = chunk -> {
			invocations.incrementAndGet();
			return new ArrayList<>(chunk);
		};

		final List<byte[]> out = DistributedKeyFanOut.mapReduceByShard(keys, store, mapper, light);
		assertEquals(KEY_COUNT, out.size());
		assertEquals(SERIAL_MAPPER_INVOCATIONS, invocations.get());
	}
}
