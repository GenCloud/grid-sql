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

import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlServerRuntime;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * LAZY hydrate SELECT stamp: cold SELECT * after reopen vs warm after first hydrate.
 * First calm stamp becomes +/-5% baseline for this track.
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
public class LazySelectHydrateBenchmark extends AbstractLatencyBenchmark {

	private static final String TABLE = "lazy_jmh";
	private static final int SHARDS = 4;
	private static final int SEED_ROWS = Integer.getInteger("grid.lazy.jmh.rows", 1_024);
	private static final String HYDRATE_LAZY = "LAZY";

	private Path dataDir;
	private SqlServerRuntime warmRuntime;
	private SqlEngine warmEngine;

	@Setup(Level.Trial)
	public void seedAndWarm() throws IOException {
		dataDir = Files.createTempDirectory("lazy-select-jmh");
		try (SqlServerRuntime writer = openLazy(dataDir)) {
			final SqlEngine engine = writer.engine();
			engine.execute("CREATE TABLE " + TABLE + " (id INT PRIMARY KEY, v VARCHAR)");
			for (int i = 0; i < SEED_ROWS; i++) {
				engine.execute("INSERT INTO " + TABLE + " (id, v) VALUES (" + i + ", 'r" + i + "')");
			}
		}
		warmRuntime = openLazy(dataDir);
		warmEngine = warmRuntime.engine();
		warmEngine.execute("SELECT id, v FROM " + TABLE);
	}

	@TearDown(Level.Trial)
	public void tearDown() {
		if (warmRuntime != null) {
			warmRuntime.close();
			warmRuntime = null;
			warmEngine = null;
		}
	}

	/**
	 * Includes LAZY reopen + first SELECT * (hydrate + sync reindex).
	 */
	@Benchmark
	@BenchmarkMode(Mode.SingleShotTime)
	@OutputTimeUnit(TimeUnit.MILLISECONDS)
	@Warmup(iterations = 0)
	@Measurement(iterations = 8)
	public void selectStarColdAfterReopen(Blackhole bh) {
		try (SqlServerRuntime runtime = openLazy(dataDir)) {
			bh.consume(runtime.engine().execute("SELECT id, v FROM " + TABLE).rows().size());
		}
	}

	/**
	 * Control: SELECT * after shards already hydrated (idempotent skip path).
	 */
	@Benchmark
	public void selectStarWarm(Blackhole bh) {
		bh.consume(warmEngine.execute("SELECT id, v FROM " + TABLE).rows().size());
	}

	private static SqlServerRuntime openLazy(Path dir) {
		return SqlServerRuntime.builder()
				.dataDir(dir)
				.shards(SHARDS)
				.durability(true)
				.hydrateMode(HYDRATE_LAZY)
				.build();
	}
}