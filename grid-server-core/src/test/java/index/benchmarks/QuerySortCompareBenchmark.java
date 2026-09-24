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
import org.genfork.grid.query.plan.QueryParser;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.store.TableStore;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
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
import org.h2.Driver;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Large-data query/sort compare: TableStore catalog+index key scan vs embedded H2.
 * Grid side measures {@link TableStore#selectKeys} (same cost class as followup
 * {@code GridCompositeIndex.executeStatement}) — not full SqlEngine row decode.
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
public class QuerySortCompareBenchmark extends AbstractLatencyBenchmark {

	private static final String TABLE = "query_row";

	@Param({"100000"})
	public int count;

	private SqlEngine engine;
	private TableStore store;
	private Connection h2;
	private String gridSqlLimit;
	private String gridSqlOrder;
	private String h2SqlLimit;
	private String h2SqlOrder;
	private int probeBucket;

	@Setup
	public void setup() throws Exception {
		QueryParser.clearPlanCache();
		engine = SqlBenchHelper.createEngine(4);
		SqlBenchHelper.ensureIndexedTable(engine, SqlBenchHelper.queryRowSchema(TABLE));
		store = SqlBenchHelper.store(engine, TABLE);

		// H2 JDBC = OSS twin harness only (not product API).
		// Bypass DriverManager: ignite-core registers IgniteJdbcThinDriver via ServiceLoader;
		// its <clinit> can fail on JDK 25 JMH forks and poison every getConnection (incl. H2).
		final Properties h2Props = new Properties();
		h2Props.setProperty("user", "sa");
		h2Props.setProperty("password", "");
		h2 = new Driver().connect("jdbc:h2:mem:query_sort_compare;DB_CLOSE_DELAY=-1", h2Props);
		if (h2 == null) {
			throw new IllegalStateException("H2 Driver.connect returned null");
		}
		try (Statement st = h2.createStatement()) {
			st.execute("CREATE TABLE query_row (id VARCHAR PRIMARY KEY, bucket INT, score INT)");
			st.execute("CREATE INDEX idx_bucket ON query_row(bucket)");
			st.execute("CREATE INDEX idx_score ON query_row(score)");
			st.execute("CREATE INDEX idx_bucket_score ON query_row(bucket, score)");
		}

		final PreparedStatement insert = h2.prepareStatement(
				"INSERT INTO query_row(id, bucket, score) VALUES (?,?,?)");

		for (int i = 0; i < count; i++) {
			final int bucket = ThreadLocalRandom.current().nextInt(0, 32);
			final int score = ThreadLocalRandom.current().nextInt(0, 1_000_000);
			final String id = String.valueOf(i);
			SqlBenchHelper.putIndexedRow(engine, TABLE, id, bucket, score);
			insert.setString(1, id);
			insert.setInt(2, bucket);
			insert.setInt(3, score);
			insert.executeUpdate();
		}
		insert.close();

		probeBucket = 7;
		gridSqlLimit = "SELECT * FROM " + TABLE + " WHERE bucket = " + probeBucket + " LIMIT 0, 100";
		gridSqlOrder = "SELECT * FROM " + TABLE + " WHERE bucket = " + probeBucket
				+ " ORDER BY score ASC LIMIT 0, 100";
		h2SqlLimit = "SELECT id, bucket, score FROM query_row WHERE bucket = " + probeBucket + " LIMIT 100";
		h2SqlOrder = "SELECT id, bucket, score FROM query_row WHERE bucket = " + probeBucket
				+ " ORDER BY score ASC LIMIT 100";
		store.selectKeys(gridSqlLimit);
		store.selectKeys(gridSqlOrder);
	}

	@TearDown
	public void tearDown() throws Exception {
		if (engine != null && engine.catalog().exists(TABLE)) {
			engine.catalog().dropTable(TABLE);
		}
		if (h2 != null) {
			try (Statement st = h2.createStatement()) {
				st.execute("SHUTDOWN");
			} catch (Exception ignored) {
				// already closed
			}
			try {
				h2.close();
			} catch (Exception ignored) {
				// ignore
			}
		}
	}

	@Benchmark
	public void gridFilterLimit(Blackhole bh) {
		final List<byte[]> keys = store.selectKeys(gridSqlLimit);
		bh.consume(keys);
	}

	@Benchmark
	public void gridFilterOrderLimit(Blackhole bh) {
		final List<byte[]> keys = store.selectKeys(gridSqlOrder);
		bh.consume(keys);
	}

	@Benchmark
	public void h2FilterLimit(Blackhole bh) throws Exception {
		try (Statement st = h2.createStatement();
		     ResultSet rs = st.executeQuery(h2SqlLimit)) {
			while (rs.next()) {
				bh.consume(rs.getString(1));
				bh.consume(rs.getInt(2));
				bh.consume(rs.getInt(3));
			}
		}
	}

	@Benchmark
	public void h2FilterOrderLimit(Blackhole bh) throws Exception {
		try (Statement st = h2.createStatement();
		     ResultSet rs = st.executeQuery(h2SqlOrder)) {
			while (rs.next()) {
				bh.consume(rs.getString(1));
				bh.consume(rs.getInt(2));
				bh.consume(rs.getInt(3));
			}
		}
	}
}
