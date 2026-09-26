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

import jodd.util.Bits;
import org.genfork.grid.mem.index.btree.comparator.ArraysComparator;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * Narrow {@link ArraysComparator} signed INT/LONG compare stamp (critical-engineering §8).
 *
 * @author: GenCloud
 * @date: 2025/04
 * @since: 1.0
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class ArraysComparatorSignedBenchmark extends AbstractBenchmark {
	private static final ArraysComparator COMPARATOR = new ArraysComparator();

	private byte[] leftInt;
	private byte[] rightInt;
	private byte[] leftLong;
	private byte[] rightLong;

	@Setup
	public void setup() {
		leftInt = new byte[Integer.BYTES];
		rightInt = new byte[Integer.BYTES];
		Bits.putInt(leftInt, 0, 1);
		Bits.putInt(rightInt, 0, -1);
		leftLong = new byte[Long.BYTES];
		rightLong = new byte[Long.BYTES];
		Bits.putLong(leftLong, 0, 1L);
		Bits.putLong(rightLong, 0, -1L);
	}

	@Benchmark
	public int compareSignedInt() {
		return COMPARATOR.compare(leftInt, rightInt);
	}

	@Benchmark
	public int compareSignedLong() {
		return COMPARATOR.compare(leftLong, rightLong);
	}
}