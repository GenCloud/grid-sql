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

import org.genfork.grid.catalog.SqlType;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.serial.LogicalFieldCursor;
import org.genfork.grid.serial.RowEncoder;
import org.genfork.grid.serial.WireFieldBytes;
import org.genfork.grid.serial.WireSpan;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Microbench: WireSpan zero-copy keys vs owned indexKeyBytes copy.
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
@State(Scope.Benchmark)
public class WireSpanKeyBenchmark extends AbstractLatencyBenchmark {

	private static final int ROW_COUNT = 256;
	private static final int LABEL_LEN = 48;
	private static final int LABEL_ORDINAL = 1;

	private byte[][] blobs;
	private TableSchema schema;

	@Setup
	public void setup() {
		schema = TableSchema.builder("span_bench")
				.primaryKey("id", SqlType.INT)
				.column("label", SqlType.VARCHAR, false)
				.build();
		blobs = new byte[ROW_COUNT][];
		for (int i = 0; i < ROW_COUNT; i++) {
			final StringBuilder sb = new StringBuilder(LABEL_LEN);
			while (sb.length() < LABEL_LEN) {
				sb.append('L').append(i).append('-');
			}
			final String label = sb.substring(0, LABEL_LEN);
			blobs[i] = RowEncoder.encode(schema, new Object[]{i, label});
		}
	}

	@Benchmark
	public void spanHashAccumulate(Blackhole bh) {
		int h = 0;
		for (byte[] blob : blobs) {
			final WireSpan span = LogicalFieldCursor.open(schema, blob).indexKeySpan(LABEL_ORDINAL);
			h ^= span.hashCode();
		}
		bh.consume(h);
	}

	@Benchmark
	public void copyHashAccumulate(Blackhole bh) {
		int h = 0;
		for (byte[] blob : blobs) {
			final WireFieldBytes key = new WireFieldBytes(
					LogicalFieldCursor.open(schema, blob).indexKeyBytes(LABEL_ORDINAL));
			h ^= key.hashCode();
		}
		bh.consume(h);
	}
}