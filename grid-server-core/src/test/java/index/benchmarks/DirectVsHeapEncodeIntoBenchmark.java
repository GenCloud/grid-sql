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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import org.genfork.grid.nio.EncodeBuffers;
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
 * Heap allocateWireLe+toByteArray vs Direct+toByteArray vs encodeInto pooled ByteBuf.
 * Frame sizes: 64, 4K, 64K, 256K. Product WireTiny stays heap until encodeInto wins p50/p99.
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
public class DirectVsHeapEncodeIntoBenchmark extends AbstractLatencyBenchmark {
	@Param({"64", "4096", "65536", "262144"})
	public int frameSize;

	@Param({"heap", "direct", "encodeInto"})
	public String mode;

	private String sql;
	private Object[] binds;
	private PooledByteBufAllocator allocator;

	@Setup
	public void setup() {
		allocator = PooledByteBufAllocator.DEFAULT;
		final int padLen = Math.max(0, frameSize - 64);
		final char[] chars = new char[padLen];
		for (int i = 0; i < padLen; i++) {
			chars[i] = 'x';
		}
		sql = "INSERT INTO t (id, v) VALUES (?, ?)";
		binds = new Object[]{42, new String(chars)};
	}

	@Benchmark
	public void frameEncode(Blackhole bh) {
		switch (mode) {
			case "heap" -> bh.consume(encodeViaAllocate(false));
			case "direct" -> bh.consume(encodeViaAllocate(true));
			case "encodeInto" -> {
				final ByteBuf out = allocator.buffer(frameSize + 64);
				try {
					SqlWire.execInto(out, 1, sql, binds);
					bh.consume(out.readableBytes());
				} finally {
					out.release();
				}
			}
			default -> throw new IllegalStateException("mode=" + mode);
		}
	}

	private byte[] encodeViaAllocate(boolean direct) {
		final byte[] sqlBytes = sql.getBytes(StandardCharsets.UTF_8);
		final byte[] cell0 = encodeIntCell(42, direct);
		final byte[] cell1 = encodeStringCell(binds[1].toString(), direct);
		final int size = 4 + 4 + sqlBytes.length + 2 + cell0.length + cell1.length;
		final ByteBuffer buf = EncodeBuffers.allocateLe(size, direct);
		buf.putInt(1);
		buf.putInt(sqlBytes.length);
		buf.put(sqlBytes);
		buf.putShort((short) 2);
		buf.put(cell0);
		buf.put(cell1);
		return EncodeBuffers.toByteArray(buf);
	}

	private static byte[] encodeIntCell(int v, boolean direct) {
		final ByteBuffer b = EncodeBuffers.allocateLe(5, direct);
		b.put((byte) 1);
		b.putInt(v);
		return EncodeBuffers.toByteArray(b);
	}

	private static byte[] encodeStringCell(String s, boolean direct) {
		final byte[] raw = s.getBytes(StandardCharsets.UTF_8);
		final ByteBuffer b = EncodeBuffers.allocateLe(5 + raw.length, direct);
		b.put((byte) 5);
		b.putInt(raw.length);
		b.put(raw);
		return EncodeBuffers.toByteArray(b);
	}
}