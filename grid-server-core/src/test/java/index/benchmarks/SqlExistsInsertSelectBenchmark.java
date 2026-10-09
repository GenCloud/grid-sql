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

/**
 * JMH: scalar {@code SELECT EXISTS} probe and {@code INSERT … SELECT} vs multi-{@code VALUES}.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.1
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@Threads(1)
@State(Scope.Benchmark)
public class SqlExistsInsertSelectBenchmark extends AbstractLatencyBenchmark {
	private static final String SRC = "exists_src";
	private static final String DST = "exists_dst";
	private static final int ROW_COUNT = 64;

	private SqlEngine engine;

	@Setup
	public void setup() {
		engine = SqlBenchHelper.createEngine(4);
		engine.execute("CREATE TABLE " + SRC + " (id INT PRIMARY KEY, val VARCHAR)");
		engine.execute("CREATE TABLE " + DST + " (id INT PRIMARY KEY, val VARCHAR)");
		final StringBuilder sb = new StringBuilder("INSERT INTO ").append(SRC).append(" VALUES ");
		for (int i = 0; i < ROW_COUNT; i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append('(').append(i).append(", 'v')");
		}
		engine.execute(sb.toString());
		engine.execute("SELECT EXISTS (SELECT 1 AS one FROM " + SRC + " WHERE id = 0)");
	}

	@TearDown
	public void tearDown() {
		if (engine != null) {
			if (engine.catalog().exists(SRC)) {
				engine.catalog().dropTable(SRC);
			}
			if (engine.catalog().exists(DST)) {
				engine.catalog().dropTable(DST);
			}
		}
		engine = null;
	}

	@Benchmark
	public void selectExistsScalar(Blackhole bh) {
		bh.consume(engine.execute(
				"SELECT EXISTS (SELECT 1 AS one FROM " + SRC + " WHERE id = 1)").rows().getFirst()[0]);
	}

	@Benchmark
	public void insertSelectBatch(Blackhole bh) {
		engine.execute("TRUNCATE TABLE " + DST);
		bh.consume(engine.execute(
				"INSERT INTO " + DST + " (id, val) SELECT id, val FROM " + SRC
		).rowsAffected());
	}

	@Benchmark
	public void insertValuesBatch(Blackhole bh) {
		engine.execute("TRUNCATE TABLE " + DST);
		final StringBuilder sb = new StringBuilder("INSERT INTO ").append(DST).append(" VALUES ");
		for (int i = 0; i < ROW_COUNT; i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append('(').append(i).append(", 'v')");
		}
		bh.consume(engine.execute(sb.toString()).rowsAffected());
	}
}
