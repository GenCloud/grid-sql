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

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.genfork.grid.sql.SqlRouteClassifier;
import org.genfork.grid.sql.SqlScriptStatements;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

/**
 * Tooling microbench: ANTLR script split + route classify (JDBC / Sync path).
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
public class JdbcScriptSplitBenchmark extends AbstractBenchmark {
	private static final String SCRIPT =
			"INSERT INTO t (id) VALUES (1); SELECT id FROM t WHERE id = 1; EXPLAIN SELECT id FROM t";

	@Benchmark
	public int splitAndClassify() {
		final List<String> parts = SqlScriptStatements.splitExecutables(SCRIPT);
		int writes = 0;
		for (String part : parts) {
			if (SqlRouteClassifier.classify(part) == SqlRouteClassifier.Route.WRITE) {
				writes++;
			}
		}
		return writes + parts.size();
	}
}
