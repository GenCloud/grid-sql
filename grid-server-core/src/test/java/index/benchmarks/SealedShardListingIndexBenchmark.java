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

import org.genfork.grid.replication.snapshot.sealed.SealedShardListingIndexUtil;
import org.genfork.grid.replication.snapshot.sealed.SealedShardPack;
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
import java.util.concurrent.TimeUnit;

/**
 * Sealed listing index lookup vs cold DirectoryStream on a dense sealed root.
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
public class SealedShardListingIndexBenchmark {
	private static final String DOMAIN = "bench_listing_idx";
	private static final int SHARD_COUNT = 64;
	private static final String RESULTS_JSON =
			"benchmarks/results/2026-11-10-thrpt-SealedShardListingIndexBenchmark.json";

	private Path sealedRoot;

	@Setup(Level.Trial)
	public void setup() throws IOException {
		sealedRoot = Files.createTempDirectory("sealed-listing-idx-jmh");
		final String hex = SealedShardPack.domainHex(DOMAIN);
		for (int shard = 0; shard < SHARD_COUNT; shard++) {
			Files.write(sealedRoot.resolve(hex + "_" + shard + "_n0.gmap"), new byte[]{1, 2});
			Files.writeString(sealedRoot.resolve(hex + "_" + shard + "_idx.sbpt"), "i");
		}
		SealedShardListingIndexUtil.rebuildAndInstall(sealedRoot);
	}

	@TearDown(Level.Trial)
	public void tearDown() throws IOException {
		SealedShardListingIndexUtil.invalidate();
		if (sealedRoot != null) {
			Files.walk(sealedRoot)
					.sorted((a, b) -> b.compareTo(a))
					.forEach(p -> {
						try {
							Files.deleteIfExists(p);
						} catch (IOException ignored) {
						}
					});
		}
	}

	@Benchmark
	public void indexedListAllShards(Blackhole bh) throws IOException {
		for (int shard = 0; shard < SHARD_COUNT; shard++) {
			bh.consume(SealedShardPack.listShardFiles(sealedRoot, DOMAIN, shard));
		}
	}

	@Benchmark
	public void unindexedListAllShards(Blackhole bh) throws IOException {
		for (int shard = 0; shard < SHARD_COUNT; shard++) {
			bh.consume(SealedShardListingIndexUtil.listShardFilesUnindexed(sealedRoot, DOMAIN, shard));
		}
	}

	@Test
	void run() throws Exception {
		final Options opt = new OptionsBuilder()
				.include(SealedShardListingIndexBenchmark.class.getSimpleName())
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
