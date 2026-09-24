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

import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import com.hazelcast.query.Predicates;
import index.sql.SqlBenchHelper;
import org.genfork.grid.query.plan.QueryParser;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.store.TableStore;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
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

import java.io.Serializable;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * IMDG put + EQ+LIMIT: TableStore putIndexed / selectKeys vs embedded Hazelcast IMap.
 * Fair vs followup: sync map+index put and key-list filter (not staged upsert / full row decode).
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
public class HazelcastCompareBenchmark extends AbstractLatencyBenchmark {

	private static final String TABLE = "hz_row";

	/** Hazelcast value twin only — not a grid domain. */
	public static final class HzRow implements Serializable {
		private static final long serialVersionUID = 1L;
		private final String id;
		private final int bucket;

		public HzRow(String id, int bucket) {
			this.id = id;
			this.bucket = bucket;
		}

		public String getId() {
			return id;
		}

		public int getBucket() {
			return bucket;
		}
	}

	@Param({"10000"})
	public int rows;

	private SqlEngine engine;
	private TableStore store;
	private HazelcastInstance hz;
	private IMap<String, HzRow> hzMap;
	private final AtomicLong putSeq = new AtomicLong();

	private String[] gridSqlByBucket;

	@Setup
	public void setup() {
		QueryParser.clearPlanCache();
		engine = SqlBenchHelper.createEngine(4);
		SqlBenchHelper.ensureIndexedTable(engine, SqlBenchHelper.hzRowSchema(TABLE));
		store = SqlBenchHelper.store(engine, TABLE);

		final Config cfg = new Config();
		cfg.setClusterName("jamoa-hz-bench-" + ThreadLocalRandom.current().nextInt(1_000_000));
		cfg.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
		cfg.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(false);
		cfg.setProperty("hazelcast.logging.type", "none");
		hz = Hazelcast.newHazelcastInstance(cfg);
		hzMap = hz.getMap("hz-rows");

		for (int i = 0; i < rows; i++) {
			final String id = "id-" + i;
			final int bucket = i % 50;
			SqlBenchHelper.putIndexedRow(engine, TABLE, id, bucket);
			hzMap.put(id, new HzRow(id, bucket));
		}
		putSeq.set(rows);
		gridSqlByBucket = new String[50];
		for (int b = 0; b < 50; b++) {
			gridSqlByBucket[b] = "SELECT id, bucket FROM " + TABLE + " WHERE bucket = " + b + " LIMIT 0, 100";
			store.selectKeys(gridSqlByBucket[b]);
		}
	}

	@TearDown
	public void tearDown() {
		if (engine != null && engine.catalog().exists(TABLE)) {
			engine.catalog().dropTable(TABLE);
		}
		if (hz != null) {
			hz.shutdown();
		}
	}

	@Benchmark
	public void gridPut(Blackhole bh) {
		final long n = putSeq.getAndIncrement();
		final String id = "p-" + n;
		final int bucket = (int) (n % 50);
		store.putIndexed(new Object[]{id, bucket});
		bh.consume(id);
	}

	@Benchmark
	public void hazelcastPut(Blackhole bh) {
		final long n = putSeq.getAndIncrement();
		final HzRow row = new HzRow("p-" + n, (int) (n % 50));
		hzMap.put(row.getId(), row);
		bh.consume(row);
	}

	@Benchmark
	public void gridFilterLimit(Blackhole bh) {
		final int bucket = ThreadLocalRandom.current().nextInt(50);
		final List<byte[]> keys = store.selectKeys(gridSqlByBucket[bucket]);
		bh.consume(keys == null ? 0 : keys.size());
	}

	@Benchmark
	public void hazelcastFilterLimit(Blackhole bh) {
		final int bucket = ThreadLocalRandom.current().nextInt(50);
		final var values = hzMap.values(Predicates.equal("bucket", bucket));
		int n = 0;
		for (HzRow ignored : values) {
			if (++n >= 100) {
				break;
			}
		}
		bh.consume(n);
	}
}
