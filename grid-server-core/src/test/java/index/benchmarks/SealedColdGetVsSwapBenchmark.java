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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlServerRuntime;
import org.junit.jupiter.api.Test;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
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

/**
 * Cold sealed PK get / DELETE under concurrent dump swap — soft-miss must not regress TPS.
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
public class SealedColdGetVsSwapBenchmark {
	private static final String TABLE = "jmh_sealed_cold_get";
	private static final int SHARDS = 4;
	private static final String HYDRATE_LAZY = "LAZY";
	private static final String SELECT_SQL =
			"SELECT var_value FROM " + TABLE + " WHERE owner_id = 1";
	private static final String DELETE_SQL =
			"DELETE FROM " + TABLE + " WHERE owner_id = 2";
	private static final String INSERT_SQL =
			"INSERT INTO " + TABLE + " (owner_id, var_name, var_value) VALUES (2, 'k', 'v')";

	private Path dataDir;
	private SqlServerRuntime runtime;
	private SqlEngine engine;
	private ReplicationCoordinator coord;
	private final AtomicLong closedErrors = new AtomicLong();

	@Setup(Level.Trial)
	public void seed() throws IOException {
		dataDir = Files.createTempDirectory("jmh-sealed-cold");
		runtime = SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(SHARDS)
				.durability(true)
				.hydrateMode(HYDRATE_LAZY)
				.build();
		engine = runtime.engine();
		engine.execute("CREATE TABLE " + TABLE + " ("
				+ "owner_id INT PRIMARY KEY, "
				+ "var_name VARCHAR, "
				+ "var_value VARCHAR)");
		engine.execute("INSERT INTO " + TABLE
				+ " (owner_id, var_name, var_value) VALUES (1, 'k', 'v')");
		engine.execute(INSERT_SQL);
		coord = runtime.replication();
		coord.dumpDomainSnapshot(TABLE);
		engine.execute(SELECT_SQL);
	}

	@TearDown(Level.Trial)
	public void tearDown() throws Exception {
		if (runtime != null) {
			runtime.close();
		}
		if (closedErrors.get() > 0L) {
			throw new IllegalStateException(
					"sealed reader closed during JMH: " + closedErrors.get());
		}
	}

	@Benchmark
	@Group("get_vs_dump")
	@GroupThreads(4)
	public void coldSelect(Blackhole bh) {
		try {
			bh.consume(engine.execute(SELECT_SQL).rows().size());
		} catch (RuntimeException ex) {
			if (ex.getMessage() != null
					&& ex.getMessage().contains("sealed reader is closed")) {
				closedErrors.incrementAndGet();
			}
			throw ex;
		}
	}

	@Benchmark
	@Group("get_vs_dump")
	@GroupThreads(1)
	public void dumpSwap(Blackhole bh) {
		bh.consume(coord.dumpDomainSnapshot(TABLE));
	}

	@Benchmark
	@Group("delete_vs_dump")
	@GroupThreads(2)
	public void deleteWhere(Blackhole bh) {
		try {
			bh.consume(engine.execute(DELETE_SQL).rowsAffected());
			bh.consume(engine.execute(INSERT_SQL).rowsAffected());
		} catch (RuntimeException ex) {
			if (ex.getMessage() != null
					&& ex.getMessage().contains("sealed reader is closed")) {
				closedErrors.incrementAndGet();
			}
			throw ex;
		}
	}

	@Benchmark
	@Group("delete_vs_dump")
	@GroupThreads(1)
	public void dumpSwapDeleteGroup(Blackhole bh) {
		bh.consume(coord.dumpDomainSnapshot(TABLE));
	}

	@Test
	void runJmh() throws Exception {
		final String stamp = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
		final Path out = Path.of("benchmarks", "lab",
				"sealed-cold-get-vs-swap-" + stamp + ".json");
		Files.createDirectories(out.getParent());
		final Options opt = new OptionsBuilder()
				.include(SealedColdGetVsSwapBenchmark.class.getSimpleName())
				.warmupIterations(1)
				.warmupTime(TimeValue.seconds(1))
				.measurementIterations(2)
				.measurementTime(TimeValue.seconds(1))
				.forks(0)
				.resultFormat(ResultFormatType.JSON)
				.result(out.toString())
				.build();
		new Runner(opt).run();
	}
}
