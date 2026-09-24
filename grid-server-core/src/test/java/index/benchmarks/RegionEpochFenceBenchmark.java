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

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
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

import org.genfork.grid.replication.region.RegionClaimQuorum;
import org.genfork.grid.replication.region.RegionEpochOps;
import org.genfork.grid.replication.region.RegionLeaseState;
import org.genfork.grid.replication.region.RegionRole;
import org.genfork.grid.replication.region.RegionRoleCoordinator;

/**
 * JMH: region claim / fence hot paths.
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
@Threads(1)
@State(Scope.Benchmark)
public class RegionEpochFenceBenchmark extends AbstractLatencyBenchmark {
	private static final int CLAIM_QUORUM_VOTERS = 2;
	private static final int CLAIM_ACKS = 2;

	private RegionRoleCoordinator coordinator;
	private RegionLeaseState activeLease;

	@Setup(Level.Trial)
	public void setup() throws Exception {
		final Path dataDir = Path.of(System.getProperty("java.io.tmpdir"),
				"region-epoch-jmh-" + System.nanoTime());
		coordinator = new RegionRoleCoordinator(
				dataDir, false, true, RegionRole.ACTIVE, 1L, "a1", "dc-a", 1L, CLAIM_QUORUM_VOTERS);
		activeLease = RegionLeaseState.bootstrap(RegionRole.ACTIVE, 1L, "a1", "dc-a");
	}

	@TearDown(Level.Trial)
	public void tearDown() throws Exception {
		if (coordinator != null) {
			coordinator.close();
		}
	}

	@Benchmark
	public void regionAllowsWrites(Blackhole bh) {
		bh.consume(RegionEpochOps.regionAllowsWrites(activeLease, 1L));
	}

	@Benchmark
	public void fenceOnHigherEpoch(Blackhole bh) {
		bh.consume(RegionEpochOps.mustFenceOnHigherEpoch(1L, 2L));
	}

	@Benchmark
	public void claimQuorum(Blackhole bh) {
		bh.consume(RegionClaimQuorum.hasQuorum(CLAIM_ACKS, CLAIM_QUORUM_VOTERS));
	}

	@Benchmark
	public void coordinatorObserveAndClaim(Blackhole bh) {
		coordinator.forceState(RegionLeaseState.bootstrap(RegionRole.HOLD, 1L, "b1", "dc-b"));
		coordinator.observePeerEpoch(1L, "a1");
		bh.consume(coordinator.tryCompleteClaim(CLAIM_ACKS));
	}
}