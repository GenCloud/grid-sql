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

import index.sql.SqlBenchHelper;
import org.genfork.grid.catalog.IndexDef;
import org.genfork.grid.catalog.SqlType;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.mem.index.IndexType;
import org.genfork.grid.query.plan.QueryParser;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlSession;
import org.genfork.grid.store.TableStore;
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

import java.util.concurrent.TimeUnit;

/**
 * JMH: TX snapshot SELECT via indexed EQ vs full PK residual baseline.
 *
 * @author: GenCloud
 * @date: 2026/12
 * @since: 1.0
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@Threads(1)
@State(Scope.Benchmark)
public class SnapshotIndexedBenchmark extends AbstractLatencyBenchmark {
	private static final String TABLE = "snap_rows";
	private static final String HIT = "HIT";
	private static final int HIT_COUNT = 64;
	private static final int SHARDS = 4;

	@Param({"10000", "100000"})
	public int count;

	private SqlEngine engine;
	private TableStore store;
	private String indexedSql;
	private String fullScanSql;

	@Setup(Level.Trial)
	public void setupBenchmark() {
		QueryParser.clearPlanCache();
		engine = SqlBenchHelper.createEngine(SHARDS);
		SqlBenchHelper.ensureIndexedTable(engine, TableSchema.builder(TABLE)
				.primaryKey("id", SqlType.INT)
				.column("state", SqlType.VARCHAR)
				.column("flag", SqlType.INT)
				.index(IndexDef.of("idx_state", IndexType.LAX, "state"))
				.build());
		store = SqlBenchHelper.store(engine, TABLE);
		for (int i = 0; i < count; i++) {
			final String state = i < HIT_COUNT ? HIT : "MISS";
			SqlBenchHelper.putIndexedRow(engine, TABLE, i, state, i);
		}
		indexedSql = "SELECT id FROM " + TABLE + " WHERE state = '" + HIT + "'";
		fullScanSql = "SELECT id FROM " + TABLE + " WHERE flag >= 0";
	}

	@TearDown(Level.Trial)
	public void tearDownBenchmark() {
		SqlBenchHelper.closeAllStores(engine);
	}

	@Benchmark
	public void snapshotIndexedEq(Blackhole bh) {
		final SqlSession session = engine.newSession();
		engine.execute(session, "BEGIN");
		try {
			bh.consume(engine.execute(session, indexedSql).rows().size());
		} finally {
			engine.execute(session, "ROLLBACK");
		}
	}

	@Benchmark
	public void snapshotFullScanBaseline(Blackhole bh) {
		final SqlSession session = engine.newSession();
		engine.execute(session, "BEGIN");
		try {
			bh.consume(engine.execute(session, fullScanSql).rows().size());
		} finally {
			engine.execute(session, "ROLLBACK");
		}
	}
}