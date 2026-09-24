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
import java.util.concurrent.atomic.AtomicInteger;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
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

import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.tx.SqlRecordLockManager;

import index.sql.SqlBenchHelper;
import index.sql.udf.DoubleIntUdf;
import index.sql.udf.ScanTableTvf;
import index.sql.udf.UpsertItemUdf;

/**
 * JMH for SQL features: WITH/CTE, VIEW, window, aggregates, UNION/INTERSECT/EXCEPT, RECURSIVE, UDF, TVF.
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
public class SqlFeaturesBenchmark extends AbstractLatencyBenchmark {

	private static final int ROW_COUNT = 2_000;
	private static final int BUCKET_MOD = 32;
	private static final int TREE_ROOT = 1;
	private static final int TREE_NODES = 64;
	private static final byte[] LOCK_KEY = new byte[]{1};
	private static final String LOCK_TABLE = "bench_lock";

	private SqlEngine engine;
	private String withSql;
	private String viewSql;
	private String minSql;
	private String havingSql;
	private String distinctSql;
	private String windowSql;
	private String namedWindowSql;
	private String mvSelectSql;
	private String unionSql;
	private String intersectSql;
	private String exceptSql;
	private String recursiveSql;
	private String udfSql;
	private String tvfSql;
	private String mutatingUdfSql;
	private AtomicInteger returningIds;
	private AtomicInteger checkIds;
	private AtomicInteger mutatingUdfIds;
	private SqlRecordLockManager lockManager;

	@Setup
	public void setup() {
		engine = SqlBenchHelper.createEngine(4);
		engine.execute("CREATE TABLE feat (id INT PRIMARY KEY, bucket INT, score INT)");
		engine.execute("CREATE TABLE returning_bench (id INT PRIMARY KEY, payload VARCHAR)");
		engine.execute("CREATE TABLE check_bench (id INT PRIMARY KEY, score INT, CHECK (score >= 0))");
		returningIds = new AtomicInteger(ROW_COUNT);
		checkIds = new AtomicInteger(ROW_COUNT);
		mutatingUdfIds = new AtomicInteger(ROW_COUNT);
		lockManager = new SqlRecordLockManager();
		for (int i = 0; i < ROW_COUNT; i++) {
			engine.execute("INSERT INTO feat VALUES (" + i + ", " + (i % BUCKET_MOD) + ", " + (i % 100) + ")");
		}
		engine.execute("CREATE VIEW feat_v AS SELECT id, bucket, score FROM feat WHERE score > 10");
		engine.execute("CREATE MATERIALIZED VIEW feat_mv AS SELECT id, score FROM feat WHERE score > 50");
		engine.execute(
				"CREATE FUNCTION double_v(x INT) RETURNS INT AS CLASS '"
						+ DoubleIntUdf.class.getName()
						+ "' METHOD 'apply'");
		ScanTableTvf.CATALOG = engine.catalog();
		engine.execute(
				"CREATE FUNCTION scan_feat(t VARCHAR) RETURNS TABLE (id INT, bucket INT, score INT) AS CLASS '"
						+ ScanTableTvf.class.getName()
						+ "' METHOD 'apply'");
		engine.execute("CREATE TABLE items (id INT PRIMARY KEY, payload VARCHAR)");
		engine.execute(
				"CREATE FUNCTION item_upsert(id INT, payload VARCHAR) RETURNS INT AS CLASS '"
						+ UpsertItemUdf.class.getName()
						+ "' METHOD 'apply'");

		engine.execute("CREATE TABLE emp (id INT PRIMARY KEY, parent_id INT)");
		engine.execute("INSERT INTO emp VALUES (" + TREE_ROOT + ", 0)");
		for (int i = 2; i <= TREE_NODES; i++) {
			engine.execute("INSERT INTO emp VALUES (" + i + ", " + (i / 2) + ")");
		}

		withSql = "WITH c AS (SELECT id, score FROM feat WHERE score > 20) SELECT id FROM c WHERE id < 100";
		viewSql = "SELECT id FROM feat_v WHERE id < 100";
		minSql = "SELECT MIN(score) FROM feat";
		havingSql = "SELECT COUNT(*) FROM feat GROUP BY bucket HAVING bucket < 4";
		distinctSql = "SELECT DISTINCT bucket FROM feat";
		windowSql = "SELECT ROW_NUMBER() OVER (PARTITION BY bucket ORDER BY id) FROM feat";
		namedWindowSql = "SELECT ROW_NUMBER() OVER w FROM feat WINDOW w AS (PARTITION BY bucket ORDER BY id)";
		mvSelectSql = "SELECT id FROM feat_mv WHERE id < 100";
		unionSql = "SELECT id FROM feat WHERE id < 50 UNION ALL SELECT id FROM feat WHERE id >= 50 AND id < 100";
		intersectSql = "SELECT id FROM feat WHERE id < 80 INTERSECT SELECT id FROM feat WHERE id >= 20 AND id < 100";
		exceptSql = "SELECT id FROM feat WHERE id < 100 EXCEPT SELECT id FROM feat WHERE id < 50";
		recursiveSql = "WITH RECURSIVE tree AS ("
				+ " SELECT id, parent_id FROM emp WHERE id = " + TREE_ROOT
				+ " UNION ALL"
				+ " SELECT id, parent_id FROM emp JOIN tree ON parent_id = id"
				+ ") SELECT id FROM tree";
		udfSql = "SELECT id, double_v(score) FROM feat WHERE id < 100";
		tvfSql = "SELECT id FROM scan_feat('feat') WHERE id < 100";
		mutatingUdfSql = "SELECT item_upsert(0, 'seed')";

		engine.execute(withSql);
		engine.execute(viewSql);
		engine.execute(minSql);
		engine.execute(havingSql);
		engine.execute(distinctSql);
		engine.execute(windowSql);
		engine.execute(namedWindowSql);
		engine.execute(mvSelectSql);
		engine.execute(unionSql);
		engine.execute(intersectSql);
		engine.execute(exceptSql);
		engine.execute(recursiveSql);
		engine.execute(udfSql);
		engine.execute(tvfSql);
		engine.execute(mutatingUdfSql);
	}

	@TearDown
	public void tearDown() {
		SqlBenchHelper.closeAllStores(engine);
		engine = null;
	}

	@Benchmark
	public void withCte(Blackhole bh) {
		bh.consume(engine.execute(withSql).rows().size());
	}

	@Benchmark
	public void selectFromView(Blackhole bh) {
		bh.consume(engine.execute(viewSql).rows().size());
	}

	@Benchmark
	public void minAggregate(Blackhole bh) {
		final SqlResult r = engine.execute(minSql);
		bh.consume(r.rows().getFirst()[0]);
	}

	@Benchmark
	public void groupByHaving(Blackhole bh) {
		bh.consume(engine.execute(havingSql).rows().size());
	}

	@Benchmark
	public void selectDistinct(Blackhole bh) {
		bh.consume(engine.execute(distinctSql).rows().size());
	}

	@Benchmark
	public void rowNumberWindow(Blackhole bh) {
		bh.consume(engine.execute(windowSql).rows().size());
	}

	@Benchmark
	public void namedRowNumberWindow(Blackhole bh) {
		bh.consume(engine.execute(namedWindowSql).rows().size());
	}

	@Benchmark
	public void materializedViewSelect(Blackhole bh) {
		bh.consume(engine.execute(mvSelectSql).rows().size());
	}

	@Benchmark
	public void unionAll(Blackhole bh) {
		bh.consume(engine.execute(unionSql).rows().size());
	}

	@Benchmark
	public void intersect(Blackhole bh) {
		bh.consume(engine.execute(intersectSql).rows().size());
	}

	@Benchmark
	public void except(Blackhole bh) {
		bh.consume(engine.execute(exceptSql).rows().size());
	}

	@Benchmark
	public void recursiveCte(Blackhole bh) {
		bh.consume(engine.execute(recursiveSql).rows().size());
	}

	@Benchmark
	public void scalarUdf(Blackhole bh) {
		bh.consume(engine.execute(udfSql).rows().size());
	}

	@Benchmark
	public void tableUdfScan(Blackhole bh) {
		bh.consume(engine.execute(tvfSql).rows().size());
	}

	@Benchmark
	public void mutatingScalarUdf(Blackhole bh) {
		final int id = mutatingUdfIds.incrementAndGet();
		bh.consume(engine.execute("SELECT item_upsert(" + id + ", 'b')").rows().getFirst()[0]);
	}

	@Benchmark
	public void insertReturning(Blackhole bh) {
		final int id = returningIds.incrementAndGet();
		bh.consume(engine.execute(
				"INSERT INTO returning_bench VALUES (" + id + ", 'x') RETURNING id")
				.rows().getFirst()[0]);
	}

	@Benchmark
	public void uncontendedRecordLock(Blackhole bh) {
		lockManager.lock(LOCK_TABLE, LOCK_KEY);
		try {
			bh.consume(LOCK_KEY);
		} finally {
			lockManager.unlock(LOCK_TABLE, LOCK_KEY);
		}
	}

	@Benchmark
	public void uncontendedTryRecordLock(Blackhole bh) {
		final boolean acquired = lockManager.tryLock(LOCK_TABLE, LOCK_KEY);
		try {
			bh.consume(acquired);
		} finally {
			if (acquired) {
				lockManager.unlock(LOCK_TABLE, LOCK_KEY);
			}
		}
	}

	@Benchmark
	public void checkConstraintInsert(Blackhole bh) {
		final int id = checkIds.incrementAndGet();
		bh.consume(engine.execute("INSERT INTO check_bench VALUES (" + id + ", 1)").rowsAffected());
	}
}
