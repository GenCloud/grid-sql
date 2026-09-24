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

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import index.sql.SqlBenchHelper;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlSession;
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

/**
 * JMH: row/statement trigger dispatch and explicit-TX auto-SAVEPOINT overhead.
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
public class SqlTriggerBenchmark extends AbstractLatencyBenchmark {
	private static final int SHARDS = 4;
	private static final String PARENT = "trg_parent";
	private static final String CHILD = "trg_child";
	private static final String STATEMENT_SOURCE = "trg_statement_source";
	private static final int MISSING_PARENT_ID = -1;

	private SqlEngine engine;
	private final AtomicInteger seq = new AtomicInteger(1);

	@Setup(Level.Trial)
	public void setupBenchmark() {
		engine = SqlBenchHelper.createEngine(SHARDS);
		engine.execute("CREATE TABLE " + PARENT + " (id INT PRIMARY KEY)");
		engine.execute("CREATE TABLE " + CHILD + " (id INT PRIMARY KEY, parent_id INT)");
		engine.execute(
				"CREATE TRIGGER trg_del BEFORE DELETE ON " + PARENT + " FOR EACH ROW "
						+ "AS 'DELETE FROM " + CHILD + " WHERE id = OLD.id'");
		engine.execute("CREATE TABLE " + STATEMENT_SOURCE + " (id INT PRIMARY KEY)");
		engine.execute(
				"CREATE TRIGGER trg_statement AFTER INSERT ON " + STATEMENT_SOURCE + " FOR EACH STATEMENT "
						+ "AS 'DELETE FROM " + CHILD + " WHERE parent_id = " + MISSING_PARENT_ID + "'");
	}

	@TearDown(Level.Trial)
	public void tearDownBenchmark() {
		SqlBenchHelper.closeAllStores(engine);
	}

	@Benchmark
	public void deleteWithTrigger(Blackhole bh) {
		final int id = seq.getAndIncrement();
		engine.execute("INSERT INTO " + PARENT + " (id) VALUES (" + id + ")");
		engine.execute("INSERT INTO " + CHILD + " (id, parent_id) VALUES (" + id + ", " + id + ")");
		bh.consume(engine.execute("DELETE FROM " + PARENT + " WHERE id = " + id).rowsAffected());
	}

	@Benchmark
	public void insertWithStatementTrigger(Blackhole bh) {
		final int id = seq.getAndIncrement();
		bh.consume(engine.execute("INSERT INTO " + STATEMENT_SOURCE + " VALUES (" + id + ")").rowsAffected());
	}

	@Benchmark
	public void deleteWithTriggerAndSavepoint(Blackhole bh) {
		final int id = seq.getAndIncrement();
		engine.execute("INSERT INTO " + PARENT + " (id) VALUES (" + id + ")");
		engine.execute("INSERT INTO " + CHILD + " (id, parent_id) VALUES (" + id + ", " + id + ")");
		final SqlSession session = engine.newSession();
		engine.execute(session, "BEGIN");
		bh.consume(engine.execute(session, "DELETE FROM " + PARENT + " WHERE id = " + id).rowsAffected());
		engine.execute(session, "ROLLBACK");
	}
}