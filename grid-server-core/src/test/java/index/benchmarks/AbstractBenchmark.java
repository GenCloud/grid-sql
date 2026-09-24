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
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Multi-thread throughput harness (index / search). Latency consensus/log benches
 * use {@link AbstractLatencyBenchmark} instead.
 *
 * @author: GenCloud
 * @date: 2026/12
 * @since: 1.0
 */
public abstract class AbstractBenchmark {
	private static final Integer MEASUREMENT_ITERATIONS = 10;
	private static final Integer WARMUP_ITERATIONS = 5;

	@Test
	public void runJmh() throws RunnerException {
		final Options opt = new OptionsBuilder()
				.include("\\." + getClass().getSimpleName() + "\\.")
				.warmupIterations(WARMUP_ITERATIONS)
				.measurementIterations(MEASUREMENT_ITERATIONS)
				.forks(0)
				.threads(20)
				.shouldDoGC(true)
				.shouldFailOnError(true)
				.resultFormat(ResultFormatType.JSON)
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

		try {
			new Runner(opt).run();
		} finally {
			ThreadService.shutdownNow();
		}
	}
}