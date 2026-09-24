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

import org.genfork.grid.nio.EncodeBuffers;
import org.genfork.grid.replication.netty.ReplicationRpcCodec;
import org.genfork.grid.sql.netty.SqlWire;
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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Heap vs direct encode for SQL wire EXEC and replication HELLO (proof track).
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
@State(Scope.Benchmark)
public class DirectVsHeapEncodeBenchmark extends AbstractLatencyBenchmark {
	@Param({"heap", "direct"})
	public String mode;

	private boolean direct;
	private String sql;
	private Object[] binds;

	@Setup
	public void setup() {
		direct = "direct".equals(mode);
		sql = "INSERT INTO t (id, v) VALUES (?, ?)";
		binds = new Object[]{42, "payload-value"};
	}

	@Benchmark
	public void sqlExecEncode(Blackhole bh) {
		final byte[] sqlBytes = sql.getBytes(StandardCharsets.UTF_8);
		final byte[] cell0 = encodeIntCell(42);
		final byte[] cell1 = encodeStringCell("payload-value");
		final int size = 4 + 4 + sqlBytes.length + 2 + cell0.length + cell1.length;
		final ByteBuffer buf = EncodeBuffers.allocateLe(size, direct);
		buf.putInt(1);
		buf.putInt(sqlBytes.length);
		buf.put(sqlBytes);
		buf.putShort((short) 2);
		buf.put(cell0);
		buf.put(cell1);
		bh.consume(EncodeBuffers.toByteArray(buf));
	}

	@Benchmark
	public void sqlWireExecProduct(Blackhole bh) {
		bh.consume(SqlWire.exec(1, sql, binds));
	}

	@Benchmark
	public void replHelloEncode(Blackhole bh) {
		bh.consume(ReplicationRpcCodec.encodeHello(
				new ReplicationRpcCodec.Hello("n1", "c1", "dc-a", 1L, 1L, (byte) 1)));
	}

	private byte[] encodeIntCell(int v) {
		final ByteBuffer b = EncodeBuffers.allocateLe(5, direct);
		b.put((byte) 1);
		b.putInt(v);
		return EncodeBuffers.toByteArray(b);
	}

	private byte[] encodeStringCell(String s) {
		final byte[] raw = s.getBytes(StandardCharsets.UTF_8);
		final ByteBuffer b = EncodeBuffers.allocateLe(5 + raw.length, direct);
		b.put((byte) 5);
		b.putInt(raw.length);
		b.put(raw);
		return EncodeBuffers.toByteArray(b);
	}
}