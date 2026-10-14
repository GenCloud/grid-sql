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
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
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

import java.util.concurrent.TimeUnit;

/**
 * JMH for LAX index {@code IN} union (hit + miss literals) — post IN null-pointers fix.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@Threads(1)
@State(Scope.Benchmark)
public class IndexInUnionBenchmark extends AbstractLatencyBenchmark {
	private static final String TABLE = "in_union_rows";
	private static final int SHARDS = 4;

	@Param({"10000", "100000"})
	public int count;

	private SqlEngine engine;
	private String inHitMissSql;
	private String inAllMissSql;

	@Setup(Level.Trial)
	public void setup() {
		engine = SqlBenchHelper.createEngine(SHARDS);
		engine.execute("CREATE TABLE " + TABLE + " (id INT PRIMARY KEY, flag INT)");
		engine.execute("CREATE INDEX in_union_flag ON " + TABLE + " (flag)");
		final int batch = 500;
		for (int start = 0; start < count; start += batch) {
			final int end = Math.min(count, start + batch);
			final StringBuilder insert = new StringBuilder("INSERT INTO " + TABLE + " VALUES ");
			for (int i = start; i < end; i++) {
				if (i > start) {
					insert.append(',');
				}
				insert.append('(').append(i).append(',').append(i % 100).append(')');
			}
			engine.execute(insert.toString());
		}
		inHitMissSql = "SELECT id FROM " + TABLE + " WHERE flag IN (0, 1, 999)";
		inAllMissSql = "SELECT id FROM " + TABLE + " WHERE flag IN (900, 901, 902)";
	}

	@TearDown(Level.Trial)
	public void tearDown() {
		SqlBenchHelper.closeAllStores(engine);
	}

	@Benchmark
	public void inHitAndMiss(Blackhole bh) {
		final SqlResult result = engine.execute(inHitMissSql);
		bh.consume(result.rows().size());
	}

	@Benchmark
	public void inAllMiss(Blackhole bh) {
		final SqlResult result = engine.execute(inAllMissSql);
		bh.consume(result.rows().size());
	}
}
