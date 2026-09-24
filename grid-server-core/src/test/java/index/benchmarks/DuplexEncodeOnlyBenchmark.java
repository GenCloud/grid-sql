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

import org.genfork.grid.catalog.SqlType;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.codec.duplex.DuplexCodecSupport;
import org.genfork.grid.codec.duplex.DuplexRepairMode;
import org.genfork.grid.serial.RowEncoder;
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
 * Duplex-on encode paths (isolated so gated logical encode stays clean).
 *
 * @author: GenCloud
 * @date: 2026/12
 * @since: 1.0
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@Threads(1)
@State(Scope.Benchmark)
public class DuplexEncodeOnlyBenchmark extends AbstractLatencyBenchmark {
	private TableSchema catalogSchema;
	private Object[] catalogValues;

	@Setup
	public void setup() {
		catalogSchema = TableSchema.builder("bench")
				.primaryKey("id", SqlType.INT)
				.column("name", SqlType.VARCHAR)
				.column("score", SqlType.DOUBLE)
				.build();
		catalogValues = new Object[]{1, "bench-name", 42.5d};
	}

	@TearDown
	public void tearDown() {
		DuplexCodecSupport.configure(false, DuplexRepairMode.FAIL, false, false, 1L, true, true);
	}

	@Benchmark
	public void duplexEncodeRaw(Blackhole bh) {
		DuplexCodecSupport.configure(true, DuplexRepairMode.REBUILD_DATA_FROM_PARITY, false, false, 1L, true, true);
		bh.consume(RowEncoder.encode(catalogSchema, catalogValues));
	}

	@Benchmark
	public void duplexEncodeVerify(Blackhole bh) {
		DuplexCodecSupport.configure(true, DuplexRepairMode.REBUILD_DATA_FROM_PARITY, true, true, 1L, true, true);
		bh.consume(RowEncoder.encode(catalogSchema, catalogValues));
	}
}