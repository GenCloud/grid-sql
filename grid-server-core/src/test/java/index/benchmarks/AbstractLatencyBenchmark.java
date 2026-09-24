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

import org.genfork.grid.threading.ThreadService;
import org.junit.jupiter.api.Test;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Fair latency harness: single-thread, forked JVM, moderate warmup/measure.
 * Throughput / index benches keep {@link AbstractBenchmark} (multi-thread).
 * <p>
 * Scheduled {@link ThreadService} threads are daemon; {@link #releaseGridRuntime()} still
 * shuts pools down so forked JMH VMs do not wait on stray {@code DelayedWorkQueue} workers.
 *
 * @author: GenCloud
 * @date: 2026/12
 * @since: 1.0
 */
public abstract class AbstractLatencyBenchmark {
	private static final int WARMUP_ITERATIONS = 2;
	private static final int MEASUREMENT_ITERATIONS = 3;
	private static final int FAST_WARMUP_ITERATIONS = 1;
	private static final int FAST_MEASUREMENT_ITERATIONS = 1;
	private static final int WARMUP_SECONDS = 1;
	private static final int MEASUREMENT_SECONDS = 1;
	private static final String JMH_FAST_PROPERTY = "jmh.fast";
	private static final String JMH_FAST_ENV = "JMH_FAST";

	/**
	 * Smoke / local iteration only ({@code -Djmh.fast=true} or {@code JMH_FAST=1}).
	 * Must not be used for official SUMMARY / QG stamps.
	 */
	protected static boolean jmhFastMode() {
		final String prop = System.getProperty(JMH_FAST_PROPERTY);
		if (prop != null && !prop.isBlank()) {
			return Boolean.parseBoolean(prop.trim());
		}
		final String env = System.getenv(JMH_FAST_ENV);
		return "1".equals(env) || "true".equalsIgnoreCase(env);
	}

	/**
	 * Inherited by {@code @State} subclasses: stop periodic workers / dumpStats and allow forked JMH VM exit.
	 */
	@TearDown(Level.Trial)
	public void releaseGridRuntime() {
		ThreadService.shutdownNow();
	}

	@Test
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
		final String className = getClass().getSimpleName();
		final Path resultFile = resultsDir.resolve(stamp + "-latency-" + className + ".json");
		final boolean fast = jmhFastMode();
		final int warmupIters = fast ? FAST_WARMUP_ITERATIONS : WARMUP_ITERATIONS;
		final int measureIters = fast ? FAST_MEASUREMENT_ITERATIONS : MEASUREMENT_ITERATIONS;
		if (fast) {
			System.out.println("JMH_FAST=1: warmup=" + warmupIters + " measure=" + measureIters
					+ " (smoke only — not a QG stamp)");
		}

		final String includeProp = System.getProperty("jmh.include");
		final String includePattern = (includeProp != null && !includeProp.isBlank())
				? includeProp.trim()
				: ("\\." + className + "\\.");
		final Options opt = new OptionsBuilder()
				.include(includePattern)
				.warmupIterations(warmupIters)
				.warmupTime(TimeValue.seconds(WARMUP_SECONDS))
				.measurementIterations(measureIters)
				.measurementTime(TimeValue.seconds(MEASUREMENT_SECONDS))
				.forks(1)
				.threads(1)
				.shouldDoGC(true)
				.shouldFailOnError(true)
				.resultFormat(ResultFormatType.JSON)
				.result(resultFile.toString())
				.jvmArgs(
						"-server",
						"--add-opens=java.base/java.lang=ALL-UNNAMED",
						"--add-opens=java.base/java.math=ALL-UNNAMED",
						"--add-opens=java.base/java.net=ALL-UNNAMED",
						"--add-opens=java.base/java.text=ALL-UNNAMED",
						"--add-opens=java.base/java.util=ALL-UNNAMED",
						"--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
						"--add-opens=java.base/java.time=ALL-UNNAMED",
						"--add-opens=java.sql/java.sql=ALL-UNNAMED",
						"--add-exports=java.base/java.lang=ALL-UNNAMED",
						"--add-exports=java.base/sun.reflect.generics.reflectiveObjects=ALL-UNNAMED",
						"--add-exports=java.base/jdk.internal.vm.annotation=ALL-UNNAMED",
						"--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
						"--add-modules=jdk.incubator.vector",
						"--enable-preview"
				)
				.build();

		new Runner(opt).run();
		System.out.println("JMH JSON → " + resultFile.toAbsolutePath());
	}
}