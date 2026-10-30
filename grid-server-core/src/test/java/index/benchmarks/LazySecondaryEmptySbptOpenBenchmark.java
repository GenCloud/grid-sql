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

import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.replication.snapshot.sealed.SealedBPTreeWriter;
import org.genfork.grid.replication.snapshot.sealed.SealedShardPack;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlServerRuntime;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * LAZY secondary EQ: full {@code .sbpt} vs empty-shell heal via sealed dump on hydrate.
 * <p>
 * Happy-path ({@code full}) is the living floor for lobby-shaped EQ after reopen.
 * {@code empty_sbpt} measures cold dump-heal cost once; warm EQ after touch must stay on floor.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@Threads(1)
@State(Scope.Benchmark)
public class LazySecondaryEmptySbptOpenBenchmark extends AbstractLatencyBenchmark {

	private static final String TABLE = "lazy_sbpt_jmh";
	private static final String INDEX = "lazy_sbpt_jmh_acct";
	private static final String ACCOUNT_COL = "account_name";
	private static final String ACCOUNT = "acc0";
	private static final int SHARDS = 4;
	private static final int SEED_ROWS = Integer.getInteger("grid.lazy.sbpt.jmh.rows", 256);
	private static final String HYDRATE_LAZY = "LAZY";
	private static final String MODE_FULL = "full";
	private static final String MODE_EMPTY_SBPT = "empty_sbpt";

	@Param({MODE_FULL, MODE_EMPTY_SBPT})
	public String accelerator;

	private Path dataDir;
	private SqlServerRuntime warmRuntime;
	private SqlEngine warmEngine;

	@Setup(Level.Trial)
	public void seed() throws IOException {
		dataDir = Files.createTempDirectory("lazy-sbpt-jmh-" + accelerator);
		try (SqlServerRuntime writer = openLazy(dataDir)) {
			final SqlEngine engine = writer.engine();
			engine.execute("CREATE TABLE " + TABLE
					+ " (id INT PRIMARY KEY, account_name VARCHAR, server_id INT)");
			engine.execute("CREATE INDEX " + INDEX + " ON " + TABLE + " (account_name)");
			for (int i = 0; i < SEED_ROWS; i++) {
				engine.execute("INSERT INTO " + TABLE
						+ " (id, account_name, server_id) VALUES ("
						+ i + ", 'acc" + i + "', 3)");
			}
			final ReplicationCoordinator coord = writer.replication();
			if (coord != null) {
				coord.dumpDomainSnapshot(TABLE);
			}
		}
	}

	/**
	 * Re-arm empty {@code .sbpt} shells each iteration (shutdown seal would refill them),
	 * then open a warm runtime for the control EQ benchmark.
	 */
	@Setup(Level.Iteration)
	public void prepareIteration() throws IOException {
		if (warmRuntime != null) {
			warmRuntime.close();
			warmRuntime = null;
			warmEngine = null;
		}
		if (MODE_EMPTY_SBPT.equals(accelerator)) {
			blankAccountNameSbpt(dataDir);
		}
		warmRuntime = openLazy(dataDir);
		warmEngine = warmRuntime.engine();
		warmEngine.execute(lobbySql());
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
	 * Cold LAZY reopen + first lobby-shaped secondary EQ (includes dump-heal when empty shell armed).
	 */
	@Benchmark
	@BenchmarkMode(Mode.SingleShotTime)
	@OutputTimeUnit(TimeUnit.MILLISECONDS)
	@Warmup(iterations = 0)
	@Measurement(iterations = 6)
	public void secondaryEqColdAfterReopen(Blackhole bh) {
		try (SqlServerRuntime runtime = openLazy(dataDir)) {
			bh.consume(runtime.engine().execute(lobbySql()).rows().size());
		}
	}

	/**
	 * Control after shard already touched — must stay on living floor for both modes.
	 */
	@Benchmark
	public void secondaryEqWarm(Blackhole bh) {
		bh.consume(warmEngine.execute(lobbySql()).rows().size());
	}

	private static String lobbySql() {
		return "SELECT id FROM " + TABLE
				+ " WHERE account_name = '" + ACCOUNT + "' AND server_id = 3";
	}

	private static SqlServerRuntime openLazy(Path dir) {
		return SqlServerRuntime.builder()
				.dataDir(dir)
				.shards(SHARDS)
				.durability(true)
				.hydrateMode(HYDRATE_LAZY)
				.build();
	}

	private static void blankAccountNameSbpt(Path dataDir) throws IOException {
		final String hex = SealedShardPack.domainHex(TABLE);
		final String needle = "_idx_" + ACCOUNT_COL + ".sbpt";
		try (Stream<Path> walk = Files.walk(dataDir)) {
			final List<Path> targets = walk
					.filter(p -> {
						final String name = p.getFileName().toString();
						return name.startsWith(hex + "_") && name.endsWith(needle);
					})
					.toList();
			for (Path sbpt : targets) {
				final String name = sbpt.getFileName().toString();
				final int idxStart = name.indexOf("_idx_");
				final int shard = Integer.parseInt(name.substring(hex.length() + 1, idxStart));
				SealedBPTreeWriter.write(sbpt, TABLE, shard, ACCOUNT_COL, List.of());
			}
		}
	}
}
