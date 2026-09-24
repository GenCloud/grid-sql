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
 * JMH: FOR UPDATE SKIP LOCKED over indexed WHERE (LockAwareKeyCursor).
 * <p>
 * Also the hot-path microbench for FOR UPDATE v2 (local locks only; no peer 2PC) —
 * see {@code DistForUpdateLocalLockIT} and docs/en/replica-reads.md.
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
public class ForUpdateIndexedSkipLockedBenchmark extends AbstractLatencyBenchmark {
	private static final String TABLE = "fu_outbox";
	private static final String PENDING = "PENDING";
	private static final int PENDING_COUNT = 64;
	private static final int SHARDS = 4;

	@Param({"10000", "100000"})
	public int count;

	private SqlEngine engine;
	private TableStore store;
	private String forUpdateSql;

	@Setup(Level.Trial)
	public void setupBenchmark() {
		QueryParser.clearPlanCache();
		engine = SqlBenchHelper.createEngine(SHARDS);
		SqlBenchHelper.ensureIndexedTable(engine, TableSchema.builder(TABLE)
				.primaryKey("id", SqlType.INT)
				.column("status", SqlType.VARCHAR)
				.index(IndexDef.of("idx_status", IndexType.LAX, "status"))
				.build());
		store = SqlBenchHelper.store(engine, TABLE);
		for (int i = 0; i < count; i++) {
			final String status = i < PENDING_COUNT ? PENDING : "DONE";
			SqlBenchHelper.putIndexedRow(engine, TABLE, i, status);
		}
		forUpdateSql = "SELECT id FROM " + TABLE + " WHERE status = '" + PENDING + "' FOR UPDATE SKIP LOCKED";
	}

	@TearDown(Level.Trial)
	public void tearDownBenchmark() {
		if (store != null) {
			store.close();
		}
	}

	@Benchmark
	public void forUpdateIndexedSkipLocked(Blackhole bh) {
		final SqlSession session = engine.newSession();
		engine.execute(session, "BEGIN");
		try {
			bh.consume(engine.execute(session, forUpdateSql).rows().size());
		} finally {
			engine.execute(session, "ROLLBACK");
		}
	}
}