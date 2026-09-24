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

import index.sql.SqlBenchHelper;
import org.genfork.grid.catalog.IndexDef;
import org.genfork.grid.catalog.SqlType;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.mem.index.IndexType;
import org.genfork.grid.query.plan.QueryParser;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.store.TableStore;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * LAX composite-index filter+LIMIT / ORDER BY latency (SQL-first TableStore).
 *
 * @author: GenCloud
 * @date: 2026/12
 * @since: 1.0
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@Threads(1)
@State(Scope.Benchmark)
public class LaxGridCompositeIndexBenchmarkTest extends AbstractLatencyBenchmark {
	private static final String TABLE = "lax_sorted";

	@Param({"10000", "100000", "1000000"})
	public int count;

	private SqlEngine engine;
	private TableStore store;
	private String sql;
	private String sqlWithOrder;

	private static TableSchema laxSchema() {
		return TableSchema.builder(TABLE)
				.primaryKey("id", SqlType.VARCHAR)
				.column("intVal", SqlType.INT)
				.column("intVal2", SqlType.INT)
				.column("orderedVal", SqlType.INT)
				.column("orderedVal2", SqlType.INT)
				.externalOrder("orderedVal")
				.externalOrder("orderedVal2")
				.index(IndexDef.of("idx_intVal", IndexType.LAX, "intVal"))
				.index(IndexDef.of("idx_intVal2", IndexType.LAX, "intVal2"))
				.index(IndexDef.of("idx_orderedVal", IndexType.LAX, "orderedVal"))
				.index(IndexDef.of("idx_orderedVal2", IndexType.LAX, "orderedVal2"))
				.build();
	}

	@Setup(Level.Trial)
	public void setupBenchmark() {
		QueryParser.clearPlanCache();
		engine = SqlBenchHelper.createEngine(4);
		SqlBenchHelper.ensureIndexedTable(engine, laxSchema());
		store = SqlBenchHelper.store(engine, TABLE);

		final List<Integer> sampleIntVal = new ArrayList<>(10);
		final List<Integer> sampleIntVal2 = new ArrayList<>(10);

		for (int i = 0; i < count; i++) {
			final int intVal = ThreadLocalRandom.current().nextInt(0, 4);
			final int intVal2 = ThreadLocalRandom.current().nextInt(10, 14);
			final int orderedVal = ThreadLocalRandom.current().nextInt(0, 1_000_000);
			final int orderedVal2 = ThreadLocalRandom.current().nextInt(0, 1_000_000);
			SqlBenchHelper.putIndexedRow(engine, TABLE, String.valueOf(i), intVal, intVal2, orderedVal, orderedVal2);
			if (sampleIntVal.size() < 10) {
				sampleIntVal.add(intVal);
				sampleIntVal2.add(intVal2);
			}
		}

		final int probeIntVal = sampleIntVal.get(ThreadLocalRandom.current().nextInt(sampleIntVal.size()));
		final int probeIntVal2 = sampleIntVal2.get(ThreadLocalRandom.current().nextInt(sampleIntVal2.size()));

		sql = "SELECT * FROM " + TABLE + " WHERE intVal = " + probeIntVal
				+ " OR intVal2 = " + probeIntVal2 + " LIMIT 0, 10";
		sqlWithOrder = "SELECT * FROM " + TABLE + " WHERE intVal = " + probeIntVal
				+ " OR intVal2 = " + probeIntVal2 + " ORDER BY orderedVal ASC LIMIT 0, 10";

		store.selectKeys(sql);
		store.selectKeys(sqlWithOrder);
	}

	@TearDown(Level.Trial)
	public void downBenchmark() {
		if (engine != null && engine.catalog().exists(TABLE)) {
			engine.catalog().dropTable(TABLE);
		}
	}

	@Benchmark
	public void searchWithLimitBench(Blackhole bh) {
		final List<byte[]> result = store.selectKeys(sql);
		bh.consume(result);
	}

	@Benchmark
	public void searchWithLimitOrderBench(Blackhole bh) {
		final List<byte[]> result = store.selectKeys(sqlWithOrder);
		bh.consume(result);
	}
}