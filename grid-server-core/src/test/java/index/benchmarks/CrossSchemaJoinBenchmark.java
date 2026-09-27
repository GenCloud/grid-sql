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
 * Cross-schema EQ JOIN latency stamp (explicit schema.table quals).
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
public class CrossSchemaJoinBenchmark extends AbstractLatencyBenchmark {
	private static final int ROWS = 200;
	private static final int PROBE_ID = 7;

	private SqlEngine engine;

	@Setup(Level.Trial)
	public void setUp() {
		engine = new SqlEngine(new TableCatalog(), null, 4);
		engine.execute("CREATE SCHEMA left_s");
		engine.execute("CREATE SCHEMA right_s");
		engine.execute("CREATE TABLE left_s.a (id INT PRIMARY KEY, k INT)");
		engine.execute("CREATE TABLE right_s.b (id INT PRIMARY KEY, k INT, label VARCHAR)");
		for (int i = 0; i < ROWS; i++) {
			engine.execute("INSERT INTO left_s.a VALUES (" + i + ", " + (i % 50) + ")");
			engine.execute("INSERT INTO right_s.b VALUES (" + i + ", " + (i % 50) + ", 'v" + i + "')");
		}
	}

	@Benchmark
	public int crossSchemaEqJoin() {
		final SqlResult r = engine.execute(
				"SELECT label FROM left_s.a JOIN right_s.b ON k = k WHERE id = " + PROBE_ID);
		return r.rows().size();
	}
}