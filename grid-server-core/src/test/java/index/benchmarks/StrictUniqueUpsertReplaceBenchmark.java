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
 * STRICT UNIQUE upsert latency: same-PK idempotent reindex vs owner-replace (ghost PK).
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
public class StrictUniqueUpsertReplaceBenchmark extends AbstractLatencyBenchmark {
	private static final int SHARDS = 4;
	private static final int BASE_PK = 1000;
	private static final String EVENT = "benchEvent";
	private static final String SCHEDULER = "benchSched";
	private static final String TABLE = "uniq_upsert_bench";

	private SqlEngine engine;
	private final AtomicInteger nextGhostPk = new AtomicInteger(BASE_PK + 1);

	@Setup(Level.Trial)
	public void setupBenchmark() {
		engine = SqlBenchHelper.createEngine(SHARDS);
		engine.execute(
				"CREATE TABLE " + TABLE + " ("
						+ "id INT PRIMARY KEY, "
						+ "event_name VARCHAR NOT NULL, "
						+ "scheduler_name VARCHAR NOT NULL)");
		engine.execute(
				"CREATE UNIQUE INDEX uniq_upsert_bench_u "
						+ "ON " + TABLE + " (event_name, scheduler_name)");
		engine.execute(
				"INSERT INTO " + TABLE + " (id, event_name, scheduler_name) VALUES ("
						+ BASE_PK + ", '" + EVENT + "', '" + SCHEDULER + "')");
	}

	@TearDown(Level.Trial)
	public void tearDownBenchmark() {
		if (engine != null && engine.catalog().exists(TABLE)) {
			engine.catalog().dropTable(TABLE);
		}
	}

	@Benchmark
	public void samePkIdempotentUpsert(Blackhole bh) {
		final SqlResult result = engine.execute(
				"INSERT INTO " + TABLE + " (id, event_name, scheduler_name) VALUES ("
						+ BASE_PK + ", '" + EVENT + "', '" + SCHEDULER + "') "
						+ "ON CONFLICT (event_name, scheduler_name) DO UPDATE SET id = " + BASE_PK);
		bh.consume(result.rows().size());
	}

	@Benchmark
	public void ownerReplaceGhostPk(Blackhole bh) {
		final int ghostId = nextGhostPk.getAndIncrement();
		final SqlResult result = engine.execute(
				"INSERT INTO " + TABLE + " (id, event_name, scheduler_name) VALUES ("
						+ ghostId + ", '" + EVENT + "', '" + SCHEDULER + "') "
						+ "ON CONFLICT (event_name, scheduler_name) DO UPDATE SET id = " + ghostId);
		bh.consume(result.rows().size());
	}

	/**
	 * Happy-path STRICT replace after apply×index defer (map under shardLocks, index after unlock).
	 */
	@Benchmark
	public void ownerReplaceThenSelect(Blackhole bh) {
		final int ghostId = nextGhostPk.getAndIncrement();
		engine.execute(
				"INSERT INTO " + TABLE + " (id, event_name, scheduler_name) VALUES ("
						+ ghostId + ", '" + EVENT + "', '" + SCHEDULER + "') "
						+ "ON CONFLICT (event_name, scheduler_name) DO UPDATE SET id = " + ghostId);
		final SqlResult rows = engine.execute(
				"SELECT id FROM " + TABLE
						+ " WHERE event_name = '" + EVENT + "' AND scheduler_name = '" + SCHEDULER + "'");
		bh.consume(rows.rows().size());
	}
}
