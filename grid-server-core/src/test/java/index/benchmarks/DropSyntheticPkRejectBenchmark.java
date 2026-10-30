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

import java.util.concurrent.TimeUnit;

import org.genfork.grid.catalog.CatalogPersistUtil;
import org.genfork.grid.catalog.SyntheticPkIndexUtil;
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
import org.openjdk.jmh.infra.Blackhole;

/**
 * O(1) fail-closed DROP of synthetic {@code table_pk} (no seal / map-scan).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
public class DropSyntheticPkRejectBenchmark extends AbstractLatencyBenchmark {
	private static final String TABLE = "drop_synth_pk_bench";
	private static final int SHARD_COUNT = 2;

	private SqlEngine engine;
	private String pkIndexName;
	private String dropSql;

	@Setup(Level.Trial)
	public void setup() {
		engine = new SqlEngine(new TableCatalog(), null, SHARD_COUNT);
		engine.execute("CREATE TABLE " + TABLE + " (id BIGINT PRIMARY KEY, v INT)");
		engine.execute("INSERT INTO " + TABLE + " VALUES (1, 10)");
		pkIndexName = CatalogPersistUtil.syntheticPkIndexName(TABLE);
		dropSql = "DROP INDEX " + pkIndexName + " ON " + TABLE;
	}

	/**
	 * Reject path only — PK tree must remain; consume whether message matched.
	 */
	@Benchmark
	public void dropSyntheticPkRejected(Blackhole bh) {
		try {
			engine.execute(dropSql);
			bh.consume(false);
		} catch (IllegalStateException ex) {
			bh.consume(ex.getMessage() != null
					&& ex.getMessage().contains(SyntheticPkIndexUtil.MSG_CANNOT_DROP_PRIMARY_KEY_INDEX));
		}
	}
}
