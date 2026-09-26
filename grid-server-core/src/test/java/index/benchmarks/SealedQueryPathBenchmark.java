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

import org.genfork.grid.mem.GridScalableMap;
import org.genfork.grid.replication.snapshot.sealed.SealedBPTreeReader;
import org.genfork.grid.replication.snapshot.sealed.SealedBPTreeWriter;
import org.genfork.grid.replication.snapshot.sealed.SealedGridMapReader;
import org.genfork.grid.replication.snapshot.sealed.SealedGridMapWriter;
import org.junit.jupiter.api.Test;
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
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.genfork.grid.mem.index.IndexPointerRef;
import org.genfork.grid.mem.index.btree.GridPointerBPTree;
import org.genfork.grid.mem.index.btree.IndexOperationResult;
import org.genfork.grid.mem.index.btree.SingleTreeKey;
import org.genfork.grid.replication.snapshot.sealed.SealedIndexKeyOrder;
import org.genfork.grid.serial.WireLikeMatcher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Compares primary-key and sealed secondary-index query paths in RAM, disk, and hybrid modes.
 *
 * @author: GenCloud
 * @date: 2026/05
 * @since: 1.0
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@Threads(1)
@State(Scope.Benchmark)
public class SealedQueryPathBenchmark extends AbstractLatencyBenchmark {
	private static final String DOMAIN = "sealed-query";
	private static final int SECONDARY_BUCKETS = 4_096;
	private static final int RANGE_WIDTH = 10;
	private static final int SEALED_WARMUP_ITERATIONS = 4;
	private static final int SEALED_MEASUREMENT_ITERATIONS = 5;
	private static final int FAST_SEALED_WARMUP_ITERATIONS = 1;
	private static final int FAST_SEALED_MEASUREMENT_ITERATIONS = 2;
	private static final int SEALED_ITERATION_SECONDS = 1;

	@Param({"1000", "100000", "1000000"})
	public int rows;

	@Param({"memory", "disk", "hybrid"})
	public String mode;

	private final AtomicInteger counter = new AtomicInteger();
	private GridScalableMap memoryMap = new GridScalableMap();
	private GridPointerBPTree memoryIndex = new GridPointerBPTree("secondary-LAX", false);
	private Path root;
	private Path sealedRootLazy;
	private SealedGridMapReader sealedMap;
	private SealedBPTreeReader sealedIndex;

	@Setup(Level.Trial)
	public void setup() throws Exception {
		counter.set(0);
		if (memoryMap != null) {
			memoryMap.destroy();
		}
		memoryMap = new GridScalableMap();
		memoryIndex = new GridPointerBPTree("secondary-LAX", false);
		root = Files.createTempDirectory("sealed-query-path");
		final List<SealedGridMapWriter.Kv> mapEntries = new ArrayList<>(rows);
		final List<SealedBPTreeWriter.Entry> indexEntries = new ArrayList<>(rows);
		for (int i = 0; i < rows; i++) {
			final byte[] key = rowKey(i);
			final byte[] value = rowValue(i);
			final int secondary = i % SECONDARY_BUCKETS;
			mapEntries.add(new SealedGridMapWriter.Kv(key, value));
			indexEntries.add(new SealedBPTreeWriter.Entry(indexKey(secondary), key));
			// memory = RAM only; hybrid = FULL working-set hydrate + sealed SoT (product hydrateMode=FULL).
			if ("memory".equals(mode) || "hybrid".equals(mode)) {
				memoryMap.put(key, value);
				memoryIndex.insert(new SingleTreeKey(indexKey(secondary)), new IndexPointerRef(0L, key));
			}
		}
		indexEntries.sort((left, right) -> compareBytes(left.indexKey(), right.indexKey()));
		SealedGridMapWriter.writeNodes(root, DOMAIN, 0, 1L, mapEntries);
		final Path indexFile = root.resolve("sealed-query-status.sbpt");
		SealedBPTreeWriter.write(indexFile, DOMAIN, 0, "secondary", indexEntries);
		if ("disk".equals(mode)) {
			sealedMap = SealedGridMapReader.openShard(root, DOMAIN, 0);
			sealedIndex = SealedBPTreeReader.open(indexFile);
		} else if ("hybrid".equals(mode)) {
			for (int i = 0; i < rows; i += Math.max(1, rows / 4096)) {
				memoryMap.get(rowKey(i));
			}
			// Defer openShard until first miss — FULL-hydrate hot path must match memory TLB/RSS.
			sealedRootLazy = root;
		} else {
			for (int i = 0; i < rows; i += Math.max(1, rows / 4096)) {
				memoryMap.get(rowKey(i));
			}
		}
	}

