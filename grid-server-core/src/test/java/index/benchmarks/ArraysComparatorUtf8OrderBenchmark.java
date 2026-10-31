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

import org.genfork.grid.mem.index.btree.comparator.ArraysComparator;
import org.genfork.grid.serial.SqlWireUtil;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * Narrow {@link ArraysComparator} UTF-8 payload-lex VARCHAR compare stamp.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class ArraysComparatorUtf8OrderBenchmark extends AbstractBenchmark {
	private static final ArraysComparator COMPARATOR = new ArraysComparator();

	private byte[] name9;
	private byte[] name10;
	private byte[] aaaa;
	private byte[] aaab;

	@Setup
	public void setup() {
		name9 = SqlWireUtil.toGenericArray("Name-9");
		name10 = SqlWireUtil.toGenericArray("Name-10");
		aaaa = SqlWireUtil.toGenericArray("AAAA");
		aaab = SqlWireUtil.toGenericArray("AAAB");
	}

	@Benchmark
	public int compareDifferentLengthUtf8() {
		return COMPARATOR.compare(name9, name10);
	}

	@Benchmark
	public int compareUtf8LenFourOccupyingLongLane() {
		return COMPARATOR.compare(aaaa, aaab);
	}
}
