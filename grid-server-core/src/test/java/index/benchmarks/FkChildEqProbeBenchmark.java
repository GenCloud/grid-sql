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
import org.genfork.grid.serial.SqlWireUtil;
import org.genfork.grid.sql.SqlEngine;
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

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * JMH: FK child wire EQ probe (forEachChildFkMatch path).
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
public class FkChildEqProbeBenchmark extends AbstractLatencyBenchmark {
	private static final String PARENT = "fk_parent";
	private static final String CHILD = "fk_child";
	private static final int SHARDS = 4;
	private static final int PARENT_ID = 1;

	@Param({"10000", "100000"})
	public int childCount;

	private SqlEngine engine;
	private TableStore child;
	private byte[] parentWire;

	@Setup(Level.Trial)
	public void setupBenchmark() {
		QueryParser.clearPlanCache();
		engine = SqlBenchHelper.createEngine(SHARDS);
		SqlBenchHelper.ensureIndexedTable(engine, TableSchema.builder(PARENT)
				.primaryKey("id", SqlType.INT).build());
		SqlBenchHelper.ensureIndexedTable(engine, TableSchema.builder(CHILD)
				.primaryKey("id", SqlType.INT)
				.column("parent_id", SqlType.INT)
				.index(IndexDef.of("idx_parent", IndexType.LAX, "parent_id"))
				.build());
		SqlBenchHelper.putIndexedRow(engine, PARENT, PARENT_ID);
		child = SqlBenchHelper.store(engine, CHILD);
		for (int i = 0; i < childCount; i++) {
			SqlBenchHelper.putIndexedRow(engine, CHILD, i, PARENT_ID);
		}
		parentWire = SqlWireUtil.toGenericArray(PARENT_ID);
	}

	@TearDown(Level.Trial)
	public void tearDownBenchmark() {
		SqlBenchHelper.closeAllStores(engine);
	}

	@Benchmark
	public void fkChildEqProbe(Blackhole bh) {
		final List<byte[]> keys = child.lookupEqKeys(List.of("parent_id"), new byte[][]{parentWire});
		bh.consume(keys.size());
	}
}