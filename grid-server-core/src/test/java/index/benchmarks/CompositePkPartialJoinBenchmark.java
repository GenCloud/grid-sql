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
 * LEFT JOIN on composite PRIMARY KEY leading column (partial ON) stamp (§8).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
public class CompositePkPartialJoinBenchmark extends AbstractBenchmark {
	private static final String PARENT = "cmp_join_parent";
	private static final String CHILD = "cmp_join_child";
	private static final int PARENT_ROWS = 256;
	private static final int CHILD_PER_PARENT = 4;
	private static final int PROBE_PARENT_ID = 7;

	private SqlEngine engine;
	private String leftJoinSql;
	private String innerJoinSql;
	private String prefixEqSql;

	@Setup(Level.Trial)
	public void setup() {
		engine = SqlBenchHelper.createEngine(4);
		engine.execute("CREATE TABLE " + PARENT + " (id INT PRIMARY KEY, owner_id INT)");
		engine.execute("CREATE TABLE " + CHILD
				+ " (p1 INT NOT NULL, p2 INT NOT NULL, p3 INT NOT NULL, payload INT, "
				+ "PRIMARY KEY (p1, p2, p3))");
		for (int parentId = 1; parentId <= PARENT_ROWS; parentId++) {
			engine.execute("INSERT INTO " + PARENT + " VALUES (" + parentId + ", " + (parentId % 16) + ")");
			for (int j = 0; j < CHILD_PER_PARENT; j++) {
				engine.execute("INSERT INTO " + CHILD + " VALUES ("
						+ parentId + ", " + j + ", " + (parentId * 10 + j) + ", " + j + ")");
			}
		}
		leftJoinSql = "SELECT " + PARENT + ".id, " + CHILD + ".p3 FROM " + PARENT
				+ " LEFT JOIN " + CHILD + " ON " + CHILD + ".p1 = " + PARENT + ".id"
				+ " WHERE " + PARENT + ".id = " + PROBE_PARENT_ID;
		innerJoinSql = "SELECT " + CHILD + ".p3 FROM " + PARENT
				+ " INNER JOIN " + CHILD + " ON " + CHILD + ".p1 = " + PARENT + ".id"
				+ " WHERE " + PARENT + ".id = " + PROBE_PARENT_ID;
		prefixEqSql = "SELECT p3 FROM " + CHILD + " WHERE p1 = " + PROBE_PARENT_ID;
	}

	@TearDown(Level.Trial)
	public void tearDown() {
		SqlBenchHelper.closeAllStores(engine);
		ThreadService.shutdownNow();
	}

	@Benchmark
	public void leftJoinPartialCompositePk(Blackhole bh) {
		bh.consume(engine.execute(leftJoinSql).rows().size());
	}

	@Benchmark
	public void innerJoinPartialCompositePk(Blackhole bh) {
		bh.consume(engine.execute(innerJoinSql).rows().size());
	}

	@Benchmark
	public void leadingPkPrefixEqBaseline(Blackhole bh) {
		bh.consume(engine.execute(prefixEqSql).rows().size());
	}
}
