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

import java.util.Map;
import java.util.concurrent.TimeUnit;

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
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import org.genfork.grid.catalog.TableAnalyzeStats;
import org.genfork.grid.query.adaptive.QueryHeaviness;
import org.genfork.grid.query.adaptive.QueryHeavinessEstimator;
import org.genfork.grid.query.filters.impl.AlwaysTrueCondition;
import org.genfork.grid.query.filters.impl.AndCondition;
import org.genfork.grid.query.filters.impl.LogicalOperatorCondition;

/**
 * {@link QueryHeavinessEstimator#estimateFilter} latency with fake ANALYZE stats.
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
public class QueryHeavinessEstimatorBenchmark extends AbstractLatencyBenchmark {
	private static final long TABLE_ROWS = QueryHeaviness.distributedCandidateThreshold();
	private static final long FAN_OUT_VAL = 128L;
	private static final long FAN_OUT_FLAG = 64L;

	private TableAnalyzeStats stats;
	private LogicalOperatorCondition eq;
	private AndCondition and;
	private AlwaysTrueCondition alwaysTrue;

	@Setup(Level.Trial)
	public void setupBenchmark() {
		stats = new TableAnalyzeStats(
				TABLE_ROWS,
				Map.of("val", FAN_OUT_VAL, "flag", FAN_OUT_FLAG)
		);
		eq = new LogicalOperatorCondition("val", LogicalOperatorCondition.Operator.EQ, 1);
		and = new AndCondition(
				eq,
				new LogicalOperatorCondition("flag", LogicalOperatorCondition.Operator.EQ, 0)
		);
		alwaysTrue = AlwaysTrueCondition.getInstance();
	}

	@Benchmark
	public void estimateAlwaysTrue(Blackhole bh) {
		bh.consume(QueryHeavinessEstimator.estimateFilter(stats, alwaysTrue, TABLE_ROWS));
	}

	@Benchmark
	public void estimateEq(Blackhole bh) {
		bh.consume(QueryHeavinessEstimator.estimateFilter(stats, eq, TABLE_ROWS));
	}

	@Benchmark
	public void estimateAnd(Blackhole bh) {
		bh.consume(QueryHeavinessEstimator.estimateFilter(stats, and, TABLE_ROWS));
	}
}
