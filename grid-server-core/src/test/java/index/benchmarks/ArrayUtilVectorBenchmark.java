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
import java.util.Random;
import java.util.concurrent.TimeUnit;

import org.genfork.grid.utils.ArrayUtil;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * Vector equality versus scalar JDK equality and unchanged scalar hash contracts.
 *
 * @author: GenCloud
 * @date: 2026/12
 * @since: 1.0
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class ArrayUtilVectorBenchmark extends AbstractBenchmark {
	private static final long RANDOM_SEED = 0xA22A7L;

	@Param({"8", "64", "256", "1024", "4096"})
	private int length;
	private byte[] left;
	private byte[] equal;

	@Setup
	public void setup() {
		final Random random = new Random(RANDOM_SEED + length);
		left = new byte[length];
		random.nextBytes(left);
		equal = left.clone();
	}

	@Benchmark
	public boolean scalarEquals() {
		return Arrays.equals(left, equal);
	}

	@Benchmark
	public boolean vectorEquals() {
		return ArrayUtil.bytesEqual(left, equal);
	}

	@Benchmark
	public int scalarHashContract() {
		return Arrays.hashCode(left);
	}

	@Benchmark
	public int fastHashContract() {
		return ArrayUtil.fastHash(left);
	}
}
