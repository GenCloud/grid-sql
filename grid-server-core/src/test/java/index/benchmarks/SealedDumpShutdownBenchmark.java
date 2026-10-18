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

import org.genfork.grid.mem.GridScalableMap;
import org.genfork.grid.mem.stage.GridEntriesProcessor;
import org.genfork.grid.replication.ReplicationNodeState;
import org.genfork.grid.replication.log.OpLog;
import org.genfork.grid.replication.snapshot.sealed.SealedGridMapService;
import org.genfork.grid.threading.ThreadService;
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
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

/**
 * Shutdown-style sealed dump latency (same path as graceful stop; no COMMIT hot path).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@Threads(1)
@State(Scope.Benchmark)
public class SealedDumpShutdownBenchmark extends AbstractLatencyBenchmark {
	private static final String DOMAIN = "seal_dump_shutdown_bench";
	private static final int SHARD = 0;
	private static final int ROW_COUNT = 64;

	private Path sealedRoot;
	private Path oplogRoot;
	private OpLog opLog;
	private SealedGridMapService sealed;
	private ReplicationNodeState state;

	@Setup(Level.Trial)
	public void setupBenchmark() throws Exception {
		final ch.qos.logback.classic.Logger diag =
				(ch.qos.logback.classic.Logger) LoggerFactory.getLogger("org.genfork.grid.diag");
		diag.setLevel(ch.qos.logback.classic.Level.WARN);

		sealedRoot = Files.createTempDirectory("sealed-dump-shutdown-jmh");
		oplogRoot = Files.createTempDirectory("oplog-dump-shutdown-jmh");
		opLog = new OpLog(oplogRoot, false);
		state = new ReplicationNodeState("jmh-s", "seal-stop", "dc-a", 1L);
		final GridEntriesProcessor proc = new GridEntriesProcessor(SHARD, new GridScalableMap(), null, null);
		sealed = new SealedGridMapService(sealedRoot);
		sealed.bindProcessor(DOMAIN, shard -> shard == SHARD ? proc : null);
		for (int i = 0; i < ROW_COUNT; i++) {
			final byte[] key = ("k" + i).getBytes(StandardCharsets.UTF_8);
			proc.installCommitted(key, ("v" + i).getBytes(StandardCharsets.UTF_8), false);
		}
		sealed.dumpDomain(DOMAIN, opLog, state, null);
	}

	@TearDown(Level.Trial)
	public void tearDownBenchmark() throws Exception {
		if (opLog != null) {
			opLog.close();
		}
		if (sealed != null) {
			sealed.closeAll();
		}
		deleteRecursive(sealedRoot);
		deleteRecursive(oplogRoot);
		ThreadService.shutdownNow();
	}

	@Benchmark
	public void dumpShutdownStyle(Blackhole bh) {
		bh.consume(sealed.dumpDomain(DOMAIN, opLog, state, null));
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
		final Path resultFile = resultsDir.resolve(stamp + "-avgt-SealedDumpShutdownBenchmark.json");
		final boolean fast = jmhFastMode();
		final Options opt = new OptionsBuilder()
				.include("\\.SealedDumpShutdownBenchmark\\.")
				.warmupIterations(fast ? 1 : 1)
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
		try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
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
