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
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hot paths: DEFAULT clock INSERT, COALESCE projection, ON CONFLICT EXCLUDED, TRUNCATE.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@Threads(1)
@State(Scope.Thread)
public class SqlBuiltinDmlBenchmark extends AbstractLatencyBenchmark {
	private SqlEngine engine;
	private final AtomicInteger seq = new AtomicInteger();

	@Setup
	public void setup() {
		engine = SqlBenchHelper.createEngine(4);
		engine.execute(
				"CREATE TABLE defs (id INT PRIMARY KEY, created TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP)");
		engine.execute("CREATE TABLE kv (id INT PRIMARY KEY, val VARCHAR, n INT)");
		engine.execute("CREATE TABLE trunc (id INT PRIMARY KEY)");
		engine.execute("INSERT INTO kv VALUES (1, 'a', 1)");
	}

	@TearDown
	public void tearDown() {
		SqlBenchHelper.closeAllStores(engine);
	}

	@Benchmark
	public void insertWithClockDefault(Blackhole bh) {
		final int id = seq.incrementAndGet();
		bh.consume(engine.execute("INSERT INTO defs (id) VALUES (" + id + ")"));
	}

	@Benchmark
	public void coalesceProjection(Blackhole bh) {
		bh.consume(engine.execute("SELECT COALESCE(val, 'x'), COALESCE(n, 0) FROM kv WHERE id = 1"));
	}

	@Benchmark
	public void onConflictExcludedUpdate(Blackhole bh) {
		bh.consume(engine.execute(
				"INSERT INTO kv VALUES (1, 'b', 2) ON CONFLICT (id) DO UPDATE SET val = EXCLUDED.val, n = EXCLUDED.n"));
	}

	@Benchmark
	public void truncateTable(Blackhole bh) {
		engine.execute("INSERT INTO trunc VALUES (1)");
		bh.consume(engine.execute("TRUNCATE TABLE trunc"));
	}
}