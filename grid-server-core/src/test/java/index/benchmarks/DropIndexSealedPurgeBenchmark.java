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
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * CREATE UNIQUE + seal dump + DROP INDEX (sealed purge) latency track.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
public class DropIndexSealedPurgeBenchmark extends AbstractLatencyBenchmark {
	private static final String TABLE = "drop_idx_bench";
	private static final String INDEX_PREFIX = "drop_idx_bench_u_";
	private static final int SHARDS = 2;

	private Path dataDir;
	private SqlServerRuntime runtime;
	private SqlEngine engine;
	private final AtomicLong indexSeq = new AtomicLong();

	@Setup(Level.Trial)
	public void setup() throws Exception {
		dataDir = Files.createTempDirectory("grid-drop-idx-jmh");
		runtime = SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(SHARDS)
				.durability(true)
				.hydrateMode("FULL")
				.build();
		engine = runtime.engine();
		engine.execute("CREATE TABLE " + TABLE
				+ " (id BIGINT PRIMARY KEY, owner_id BIGINT, item_id INT, location_data VARCHAR)");
		engine.execute("INSERT INTO " + TABLE + " VALUES (1, 10, 57, 'INVENTORY')");
	}

	@TearDown(Level.Trial)
	public void tearDown() throws Exception {
		if (runtime != null) {
			runtime.close();
		}
		deleteRecursive(dataDir);
	}

	@Benchmark
	public Object createDumpDropUnique() {
		final String indexName = INDEX_PREFIX + indexSeq.incrementAndGet();
		engine.execute("CREATE UNIQUE INDEX " + indexName + " ON " + TABLE
				+ " (owner_id, item_id, location_data)");
		runtime.ownedReplication().dumpDomainSnapshot(TABLE);
		engine.execute("DROP INDEX " + indexName + " ON " + TABLE);
		return indexName;
	}

	private static void deleteRecursive(Path root) throws IOException {
		if (root == null || !Files.exists(root)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(root)) {
			walk.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException ignored) {
					// best-effort temp cleanup
				}
			});
		}
	}
}
