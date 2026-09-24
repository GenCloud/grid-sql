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
package index.benchmarks;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import index.sql.SqlBenchHelper;

import org.genfork.grid.catalog.SqlType;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.query.adaptive.QueryHeaviness;
import org.genfork.grid.query.distributed.DistributedKeyFanOut;
import org.genfork.grid.query.distributed.ShardPartitionPlanner;
import org.genfork.grid.serial.SqlWireUtil;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.store.TableStore;
import org.genfork.grid.threading.ThreadService;

/**
 * Domain-shard partition + {@link DistributedKeyFanOut#mapReduceByShard} identity mapper.
 *
 * @author: GenCloud
 * @date: 2026/12
 * @since: 1.0
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@Threads(1)
@State(Scope.Benchmark)
public class ShardPartitionMapReduceBenchmark extends AbstractLatencyBenchmark {
	private static final String TABLE = "spm_bench";
	private static final int SHARDS = 4;
	private static final boolean NOT_INDEXED = false;

	@Param({"10000", "100000"})
	public int count;

	private SqlEngine engine;
	private TableStore store;
	private List<byte[]> keys;
	private QueryHeaviness heavy;
	private Function<List<byte[]>, List<byte[]>> identityMapper;

	@Setup(Level.Trial)
	public void setupBenchmark() {
		ThreadService.ensureRunning();
		engine = SqlBenchHelper.createEngine(SHARDS);
		SqlBenchHelper.ensureIndexedTable(engine, TableSchema.builder(TABLE)
				.primaryKey("id", SqlType.INT)
				.column("val", SqlType.INT)
				.build());
		store = SqlBenchHelper.store(engine, TABLE);
		keys = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			SqlBenchHelper.putIndexedRow(engine, TABLE, i, i);
			keys.add(SqlWireUtil.toGenericArray(i));
		}
		heavy = QueryHeaviness.estimate(
				QueryHeaviness.distributedCandidateThreshold(),
				NOT_INDEXED
		);
		identityMapper = chunk -> {
			final List<byte[]> out = new ArrayList<>(chunk.size());
			for (byte[] key : chunk) {
				out.add(key);
			}
			return out;
		};
	}

	@TearDown(Level.Trial)
	public void tearDownBenchmark() {
		SqlBenchHelper.closeAllStores(engine);
	}

	@Benchmark
	public void partitionByDomainShard(Blackhole bh) {
		final List<List<byte[]>> buckets = ShardPartitionPlanner.partitionByDomainShard(keys, store);
		bh.consume(buckets.size());
	}

	@Benchmark
	public void mapReduceByShardIdentity(Blackhole bh) {
		final List<byte[]> out = DistributedKeyFanOut.mapReduceByShard(
				keys, store, identityMapper, heavy);
		bh.consume(out.size());
	}
}
