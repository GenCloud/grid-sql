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

import index.sql.SqlBenchHelper;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.threading.ThreadService;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Composite PRIMARY KEY left-prefix EQ stamp (critical-engineering §8).
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
public class CompositePkPrefixEqBenchmark extends AbstractBenchmark {
	private static final String TABLE = "cmp_pk_bench";
	private static final int ROWS = 2_000;
	private static final int PREFIX_SERVER_ID = 7;

	private SqlEngine engine;
	private String prefixSql;
	private String fullSql;
	private String selectStarSql;

	@Setup(Level.Trial)
	public void setup() {
		engine = SqlBenchHelper.createEngine(4);
		engine.execute("CREATE TABLE " + TABLE
				+ " (server_id INT NOT NULL, biset_type VARCHAR NOT NULL, v INT, "
				+ "PRIMARY KEY (server_id, biset_type))");
		for (int i = 0; i < ROWS; i++) {
			final int serverId = (i % 16) + 1;
			engine.execute("INSERT INTO " + TABLE + " VALUES (" + serverId + ", 'T" + i + "', " + i + ")");
		}
		prefixSql = "SELECT server_id FROM " + TABLE + " WHERE server_id = " + PREFIX_SERVER_ID;
		fullSql = "SELECT server_id FROM " + TABLE
				+ " WHERE server_id = " + PREFIX_SERVER_ID + " AND biset_type = 'T7'";
		selectStarSql = "SELECT server_id FROM " + TABLE;
	}

	@TearDown(Level.Trial)
	public void tearDown() {
		SqlBenchHelper.closeAllStores(engine);
		ThreadService.shutdownNow();
	}

	@Benchmark
	public void leadingPkPrefixEq(Blackhole bh) {
		bh.consume(engine.execute(prefixSql).rows().size());
	}

	@Benchmark
	public void fullCompositePkEq(Blackhole bh) {
		bh.consume(engine.execute(fullSql).rows().size());
	}

	@Benchmark
	public void selectStarCompositePk(Blackhole bh) {
		bh.consume(engine.execute(selectStarSql).rows().size());
	}
}