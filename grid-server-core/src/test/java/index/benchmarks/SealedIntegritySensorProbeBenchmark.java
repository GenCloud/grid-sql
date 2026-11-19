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

import org.genfork.grid.replication.snapshot.sealed.SealedShardPack;
import org.genfork.grid.replication.util.SealedIntegrityProbeCacheUtil;
import org.genfork.grid.replication.util.SealedIntegritySensorUtil;
import org.junit.jupiter.api.Test;
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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Sealed integrity sensor: list+probe vs fast-path skip gate.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class SealedIntegritySensorProbeBenchmark {
	private static final String DOMAIN = "bench_sensor_probe";
	private static final int SHARD = 1;
	private static final long FP = 99L;
	private static final String RESULTS_JSON =
			"benchmarks/results/2026-11-10-thrpt-SealedIntegritySensorProbeBenchmark.json";

	private Path sealedRoot;
	private List<Path> listed;

	@Setup(Level.Trial)
	public void setup() throws IOException {
		sealedRoot = Files.createTempDirectory("sealed-sensor-probe-jmh");
		final String hex = SealedShardPack.domainHex(DOMAIN);
		Files.write(sealedRoot.resolve(hex + "_" + SHARD + "_n0.gmap"),
				new byte[SealedIntegritySensorUtil.GMAP_FIXED_HEADER_BYTES]);
		Files.writeString(sealedRoot.resolve(hex + "_" + SHARD + "_idx_id.sbpt"), "i");
		listed = SealedShardPack.listShardFiles(sealedRoot, DOMAIN, SHARD);
	}

	@TearDown(Level.Trial)
	public void tearDown() throws IOException {
		if (sealedRoot != null) {
			Files.walk(sealedRoot)
					.sorted((a, b) -> b.compareTo(a))
					.forEach(path -> {
						try {
							Files.deleteIfExists(path);
						} catch (IOException ignored) {
							// best-effort
						}
					});
		}
	}

	@Benchmark
	public void probeAllGmapHeadersDisk(Blackhole blackhole) {
		blackhole.consume(SealedIntegritySensorUtil.probeAllGmapHeaders(sealedRoot, DOMAIN, SHARD));
	}

	@Benchmark
	public void probeAllGmapHeadersListed(Blackhole blackhole) {
		blackhole.consume(SealedIntegritySensorUtil.probeAllGmapHeadersListed(listed));
	}

	@Benchmark
	public void canSkipStructuralDiskProbe(Blackhole blackhole) {
		blackhole.consume(SealedIntegrityProbeCacheUtil.canSkipStructuralDiskProbe(
				false, Boolean.FALSE, FP, FP));
	}

	@Test
	void runBenchmark() throws Exception {
		Files.createDirectories(Path.of("benchmarks/results"));
		final Options opt = new OptionsBuilder()
				.include(SealedIntegritySensorProbeBenchmark.class.getSimpleName())
				.warmupIterations(1)
				.warmupTime(TimeValue.seconds(1))
				.measurementIterations(3)
				.measurementTime(TimeValue.seconds(1))
				.forks(1)
				.resultFormat(ResultFormatType.JSON)
				.result(RESULTS_JSON)
				.build();
		new Runner(opt).run();
	}
}
