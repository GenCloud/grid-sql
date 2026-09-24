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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

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

import org.genfork.grid.query.adaptive.AdaptiveParallelScan;
import org.genfork.grid.query.adaptive.HeavyQueryAdmission;
import org.genfork.grid.query.adaptive.QueryHeaviness;
import org.genfork.grid.serial.SqlWireUtil;
import org.genfork.grid.threading.ThreadService;

/**
 * Serial vs adaptive parallel {@link AdaptiveParallelScan#mapMergeKeys} latency.
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
public class AdaptiveParallelScanBenchmark extends AbstractLatencyBenchmark {
	private static final int MAX_CONCURRENT_HEAVY = 8;
	private static final int MAX_WORKERS = 4;
	private static final boolean NOT_INDEXED = false;
	private static final boolean FULLY_INDEXED = true;

	@Param({"10000", "100000"})
	public int count;

	private List<byte[]> keys;
	private QueryHeaviness heavy;
	private QueryHeaviness light;
	private HeavyQueryAdmission admission;
	private Function<List<byte[]>, List<byte[]>> mapper;

	@Setup(Level.Trial)
	public void setupBenchmark() {
		ThreadService.ensureRunning();
		keys = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			keys.add(SqlWireUtil.toGenericArray(i));
		}
		heavy = QueryHeaviness.estimate(QueryHeaviness.heavyCandidateThreshold(), NOT_INDEXED);
		light = QueryHeaviness.estimate(0, FULLY_INDEXED);
		admission = new HeavyQueryAdmission(MAX_CONCURRENT_HEAVY, MAX_WORKERS);
		mapper = chunk -> {
			final List<byte[]> out = new ArrayList<>(chunk.size());
			for (byte[] key : chunk) {
				out.add(key);
			}
			return out;
		};
	}

	@TearDown(Level.Trial)
	public void tearDownBenchmark() {
		if (admission != null) {
			admission.resetInflightForTests();
		}
	}

	@Benchmark
	public void serialMapMerge(Blackhole bh) {
		final List<byte[]> out = AdaptiveParallelScan.mapMergeKeys(keys, mapper, light, admission);
		bh.consume(out.size());
	}

	@Benchmark
	public void parallelMapMerge(Blackhole bh) {
		final List<byte[]> out = AdaptiveParallelScan.mapMergeKeys(keys, mapper, heavy, admission);
		bh.consume(out.size());
	}
}