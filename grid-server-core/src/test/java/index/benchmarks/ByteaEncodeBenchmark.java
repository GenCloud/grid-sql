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

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.genfork.grid.catalog.SqlType;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.serial.LogicalFieldCursor;
import org.genfork.grid.serial.RowEncoder;
import org.genfork.grid.sql.netty.SqlWire;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * BYTES / BYTEA encode-decode thrpt for fork-sized payloads (8 KiB key_binding).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@Threads(1)
@State(Scope.Benchmark)
public class ByteaEncodeBenchmark {
	private static final int PAYLOAD_8K = 8000;
	private static final int PAYLOAD_2K = 2048;
	private static final byte FILL = (byte) 0xa5;

	private TableSchema schema;
	private byte[] payload8k;
	private byte[] payload2k;
	private byte[] row8k;
	private Object[] binds8k;

	@Setup
	public void setup() {
		schema = TableSchema.builder("bytea_bench")
				.primaryKey("id", SqlType.INT)
				.column("payload", SqlType.BYTES)
				.build();
		payload8k = new byte[PAYLOAD_8K];
		Arrays.fill(payload8k, FILL);
		payload2k = new byte[PAYLOAD_2K];
		Arrays.fill(payload2k, FILL);
		row8k = RowEncoder.encode(schema, new Object[]{1, payload8k});
		binds8k = new Object[]{payload8k};
	}

	@Benchmark
	public void rowEncoderEncode8k(Blackhole bh) {
		bh.consume(RowEncoder.encode(schema, new Object[]{1, payload8k}));
	}

	@Benchmark
	public void rowEncoderEncode2k(Blackhole bh) {
		bh.consume(RowEncoder.encode(schema, new Object[]{1, payload2k}));
	}

	@Benchmark
	public void logicalCursorReadBytes8k(Blackhole bh) {
		final LogicalFieldCursor cur = LogicalFieldCursor.open(schema, row8k);
		bh.consume(cur.read(1));
	}

	@Benchmark
	public void wireCellEncodeDecode8k(Blackhole bh) {
		final byte[] frame = SqlWire.exec(1, "SELECT 1", binds8k);
		final SqlWire.ExecPayload decoded = SqlWire.readExec(frame);
		bh.consume(decoded);
		bh.consume(frame);
	}
}
