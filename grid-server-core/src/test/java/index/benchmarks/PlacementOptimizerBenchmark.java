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

import org.genfork.grid.replication.swarm.HierarchicalPlacementOptimizer;
import org.genfork.grid.replication.swarm.PlacementPlan;
import org.genfork.grid.replication.swarm.PlacementScore;
import org.genfork.grid.replication.swarm.PlacementTopology;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Hierarchical multipopulation placement search cost (placement-optimizer / HDCRM-class).
 * No direct OSS twin — reports absolute µs/tick for ops budgeting vs threshold Swarm.
 *
 * @author: GenCloud
 * @date: 2026/12
 * @since: 1.0
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@Threads(1)
@State(Scope.Benchmark)
public class PlacementOptimizerBenchmark extends AbstractLatencyBenchmark {
	private HierarchicalPlacementOptimizer optimizer;
	private PlacementTopology topology;
	private PlacementScore stressed;

	@Setup
	public void setup() {
		optimizer = new HierarchicalPlacementOptimizer(
				true, 8, 12, 3, 0.25, 0.15, 2, 42L, 0.3
		);
		final List<PlacementTopology.PeerEndpoint> peers = List.of(
				new PlacementTopology.PeerEndpoint("r1", "dc-a"),
				new PlacementTopology.PeerEndpoint("r2", "dc-a"),
				new PlacementTopology.PeerEndpoint("r3", "dc-b"),
				new PlacementTopology.PeerEndpoint("r4", "dc-b")
		);
		final List<PlacementTopology.StreamPlacement> streams = new ArrayList<>();
		for (int s = 0; s < 32; s++) {
			streams.add(new PlacementTopology.StreamPlacement("demo", s, "primary", null, 100 + s));
		}
		topology = new PlacementTopology("primary", "dc-a", peers, streams);
		stressed = new PlacementScore(5_000, 2_000, 0.85, 0.4, 80, 0.72);
	}

	@Benchmark
	public void hierarchicalSearchTick(Blackhole bh) {
		final PlacementPlan plan = optimizer.search(topology, stressed);
		bh.consume(plan.hint());
		bh.consume(plan.migrations().size());
	}
}
