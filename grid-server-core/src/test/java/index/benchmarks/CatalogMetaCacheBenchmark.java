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

import org.genfork.grid.catalog.CatalogMetaCache;
import org.genfork.grid.catalog.ColumnDef;
import org.genfork.grid.catalog.SqlType;
import org.genfork.grid.catalog.TableSchema;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * CatalogMetaCache warm vs cold lookup latency.
 *
 * @author: GenCloud
 * @date: 2026/12
 * @since: 1.0
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class CatalogMetaCacheBenchmark extends AbstractLatencyBenchmark {
	private static final String KEY = "bench_table";

	private CatalogMetaCache cache;
	private TableSchema schema;

	@Setup(Level.Trial)
	public void setup() {
		schema = new TableSchema(
				KEY,
				List.of(new ColumnDef("id", SqlType.BIGINT, false, 0, true, false, false, null)),
				List.of(),
				1L
		);
		cache = new CatalogMetaCache(CatalogMetaCache.DEFAULT_SIZE);
		cache.put(KEY, schema);
	}

	@Benchmark
	public Object warmGet() {
		return cache.get(KEY);
	}

	@Benchmark
	public Object coldGet() {
		cache.invalidate(KEY);
		final CatalogMetaCache.CachedMeta miss = cache.get(KEY);
		cache.put(KEY, schema);
		return miss;
	}
}