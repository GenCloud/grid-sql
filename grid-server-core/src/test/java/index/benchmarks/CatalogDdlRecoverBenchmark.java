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

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.sql.SqlEngine;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Catalog recover + ddl.sql compaction latency (bootstrap path, not query hot-path).
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
public class CatalogDdlRecoverBenchmark extends AbstractLatencyBenchmark {
	private static final int SPAM_ROUNDS = 40;
	private static final String SCHEMA = "bench_app";
	private static final String TABLE = "bench_app.t";

	private Path bloatedDir;
	private Path compactedDir;

	@Setup(Level.Trial)
	public void setup() throws Exception {
		bloatedDir = Files.createTempDirectory("grid-ddl-bloat");
		final SqlEngine seed = new SqlEngine(new TableCatalog(bloatedDir), null, 2);
		for (int i = 0; i < SPAM_ROUNDS; i++) {
			seed.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA);
			seed.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " (id INT PRIMARY KEY, v INT)");
		}
		/* Compacted sibling: recover once then copy compacted catalog tree. */
		compactedDir = Files.createTempDirectory("grid-ddl-compact");
		copyCatalog(bloatedDir, compactedDir);
		final SqlEngine compactOnce = new SqlEngine(new TableCatalog(compactedDir), null, 2);
		compactOnce.recoverPersistedCatalog();
	}

	@TearDown(Level.Trial)
	public void tearDown() throws Exception {
		deleteRecursive(bloatedDir);
		deleteRecursive(compactedDir);
	}

	@Benchmark
	public Object recoverBloated() throws Exception {
		final Path work = Files.createTempDirectory("grid-ddl-bloat-run");
		try {
			copyCatalog(bloatedDir, work);
			final SqlEngine engine = new SqlEngine(new TableCatalog(work), null, 2);
			engine.recoverPersistedCatalog();
			return engine.catalog().exists(TABLE);
		} finally {
			deleteRecursive(work);
		}
	}

	@Benchmark
	public Object recoverCompacted() throws Exception {
		final Path work = Files.createTempDirectory("grid-ddl-compact-run");
		try {
			copyCatalog(compactedDir, work);
			final SqlEngine engine = new SqlEngine(new TableCatalog(work), null, 2);
			engine.recoverPersistedCatalog();
			return engine.catalog().exists(TABLE);
		} finally {
			deleteRecursive(work);
		}
	}

	private static void copyCatalog(Path fromRoot, Path toRoot) throws Exception {
		final Path from = fromRoot.resolve("catalog");
		final Path to = toRoot.resolve("catalog");
		Files.createDirectories(to);
		if (!Files.isDirectory(from)) {
			return;
		}
		try (java.util.stream.Stream<Path> stream = Files.list(from)) {
			for (Path p : stream.toList()) {
				Files.copy(p, to.resolve(p.getFileName().toString()));
			}
		}
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
					/* best-effort */
				}
			});
		}
	}
}
