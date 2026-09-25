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
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlSession;
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

import java.util.concurrent.TimeUnit;

/**
 * N2a/N2b/N2c JMH: JOIN PK/hash/LEFT, aggregates, prepared vs ad-hoc SELECT.
 * <p>
 * {@link #selectPrepared} exercises the PREPARE body Stmt cache: ANTLR runs on PREPARE;
 * each EXECUTE rebinds {@code ?} without re-parsing the body ({@code SqlPreparedBinder}).
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
public class SqlJoinAggPrepareBenchmark extends AbstractLatencyBenchmark {

	private static final int ROW_COUNT = 2_000;

	private SqlEngine engine;
	private SqlSession preparedSession;
	private String joinPkSql;
	private String joinHashSql;
	private String leftJoinSql;
	private String joinMultiEqSql;
	private String viewWithSql;
	private String namedOverSql;
	private String aggSql;
	private String joinVarcharSql;
	private String adhocSelect;
	private String executePrepared;

	@Setup
	public void setup() {
		engine = SqlBenchHelper.createEngine(4);
		engine.execute("CREATE TABLE jp_a (id INT PRIMARY KEY, k INT, score INT)");
		engine.execute("CREATE TABLE jp_b (id INT PRIMARY KEY, k INT)");
		engine.execute("CREATE TABLE jp_h (id INT PRIMARY KEY, bucket INT)");
		engine.execute("CREATE TABLE jp_vs (id INT PRIMARY KEY, label VARCHAR)");
		engine.execute("CREATE TABLE jp_vt (id INT PRIMARY KEY, label VARCHAR)");
		engine.execute("CREATE TABLE jp_m (id INT PRIMARY KEY, sid INT, v INT)");
		engine.execute("CREATE TABLE jp_n (id INT PRIMARY KEY, sid INT, w INT)");
		engine.execute("CREATE INDEX jp_n_eq ON jp_n (id, sid)");
		engine.execute("CREATE TABLE jp_scores (id INT PRIMARY KEY, pts INT)");
		for (int i = 0; i < ROW_COUNT; i++) {
			engine.execute("INSERT INTO jp_a VALUES (" + i + ", " + (i % 50) + ", " + (i % 100) + ")");
			engine.execute("INSERT INTO jp_b VALUES (" + i + ", " + (i % 50) + ")");
			engine.execute("INSERT INTO jp_h VALUES (" + i + ", " + (i % 32) + ")");
			engine.execute("INSERT INTO jp_vs VALUES (" + i + ", 'L" + (i % 50) + "')");
			engine.execute("INSERT INTO jp_vt VALUES (" + i + ", 'L" + (i % 50) + "')");
			engine.execute("INSERT INTO jp_m VALUES (" + i + ", " + (i % 10) + ", " + i + ")");
			engine.execute("INSERT INTO jp_n VALUES (" + i + ", " + (i % 10) + ", " + (i * 2) + ")");
			engine.execute("INSERT INTO jp_scores VALUES (" + i + ", " + (i % 100) + ")");
		}
		engine.execute(
				"CREATE VIEW jp_v AS WITH cte AS (SELECT id, score FROM jp_a WHERE score > 10) SELECT id FROM cte");
		joinPkSql = "SELECT * FROM jp_a JOIN jp_b ON id = id WHERE score > 10 LIMIT 50";
		joinHashSql = "SELECT * FROM jp_a JOIN jp_b ON k = k WHERE score > 50 LIMIT 50";
		leftJoinSql = "SELECT * FROM jp_a LEFT OUTER JOIN jp_b ON id = id LIMIT 50";
		joinMultiEqSql = "SELECT * FROM jp_m m JOIN jp_n n ON m.id = n.id AND m.sid = n.sid LIMIT 50";
		viewWithSql = "SELECT id FROM jp_v LIMIT 50";
		namedOverSql = "SELECT id, RANK() OVER (w) AS rnk FROM jp_scores WINDOW w AS (ORDER BY pts DESC) LIMIT 50";
		aggSql = "SELECT COUNT(*) FROM jp_h GROUP BY bucket";
		joinVarcharSql = "SELECT * FROM jp_vs JOIN jp_vt ON label = label LIMIT 50";
		adhocSelect = "SELECT score FROM jp_a WHERE id = 42";
		preparedSession = engine.newSession();
		engine.execute(preparedSession, "PREPARE q AS SELECT score FROM jp_a WHERE id = ?");
		executePrepared = "EXECUTE q USING 42";
		engine.execute(joinPkSql);
		engine.execute(joinHashSql);
		engine.execute(leftJoinSql);
		engine.execute(joinMultiEqSql);
		engine.execute(viewWithSql);
		engine.execute(namedOverSql);
		engine.execute(aggSql);
		engine.execute(joinVarcharSql);
		engine.execute(preparedSession, executePrepared);
	}

	@TearDown
	public void tearDown() {
		SqlBenchHelper.closeAllStores(engine);
		engine = null;
		preparedSession = null;
	}

	@Benchmark
	public void joinPkProbe(Blackhole bh) {
		bh.consume(engine.execute(joinPkSql).rows().size());
	}

	@Benchmark
	public void joinHash(Blackhole bh) {
		bh.consume(engine.execute(joinHashSql).rows().size());
	}

	@Benchmark
	public void leftOuterJoin(Blackhole bh) {
		bh.consume(engine.execute(leftJoinSql).rows().size());
	}

	@Benchmark
	public void joinMultiEqAnd(Blackhole bh) {
		bh.consume(engine.execute(joinMultiEqSql).rows().size());
	}

	@Benchmark
	public void viewAsWithSelect(Blackhole bh) {
		bh.consume(engine.execute(viewWithSql).rows().size());
	}

	@Benchmark
	public void namedWindowOverParen(Blackhole bh) {
		bh.consume(engine.execute(namedOverSql).rows().size());
	}

	@Benchmark
	public void joinVarcharHash(Blackhole bh) {
		bh.consume(engine.execute(joinVarcharSql).rows().size());
	}

	@Benchmark
	public void aggregateGroupBy(Blackhole bh) {
		bh.consume(engine.execute(aggSql).rows().size());
	}

	@Benchmark
	public void selectAdHoc(Blackhole bh) {
		bh.consume(engine.execute(adhocSelect).rows().size());
	}

	@Benchmark
	public void selectPrepared(Blackhole bh) {
		bh.consume(engine.execute(preparedSession, executePrepared).rows().size());
	}
}