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

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.query.plan.QueryParser;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.concurrent.TimeUnit;

/**
 * WHERE fragment → keysMatching (native FilterCondition path) latency stamp.
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
public class ExpressionFilterKeysBenchmark extends AbstractLatencyBenchmark {
	private static final int ROWS = 500;
	private static final int PROBE = 42;

	private SqlEngine engine;

	@Setup(Level.Trial)
	public void setUp() {
		engine = new SqlEngine(new TableCatalog(), null, 4);
		engine.execute("CREATE TABLE t (id INT PRIMARY KEY, v INT)");
		for (int i = 0; i < ROWS; i++) {
			engine.execute("INSERT INTO t VALUES (" + i + ", " + (i * 2) + ")");
		}
		QueryParser.parseExpression("id = " + PROBE);
	}

	@Benchmark
	public long updateByWhereFragment() {
		final SqlResult r = engine.execute("UPDATE t SET v = v + 1 WHERE id = " + PROBE);
		return r.rowsAffected();
	}
}