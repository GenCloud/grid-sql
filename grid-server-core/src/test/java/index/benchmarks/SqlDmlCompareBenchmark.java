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
import org.genfork.grid.store.TableStore;
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

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DML hot-path JMH: INSERT / UPDATE RMW / UPDATE mixed RMW+literal / DELETE PK / batch INSERT.
 * First-good stamp becomes +/-5% gate baseline.
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
public class SqlDmlCompareBenchmark extends AbstractLatencyBenchmark {

	private static final String TABLE = "dml_row";
	private static final String NULL_TABLE = "dml_null";
	private static final String BOOL_TABLE = "dml_bool";

	private SqlEngine engine;
	private TableStore store;
	private final AtomicInteger seq = new AtomicInteger(100_000);

	@Setup
	public void setup() {
		engine = SqlBenchHelper.createEngine(4);
		engine.execute("CREATE TABLE " + TABLE + " (id INT PRIMARY KEY, v VARCHAR, n INT)");
		engine.execute(
				"CREATE TABLE " + NULL_TABLE + " (id INT PRIMARY KEY, ts TIMESTAMP)");
		engine.execute(
				"CREATE TABLE " + BOOL_TABLE + " (id INT PRIMARY KEY, flag BOOLEAN)");
		store = SqlBenchHelper.store(engine, TABLE);
		for (int i = 0; i < 500; i++) {
			store.putIndexed(i, "x" + i, i);
			engine.execute(
					"INSERT INTO " + NULL_TABLE + " VALUES (" + i
							+ ", TIMESTAMP '2026-01-01 00:00:00')");
			engine.execute(
					"INSERT INTO " + BOOL_TABLE + " VALUES (" + i + ", TRUE)");
		}
		engine.execute("UPDATE " + TABLE + " SET n = n + 1 WHERE id = 1");
		engine.execute(joinInsert(seq.getAndIncrement()));
		engine.execute("DELETE FROM " + TABLE + " WHERE id = " + (seq.get() - 1));
	}

	private static String joinInsert(int id) {
		return "INSERT INTO " + TABLE + " VALUES (" + id + ", 'warm', 0)";
	}

	@TearDown
	public void tearDown() {
		if (engine != null && engine.catalog().exists(TABLE)) {
			engine.catalog().dropTable(TABLE);
		}
		if (engine != null && engine.catalog().exists(NULL_TABLE)) {
			engine.catalog().dropTable(NULL_TABLE);
		}
		if (engine != null && engine.catalog().exists(BOOL_TABLE)) {
			engine.catalog().dropTable(BOOL_TABLE);
		}
		engine = null;
		store = null;
	}

	@Benchmark
	public void insertSingle(Blackhole bh) {
		final int id = seq.getAndIncrement();
		bh.consume(engine.execute("INSERT INTO " + TABLE + " VALUES (" + id + ", 'a', 1)").rowsAffected());
	}

	@Benchmark
	public void insertBatch8(Blackhole bh) {
		final int base = seq.getAndAdd(8);
		final StringBuilder sb = new StringBuilder("INSERT INTO ").append(TABLE).append(" VALUES ");
		for (int i = 0; i < 8; i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append('(').append(base + i).append(", 'b', ").append(i).append(')');
		}
		bh.consume(engine.execute(sb.toString()).rowsAffected());
	}

	@Benchmark
	public void updateRmwPlus(Blackhole bh) {
		final int id = ThreadLocalRandom.current().nextInt(0, 500);
		bh.consume(engine.execute("UPDATE " + TABLE + " SET n = n + 1 WHERE id = " + id).rowsAffected());
	}

	@Benchmark
	public void updateMixedRmwLiteral(Blackhole bh) {
		final int id = ThreadLocalRandom.current().nextInt(0, 500);
		bh.consume(engine.execute(
				"UPDATE " + TABLE + " SET n = n + 1, v = 'm' WHERE id = " + id).rowsAffected());
	}

	@Benchmark
	public void updateColumnCopy(Blackhole bh) {
		final int id = ThreadLocalRandom.current().nextInt(0, 500);
		bh.consume(engine.execute(
				"UPDATE " + TABLE + " SET v = v WHERE id = " + id).rowsAffected());
	}

	@Benchmark
	public void updateCaseColPlus(Blackhole bh) {
		final int id = ThreadLocalRandom.current().nextInt(0, 500);
		bh.consume(engine.execute(
				"UPDATE " + TABLE + " SET n = CASE "
						+ "WHEN n = 0 THEN 1 "
						+ "WHEN (n + 1) >= 1000000 THEN 1000000 "
						+ "ELSE (n + 1) END WHERE id = " + id).rowsAffected());
	}

	@Benchmark
	public void updateSetNullLiteral(Blackhole bh) {
		final int id = ThreadLocalRandom.current().nextInt(0, 500);
		bh.consume(engine.execute(
				"UPDATE " + NULL_TABLE + " SET ts = NULL WHERE id = " + id).rowsAffected());
	}

	@Benchmark
	public void updateSetFalseLiteral(Blackhole bh) {
		final int id = ThreadLocalRandom.current().nextInt(0, 500);
		bh.consume(engine.execute(
				"UPDATE " + BOOL_TABLE + " SET flag = FALSE WHERE id = " + id).rowsAffected());
	}

	@Benchmark
	public void deleteByPk(Blackhole bh) {
		final int id = seq.getAndIncrement();
		engine.execute("INSERT INTO " + TABLE + " VALUES (" + id + ", 'd', 0)");
		bh.consume(engine.execute("DELETE FROM " + TABLE + " WHERE id = " + id).rowsAffected());
	}

	@Benchmark
	public void putIndexed(Blackhole bh) {
		final int id = seq.getAndIncrement();
		bh.consume(store.putIndexed(id, "p", id));
	}
}