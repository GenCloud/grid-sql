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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.genfork.grid.mem.index.IndexPointerRef;
import org.genfork.grid.mem.index.bitmap.GridBitmapIndex;
import org.genfork.grid.mem.index.btree.IndexOperationResult;
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
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * JMH: {@link GridBitmapIndex} wire-key searchEq / searchIn / AND intersect.
 * <p>
 * Track: {@code mvn -pl grid-server-core -Dtest=GridBitmapIndexBenchmark -Pjmh test}
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@Threads(1)
@State(Scope.Benchmark)
public class GridBitmapIndexBenchmark extends AbstractLatencyBenchmark {
	private static final String FLAG_INDEX = "flag-BITMAP";
	private static final String COLOR_INDEX = "color-BITMAP";
	private static final int BITMAP_CAPACITY = 1 << 14;
	private static final int FLAG_CARDINALITY = 8;
	private static final int COLOR_CARDINALITY = 4;
	private static final int IN_LIST_SIZE = 3;

	@Param({"4096", "16384"})
	public int rowCount;

	private GridBitmapIndex flagIndex;
	private GridBitmapIndex colorIndex;
	private SingleTreeKey eqKey;
	private List<SingleTreeKey> inKeys;
	private SingleTreeKey colorEqKey;

	@Setup(Level.Trial)
	public void setupBenchmark() {
		flagIndex = new GridBitmapIndex(FLAG_INDEX, BITMAP_CAPACITY);
		colorIndex = new GridBitmapIndex(COLOR_INDEX, BITMAP_CAPACITY);
		for (int i = 0; i < rowCount; i++) {
			final byte[] rowKey = SqlWireUtil.toGenericArray(i);
			final IndexPointerRef ptr = new IndexPointerRef(0L, rowKey);
			final int flag = i % FLAG_CARDINALITY;
			final int color = i % COLOR_CARDINALITY;
			flagIndex.insert(new SingleTreeKey(SqlWireUtil.toGenericArray(flag)), ptr);
			colorIndex.insert(new SingleTreeKey(SqlWireUtil.toGenericArray(color)), ptr);
		}
		eqKey = new SingleTreeKey(SqlWireUtil.toGenericArray(1));
		colorEqKey = new SingleTreeKey(SqlWireUtil.toGenericArray(0));
		inKeys = new ArrayList<>(IN_LIST_SIZE);
		for (int v = 0; v < IN_LIST_SIZE; v++) {
			inKeys.add(new SingleTreeKey(SqlWireUtil.toGenericArray(v)));
		}
	}

	@Benchmark
	public void searchEq(Blackhole bh) {
		final IndexOperationResult result = flagIndex.searchEq(eqKey);
		bh.consume(result.getSize());
	}

	@Benchmark
	public void searchIn(Blackhole bh) {
		final IndexOperationResult result = flagIndex.searchIn(inKeys);
		bh.consume(result.getSize());
	}

	@Benchmark
	public void andIntersect(Blackhole bh) {
		final IndexOperationResult left = flagIndex.searchEq(eqKey);
		final IndexOperationResult right = colorIndex.searchEq(colorEqKey);
		if (left.hasBitmap() && right.hasBitmap()) {
			bh.consume(left.getBitmap().and(right.getBitmap()).getSetBitCount());
		} else {
			bh.consume(0L);
		}
	}
}
