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

import org.genfork.grid.replication.snapshot.sealed.SealedGridMapWriter;
import org.genfork.grid.replication.snapshot.sealed.SealedPackFingerprint;
import org.genfork.grid.replication.util.LocalSealedInventoryIndex;
import org.genfork.grid.replication.util.MissingStreamAdmitState;
import org.genfork.grid.replication.util.ProposerMissingStreamUtil;
import org.genfork.grid.replication.util.SealedInventoryIdentityUtil;
import org.genfork.grid.replication.util.SealedShardInventoryEntry;
import org.genfork.grid.replication.util.OpLogStreamKeyUtil;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * HELLO inventory identity-hash skip vs full ofShardIdentity disk walk.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@Threads(1)
@State(Scope.Benchmark)
public class SealedInventoryIdentityBenchmark extends AbstractLatencyBenchmark {

	private static final String DOMAIN_PREFIX = "inv-bench-";
	private static final int SHARD_COUNT = 256;

	private Path sealedRoot;
	private List<SealedShardInventoryEntry> inventory;
	private Map<String, Long> proposerFpByStream;
	private LocalSealedInventoryIndex localIndex;
	private MissingStreamAdmitState stickyAdmit;

	@Setup
	public void setup() throws Exception {
		sealedRoot = Files.createTempDirectory("sealed-inv-bench");
		inventory = new ArrayList<>(SHARD_COUNT);
		proposerFpByStream = new LinkedHashMap<>(SHARD_COUNT);
		for (int shard = 0; shard < SHARD_COUNT; shard++) {
			final String domain = DOMAIN_PREFIX + shard;
			SealedGridMapWriter.writeNodes(sealedRoot, domain, 0, 1L,
					List.of(new SealedGridMapWriter.Kv(
							new byte[]{(byte) shard}, new byte[]{(byte) (shard + 1)})));
			final long fp = SealedPackFingerprint.ofShardIdentity(sealedRoot, domain, 0);
			inventory.add(new SealedShardInventoryEntry(domain, 0, fp));
			proposerFpByStream.put(OpLogStreamKeyUtil.format(domain, 0), fp);
		}
		localIndex = LocalSealedInventoryIndex.fromEntries(inventory);
		stickyAdmit = new MissingStreamAdmitState();
		stickyAdmit.publishAndRecompute(
				localIndex,
				true,
				proposerFpByStream,
				domain -> domain != null && domain.startsWith(DOMAIN_PREFIX),
				(domain, shard) -> 0L,
				(domain, shard) -> 0L);
	}

	@TearDown
	public void tearDown() throws Exception {
		deleteRecursive(sealedRoot);
	}

	@Benchmark
	public long inventoryIdentityHash(Blackhole bh) {
		final long hash = SealedInventoryIdentityUtil.identityHash(inventory);
		bh.consume(hash);
		return hash;
	}

	@Benchmark
	public long fullOfShardIdentityWalk(Blackhole bh) throws Exception {
		long mix = 0L;
		for (SealedShardInventoryEntry entry : inventory) {
			mix ^= SealedPackFingerprint.ofShardIdentity(sealedRoot, entry.domain(), entry.shard());
		}
		bh.consume(mix);
		return mix;
	}

	@Benchmark
	public boolean proposerMissingStreamProbe(Blackhole bh) {
		final boolean missing = ProposerMissingStreamUtil.hasMissingStream(
				true,
				proposerFpByStream,
				domain -> domain != null && domain.startsWith(DOMAIN_PREFIX),
				(domain, shard) -> 0L,
				(domain, shard) -> 0L,
				(domain, shard) -> 0L);
		bh.consume(missing);
		return missing;
	}

	@Benchmark
	public boolean cachedIndexAdmit(Blackhole bh) {
		final boolean missing = ProposerMissingStreamUtil.hasMissingStream(
				true,
				proposerFpByStream,
				domain -> domain != null && domain.startsWith(DOMAIN_PREFIX),
				(domain, shard) -> 0L,
				(domain, shard) -> 0L,
				localIndex::fingerprint);
		bh.consume(missing);
		return missing;
	}

	@Benchmark
	public boolean stickyAdmitBit(Blackhole bh) {
		final boolean incomplete = stickyAdmit.isIncomplete();
		bh.consume(incomplete);
		return incomplete;
	}

	@Benchmark
	public long diskAdmitWalk(Blackhole bh) throws Exception {
		long mix = 0L;
		for (SealedShardInventoryEntry entry : inventory) {
			mix ^= SealedPackFingerprint.ofShardIdentity(sealedRoot, entry.domain(), entry.shard());
		}
		final boolean missing = ProposerMissingStreamUtil.hasMissingStream(
				true,
				proposerFpByStream,
				domain -> domain != null && domain.startsWith(DOMAIN_PREFIX),
				(domain, shard) -> 0L,
				(domain, shard) -> 0L,
				(domain, shard) -> {
					try {
						return SealedPackFingerprint.ofShardIdentity(sealedRoot, domain, shard);
					} catch (Exception ex) {
						return 0L;
					}
				});
		bh.consume(mix);
		bh.consume(missing);
		return mix;
	}

	@Test
	@Override
	public void runJmh() throws Exception {
		final Path resultsDir = Path.of("benchmarks", "results");
		Files.createDirectories(resultsDir);
		final String date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
		String stamp = System.getProperty("jmh.result.stamp");
		if (stamp == null || stamp.isBlank()) {
			stamp = System.getenv("JMH_STAMP");
		}
		if (stamp == null || stamp.isBlank()) {
			stamp = date;
		}
		final Path resultFile = resultsDir.resolve(stamp + "-thrpt-SealedInventoryIdentityBenchmark.json");
		final boolean fast = jmhFastMode();
		final Options opt = new OptionsBuilder()
				.include("\\.SealedInventoryIdentityBenchmark\\.")
				.warmupIterations(fast ? 1 : 2)
				.measurementIterations(fast ? 1 : 3)
				.warmupTime(TimeValue.seconds(1))
				.measurementTime(TimeValue.seconds(1))
				.forks(1)
				.threads(1)
				.resultFormat(ResultFormatType.JSON)
				.result(resultFile.toString())
				.build();
		new Runner(opt).run();
	}

	private static void deleteRecursive(Path root) throws Exception {
		if (root == null || !Files.exists(root)) {
			return;
		}
		try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
			walk.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (Exception ignored) {
					// best-effort cleanup
				}
			});
		}
	}
}
