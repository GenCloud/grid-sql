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

import org.genfork.grid.mem.index.IndexPointerRef;
import org.genfork.grid.mem.index.bitmap.GridBitmapIndex;
import org.genfork.grid.mem.index.btree.SingleTreeKey;
import org.genfork.grid.replication.snapshot.sealed.SealedBitmapService;
import org.genfork.grid.replication.snapshot.sealed.SealedGridMapService;
import org.genfork.grid.replication.snapshot.sealed.SealedPackFingerprint;
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
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Pack / fingerprint thrpt for sealed migrate CATCH_UP ship path.
 *
 * @author: GenCloud
 * @date: 2026/05
 * @since: 1.0
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@Threads(1)
@State(Scope.Benchmark)
public class SealedShardPackBenchmark extends AbstractLatencyBenchmark {
	private static final String DOMAIN = "sealed-pack-bench";
	private static final int SHARD = 0;
	private static final String BITMAP_PROPERTY = "status";
	private static final int BITMAP_CAPACITY = 64;
	private static final int ARTIFACT_ROWS = 32;

	private Path sealedRoot;
	private SealedGridMapService sealed;
	private List<Path> listed;
	private long fingerprint;

	@Setup(Level.Trial)
	public void setup() throws Exception {
		sealedRoot = Files.createTempDirectory("sealed-pack-jmh");
		sealed = new SealedGridMapService(sealedRoot);
		final SealedBitmapService bitmaps = sealed.sealedBitmapService();
		final GridBitmapIndex bitmap = new GridBitmapIndex(BITMAP_PROPERTY + "-BITMAP", BITMAP_CAPACITY);
		for (int i = 0; i < ARTIFACT_ROWS; i++) {
			final byte[] key = ("k" + i).getBytes(StandardCharsets.UTF_8);
			bitmap.insert(new SingleTreeKey(key), new IndexPointerRef((long) i, key));
		}
		bitmaps.dumpIndex(DOMAIN, SHARD, BITMAP_PROPERTY, bitmap);
		listed = SealedShardPack.listShardFiles(sealedRoot, DOMAIN, SHARD);
		fingerprint = SealedPackFingerprint.ofFiles(listed);
	}

	@TearDown(Level.Trial)
	public void tearDown() throws Exception {
		releaseGridRuntime();
		if (sealedRoot != null) {
			deleteRecursive(sealedRoot);
		}
	}

	@Benchmark
	public void packListed(Blackhole bh) throws Exception {
		bh.consume(SealedShardPack.packListed(listed));
	}

	@Benchmark
	public void fingerprintOfFiles(Blackhole bh) throws Exception {
		bh.consume(SealedPackFingerprint.ofFiles(listed));
	}

	@Benchmark
	public void skipUnchangedFingerprint(Blackhole bh) throws Exception {
		final long again = SealedPackFingerprint.ofFiles(listed);
		bh.consume(again == fingerprint);
	}

	@Test
	@Override
	public void runJmh() throws Exception {
		final Path resultsDir = Path.of("benchmarks", "results");
		Files.createDirectories(resultsDir);
		final String date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
		String stamp = System.getProperty("jmh.result.stamp");
		if (stamp == null || stamp.isBlank()) {
			stamp = System.getenv("JMH_STAMP");
		}
		if (stamp == null || stamp.isBlank()) {
			stamp = date;
		}
		final Path resultFile = resultsDir.resolve(stamp + "-thrpt-SealedShardPackBenchmark.json");
		final boolean fast = jmhFastMode();
		final Options opt = new OptionsBuilder()
				.include("\\.SealedShardPackBenchmark\\.")
				.warmupIterations(fast ? 1 : 2)
				.measurementIterations(fast ? 1 : 3)
				.warmupTime(TimeValue.seconds(1))
				.measurementTime(TimeValue.seconds(1))
				.forks(1)
				.threads(1)
				.resultFormat(ResultFormatType.JSON)
				.result(resultFile.toString())
				.build();
		new Runner(opt).run();
	}

	private static void deleteRecursive(Path root) throws Exception {
		if (root == null || !Files.exists(root)) {
			return;
		}
		try (var walk = Files.walk(root)) {
			walk.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (Exception ignored) {
					// best-effort cleanup
				}
			});
		}
	}
}