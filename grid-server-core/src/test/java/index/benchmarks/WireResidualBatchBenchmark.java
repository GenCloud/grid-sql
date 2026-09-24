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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

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
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import org.genfork.grid.catalog.SqlType;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.query.filters.WireResidualBatch;
import org.genfork.grid.query.filters.impl.LogicalOperatorCondition;
import org.genfork.grid.serial.RowEncoder;

/**
 * {@link WireResidualBatch#filterBlobs} EQ SIMD path vs scalar matches loop.
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
public class WireResidualBatchBenchmark extends AbstractLatencyBenchmark {
	private static final String TABLE = "wrb_bench";
	private static final int MATCH_STATUS = 1;
	private static final int OTHER_STATUS = 0;
	private static final int STATUS_MATCH_EVERY = 2;

	@Param({"100000"})
	public int count;

	private TableSchema schema;
	private List<byte[]> blobs;
	private LogicalOperatorCondition eq;

	@Setup(Level.Trial)
	public void setupBenchmark() {
		schema = TableSchema.builder(TABLE)
				.primaryKey("id", SqlType.INT)
				.column("status", SqlType.INT)
				.build();
		blobs = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			final int status = (i % STATUS_MATCH_EVERY == 0) ? MATCH_STATUS : OTHER_STATUS;
			blobs.add(RowEncoder.encode(schema, new Object[]{i, status}));
		}
		eq = new LogicalOperatorCondition(
				"status", LogicalOperatorCondition.Operator.EQ, MATCH_STATUS);
		eq.validate(schema);
	}

	@Benchmark
	public void filterBlobsEq(Blackhole bh) {
		final List<byte[]> out = WireResidualBatch.filterBlobs(blobs, schema, eq);
		bh.consume(out.size());
	}

	@Benchmark
	public void scalarMatchesLoop(Blackhole bh) {
		int matched = 0;
		for (byte[] blob : blobs) {
			if (eq.matches(blob, schema)) {
				matched++;
			}
		}
		bh.consume(matched);
	}
}
