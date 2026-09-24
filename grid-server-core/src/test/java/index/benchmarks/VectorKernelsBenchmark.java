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

import org.genfork.grid.utils.ArrayUtil;
import org.genfork.grid.utils.ArrayVectors;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Latency of Vector API byte[] kernels (fastHash / equals / compare / CRC32).
 *
 * @author: GenCloud
 * @date: 2026/12
 * @since: 1.0
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class VectorKernelsBenchmark extends AbstractLatencyBenchmark {
	private static final long RANDOM_SEED = 7L;

	@Param({"8", "64", "1024", "4096"})
	public int payloadLength;

	private byte[] left;
	private byte[] rightEqual;
	private byte[] rightMismatch;

	@Setup(Level.Trial)
	public void setup() {
		final Random rnd = new Random(RANDOM_SEED);
		left = new byte[payloadLength];
		rnd.nextBytes(left);
		rightEqual = Arrays.copyOf(left, payloadLength);
		rightMismatch = Arrays.copyOf(left, payloadLength);
		rightMismatch[payloadLength - 1] ^= 1;
	}

	@Benchmark
	public int fastHash() {
		return ArrayVectors.fastHashCompatible(left);
	}

	@Benchmark
	public boolean bytesEqual() {
		return ArrayVectors.bytesEqual(left, rightEqual);
	}

	@Benchmark
	public int compareUnsigned(Blackhole bh) {
		final int eq = ArrayVectors.compareUnsigned(left, rightEqual);
		final int ne = ArrayVectors.compareUnsigned(left, rightMismatch);
		bh.consume(eq);
		return ne;
	}

	@Benchmark
	public int crc32() {
		return ArrayVectors.crc32(left);
	}

	@Benchmark
	public int scalarFastHashBaseline() {
		return ArrayUtil.fastHash(left);
	}
}