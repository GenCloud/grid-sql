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

import org.genfork.grid.mem.index.IndexPointerRef;
import org.genfork.grid.mem.index.btree.BPValueHelper;
import org.genfork.grid.mem.index.btree.GridPointerBPTree;
import org.genfork.grid.mem.index.btree.SingleTreeKey;
import org.genfork.grid.serial.SqlWireUtil;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * JMH multi-leaf BPTree LE / GE∧LE-via-range / BETWEEN-equivalent searchRange.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@Threads(1)
@State(Scope.Benchmark)
public class MultiLeafOpenBoundBenchmark extends AbstractLatencyBenchmark {
	private static final byte[][] EMPTY_ORDERS = new byte[0][];
	private static final int PAST_MAX_PAD = 50_000;

	@Param({"200", "2000"})
	public int keyCount;

	private GridPointerBPTree tree;
	private SingleTreeKey pastMax;
	private SingleTreeKey mid;
	private SingleTreeKey lo;
	private SingleTreeKey hi;

	@Setup(Level.Trial)
	public void setup() {
		tree = new GridPointerBPTree("jmh-multi-leaf-open", false);
		for (int key = 0; key < keyCount; key++) {
			final byte[] keyBytes = SqlWireUtil.toGenericArray(key);
			final long nativeRef = BPValueHelper.toNativeValueRef(keyBytes, EMPTY_ORDERS);
			tree.insert(new SingleTreeKey(keyBytes), new IndexPointerRef(nativeRef));
		}
		pastMax = new SingleTreeKey(SqlWireUtil.toGenericArray(keyCount + PAST_MAX_PAD));
		mid = new SingleTreeKey(SqlWireUtil.toGenericArray(keyCount / 2));
		lo = new SingleTreeKey(SqlWireUtil.toGenericArray(keyCount / 5));
		hi = new SingleTreeKey(SqlWireUtil.toGenericArray((keyCount * 4) / 5));
	}

	@TearDown(Level.Trial)
	public void tearDown() {
		if (tree != null) {
			tree.clear();
			tree = null;
		}
	}

	@Benchmark
	public void searchLessThanOrEqualPastMax(Blackhole bh) {
		bh.consume(tree.searchLessThanOrEqual(pastMax).getSize());
	}

	@Benchmark
	public void searchLessThanOrEqualMid(Blackhole bh) {
		bh.consume(tree.searchLessThanOrEqual(mid).getSize());
	}

	@Benchmark
	public void searchRangeClosed(Blackhole bh) {
		bh.consume(tree.searchRange(lo, hi).getSize());
	}
}