	@TearDown(Level.Trial)
	public void tearDown() throws Exception {
		if (sealedIndex != null) {
			sealedIndex.close();
		}
		if (sealedMap != null) {
			sealedMap.close();
		}
		if (root != null) {
			try (Stream<Path> paths = Files.walk(root)) {
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
					Files.deleteIfExists(path);
				}
			}
		}
	}

	@Benchmark
	public void pkGet(Blackhole blackhole) {
		blackhole.consume(pkGetValue(rowKey(nextRow())));
	}

	@Benchmark
	public void whereEq(Blackhole blackhole) {
		final int secondary = nextRow() % SECONDARY_BUCKETS;
		consumeRows(indexEq(secondary), blackhole);
	}

	@Benchmark
	public void whereRange(Blackhole blackhole) {
		final int low = nextRow() % (SECONDARY_BUCKETS - RANGE_WIDTH);
		consumeRows(indexRange(low, low + RANGE_WIDTH - 1), blackhole);
	}

	/** Trailing-% LIKE over secondary wire keys (sealed {@code searchMatching} / RAM filter). */
	@Benchmark
	public void whereLike(Blackhole blackhole) {
		final int secondary = nextRow() % SECONDARY_BUCKETS;
		consumeRows(indexLike(secondary), blackhole);
	}

	/** Open GT bound on secondary (sealed leaf walk / RAM {@code searchGreaterThan}). */
	@Benchmark
	public void whereOpenGt(Blackhole blackhole) {
		final int bound = nextRow() % (SECONDARY_BUCKETS - 1);
		consumeRows(indexOpenGt(bound), blackhole);
	}

	/**
	 * Named {@code zzPkMiss} so JMH runs it after hot {@code whereEq}/{@code whereRange}:
	 * miss opens lazy sealed node directories and would otherwise TLB-pollute FULL-hydrate index scores.
	 */
	@Benchmark
	public void zzPkMiss(Blackhole blackhole) {
		blackhole.consume(pkMissValue(("missing-" + nextRow()).getBytes(StandardCharsets.UTF_8)));
	}

	@Benchmark
	public void whereEqEmpty(Blackhole blackhole) {
		blackhole.consume(indexEq(SECONDARY_BUCKETS + 1));
	}

	private int nextRow() {
		return Math.floorMod(counter.getAndIncrement(), rows);
	}

	/**
	 * PK hit path. Hybrid FULL hydrate never consults sealed here — keeps JMH profile off the miss path
	 * shared with {@link #zzPkMiss} (otherwise branch pollution makes hybrid.pkGet look like sealed+RAM).
	 */
	private byte[] pkGetValue(byte[] key) {
		if ("disk".equals(mode)) {
			return sealedMap.get(key);
		}
		return memoryMap.get(key);
	}

	private byte[] pkMissValue(byte[] key) {
		if ("memory".equals(mode)) {
			return memoryMap.get(key);
		}
		if ("hybrid".equals(mode)) {
			final byte[] value = memoryMap.get(key);
			if (value != null) {
				return value;
			}
			return ensureSealedMap().get(key);
		}
		return sealedMap.get(key);
	}

	private byte[] readValue(byte[] key) {
		if ("memory".equals(mode)) {
			return memoryMap.get(key);
		}
		if ("hybrid".equals(mode)) {
			final byte[] value = memoryMap.get(key);
			if (value != null) {
				return value;
			}
			return promoteFromSealed(key);
		}
		return sealedMap.get(key);
	}

	private byte[] promoteFromSealed(byte[] key) {
		final byte[] fromSealed = ensureSealedMap().get(key);
		if (fromSealed != null) {
			memoryMap.put(key, fromSealed);
		}
		return fromSealed;
	}

	private SealedGridMapReader ensureSealedMap() {
		if (sealedMap != null) {
			return sealedMap;
		}
		try {
			sealedMap = SealedGridMapReader.openShard(sealedRootLazy != null ? sealedRootLazy : root, DOMAIN, 0);
			return sealedMap;
		} catch (IOException failure) {
			throw new IllegalStateException("failed to open sealed map pack", failure);
		}
	}

	private List<byte[]> indexEq(int secondary) {
		// memory + hybrid FULL hydrate: RAM index is complete; never open sealed on empty hot bucket
		// (would TLB-pollute sibling JMH methods and disagree with hydrateMode=FULL).
		if ("memory".equals(mode) || "hybrid".equals(mode)) {
			return pointersToKeys(memoryIndex.searchEq(new SingleTreeKey(indexKey(secondary))));
		}
		return sealedIndex.searchEq(indexKey(secondary));
	}

	private List<byte[]> indexRange(int low, int high) {
		if ("memory".equals(mode) || "hybrid".equals(mode)) {
			return pointersToKeys(memoryIndex.searchRange(
					new SingleTreeKey(indexKey(low)), new SingleTreeKey(indexKey(high))));
		}
		return sealedIndex.searchRange(indexKey(low), indexKey(high));
	}

	private List<byte[]> indexLike(int secondaryPrefix) {
		final byte[] pattern = likePattern(secondaryPrefix);
		if ("memory".equals(mode) || "hybrid".equals(mode)) {
			final List<byte[]> keys = new ArrayList<>();
			for (int s = 0; s < SECONDARY_BUCKETS; s++) {
				final byte[] key = indexKey(s);
				if (WireLikeMatcher.matches(key, pattern)) {
					keys.addAll(pointersToKeys(memoryIndex.searchEq(new SingleTreeKey(key))));
				}
			}
			return keys;
		}
		return sealedIndex.searchMatching(indexKey -> WireLikeMatcher.matches(indexKey, pattern));
	}

	private List<byte[]> indexOpenGt(int bound) {
		final byte[] boundKey = indexKey(bound);
		if ("memory".equals(mode) || "hybrid".equals(mode)) {
			return pointersToKeys(memoryIndex.searchGreaterThan(new SingleTreeKey(boundKey)));
		}
		return sealedIndex.searchMatching(
				indexKey -> SealedIndexKeyOrder.compare(indexKey, boundKey) > 0);
	}

	/** Wire LIKE pattern {@code <4-byte-prefix>%} matching one secondary bucket's BE int key. */
	private static byte[] likePattern(int secondary) {
		final byte[] literal = indexKey(secondary);
		final byte[] pattern = new byte[literal.length + 1];
		System.arraycopy(literal, 0, pattern, 0, literal.length);
		pattern[literal.length] = (byte) '%';
		return pattern;
	}

	private static List<byte[]> pointersToKeys(IndexOperationResult result) {
		if (result == null || result.getPointers() == null || result.getPointers().isEmpty()) {
			return List.of();
		}
		final List<byte[]> keys = new ArrayList<>(result.getPointers().size());
		for (IndexPointerRef pointer : result.getPointers()) {
			keys.add(pointer.resolveKey());
		}
		return keys;
	}

	private void consumeRows(List<byte[]> keys, Blackhole blackhole) {
		for (byte[] key : keys) {
			blackhole.consume(readValue(key));
		}
	}

	private static byte[] rowKey(int row) {
		return ("k" + row).getBytes(StandardCharsets.UTF_8);
	}

	private static byte[] rowValue(int row) {
		return ("v" + row).getBytes(StandardCharsets.UTF_8);
	}

	private static byte[] indexKey(int secondary) {
		return ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.BIG_ENDIAN).putInt(secondary).array();
	}

	private static int compareBytes(byte[] left, byte[] right) {
		for (int i = 0; i < Math.min(left.length, right.length); i++) {
			final int comparison = Byte.compareUnsigned(left[i], right[i]);
			if (comparison != 0) {
				return comparison;
			}
		}
		return Integer.compare(left.length, right.length);
	}

	@Test
	@Override
	public void runJmh() throws Exception {
		final String[] selectedRows = propertyValues("sealed.bench.rows", "1000,100000,1000000");
		final String[] selectedModes = propertyValues("sealed.bench.modes", "memory,disk,hybrid");
		final Path resultsDir = Path.of("benchmarks", "results");
		Files.createDirectories(resultsDir);
		String stamp = System.getProperty("jmh.result.stamp");
		if (stamp == null || stamp.isBlank()) {
			stamp = System.getenv("JMH_STAMP");
		}
		if (stamp == null || stamp.isBlank()) {
			stamp = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
		}
		final Path resultFile = resultsDir.resolve(stamp + "-latency-SealedQueryPathBenchmark.json");
		final boolean fast = jmhFastMode();
		final int warmupIters = fast ? FAST_SEALED_WARMUP_ITERATIONS : SEALED_WARMUP_ITERATIONS;
		final int measureIters = fast ? FAST_SEALED_MEASUREMENT_ITERATIONS : SEALED_MEASUREMENT_ITERATIONS;
		if (fast) {
			System.out.println("JMH_FAST=1 sealed: warmup=" + warmupIters + " measure=" + measureIters
					+ " rows=" + String.join(",", selectedRows) + " (smoke only)");
		}
		final Options options = new OptionsBuilder()
				.include("\\.SealedQueryPathBenchmark\\.")
				.param("rows", selectedRows)
				.param("mode", selectedModes)
				.warmupIterations(warmupIters)
				.warmupTime(TimeValue.seconds(SEALED_ITERATION_SECONDS))
				.measurementIterations(measureIters)
				.measurementTime(TimeValue.seconds(SEALED_ITERATION_SECONDS))
				.forks(1)
				.threads(1)
				.shouldDoGC(true)
				.shouldFailOnError(true)
				.resultFormat(ResultFormatType.JSON)
				.result(resultFile.toString())
				.jvmArgs("-server", "-Dsealed.metrics=false", "--add-opens=java.base/java.lang=ALL-UNNAMED",
						"--add-opens=java.base/java.nio=ALL-UNNAMED",
						"--add-opens=java.base/java.util=ALL-UNNAMED",
						"--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
						"--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
						"--add-modules=jdk.incubator.vector", "--enable-preview")
				.build();
		new Runner(options).run();
		System.out.println("JMH JSON -> " + resultFile.toAbsolutePath());
	}

	private static String[] propertyValues(String name, String defaultValue) throws IOException {
		final String value = System.getProperty(name, defaultValue);
		final String[] values = Stream.of(value.split(","))
				.map(String::trim)
				.filter(item -> !item.isEmpty())
				.toArray(String[]::new);
		if (values.length == 0) {
			throw new IOException("empty JMH parameter property: " + name);
		}
		return values;
	}
}
