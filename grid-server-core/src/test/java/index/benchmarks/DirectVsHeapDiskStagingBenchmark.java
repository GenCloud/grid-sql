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

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

/**
 * Lightweight A/B: heap {@link ByteBuffer#allocate} vs {@link ByteBuffer#allocateDirect}
 * fill+flip for sealed-sized chunks (64 KiB, 1 MiB).
 * <p>
 * Production sealed / OpLog remains mmap source of truth — this bench does <strong>not</strong>
 * change sealed or OpLog defaults; it only measures staging allocation cost.
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
public class DirectVsHeapDiskStagingBenchmark extends AbstractLatencyBenchmark {
	@Param({"65536", "1048576"})
	public int chunkSize;

	@Param({"heap", "direct"})
	public String mode;

	@Benchmark
	public void fillFlip(Blackhole bh) {
		final boolean direct = "direct".equals(mode);
		final ByteBuffer buf = direct
				? ByteBuffer.allocateDirect(chunkSize)
				: ByteBuffer.allocate(chunkSize);
		for (int i = 0; i < chunkSize; i++) {
			buf.put((byte) (i & 0xff));
		}
		buf.flip();
		bh.consume(buf.remaining());
		bh.consume(buf.get(0));
	}
}