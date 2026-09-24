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
package index.unit.replication;

import org.genfork.grid.replication.swarm.HierarchicalPlacementOptimizer;
import org.genfork.grid.replication.swarm.PeerRole;
import org.genfork.grid.replication.swarm.PlacementHint;
import org.genfork.grid.replication.swarm.PlacementPlan;
import org.genfork.grid.replication.swarm.PlacementScore;
import org.genfork.grid.replication.swarm.PlacementTopology;
import org.genfork.grid.replication.swarm.ShardMigrateAction;
import org.genfork.grid.replication.swarm.VoterSetRecommendation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused unit tests for hierarchical multipopulation placement (placement-optimizer).
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public class HierarchicalPlacementOptimizerTest {

	@Test
	void disabledOptimizerReturnsKeep() {
		final HierarchicalPlacementOptimizer opt = optimizer(false, 42L);
		final PlacementPlan plan = opt.search(sampleTopology(), stressedScore());
		assertEquals(PlacementHint.KEEP, plan.hint());
		assertFalse(plan.hasMigrations());
	}

	@Test
	void stressedSensorsProduceMigrationsTowardPeers() {
		final HierarchicalPlacementOptimizer opt = optimizer(true, 7L);
		final PlacementPlan plan = opt.search(sampleTopology(), stressedScore());
		assertTrue(plan.hasMigrations(), "expected migrate actions under heap/lag pressure");
		assertTrue(plan.migrations().size() <= 2, "maxMigratesPerTick=2");
		for (ShardMigrateAction a : plan.migrations()) {
			assertTrue(Set.of("replica-1", "replica-2", "replica-b").contains(a.targetPeerId()));
			assertFalse("primary-1".equals(a.targetPeerId()));
		}
		assertTrue(plan.hint() == PlacementHint.SHED_LOAD
				|| plan.hint() == PlacementHint.ATTRACT_LEARNER
				|| plan.hint() == PlacementHint.PREFER_DC);
	}

	@Test
	void affinityPinToSelfIsNotMigratedAway() {
		final HierarchicalPlacementOptimizer opt = optimizer(true, 11L);
		final PlacementTopology topology = new PlacementTopology(
				"primary-1",
				"dc-a",
				List.of(
						new PlacementTopology.PeerEndpoint("replica-1", "dc-a"),
						new PlacementTopology.PeerEndpoint("replica-b", "dc-b")
				),
				List.of(
						new PlacementTopology.StreamPlacement("d", 0, "primary-1", "primary-1", 900),
						new PlacementTopology.StreamPlacement("d", 1, "primary-1", null, 800)
				)
		);
		final PlacementPlan plan = opt.search(topology, stressedScore());
		for (ShardMigrateAction a : plan.migrations()) {
			assertFalse(a.shard() == 0 && a.domainType().equals("d"),
					"pinned shard 0 must not appear in migrate plan");
		}
	}

	@Test
	void highRttPrefersLocalDcPeersWhenPossible() {
		final HierarchicalPlacementOptimizer opt = optimizer(true, 99L);
		final PlacementTopology topology = new PlacementTopology(
				"primary-1",
				"dc-a",
				List.of(
						new PlacementTopology.PeerEndpoint("replica-local", "dc-a"),
						new PlacementTopology.PeerEndpoint("replica-remote", "dc-b")
				),
				List.of(
						new PlacementTopology.StreamPlacement("d", 0, "primary-1", null, 100),
						new PlacementTopology.StreamPlacement("d", 1, "primary-1", null, 120),
						new PlacementTopology.StreamPlacement("d", 2, "primary-1", null, 90)
				)
		);
		final PlacementScore highRtt = new PlacementScore(200, 1000, 0.4, 0.8, 180, 0.55);
		final PlacementPlan plan = opt.search(topology, highRtt);
		if (plan.hasMigrations()) {
			final Set<String> targets = plan.migrations().stream()
					.map(ShardMigrateAction::targetPeerId)
					.collect(Collectors.toSet());
			assertTrue(targets.contains("replica-local") || plan.hint() == PlacementHint.PREFER_DC,
					"high RTT should bias toward local DC / PREFER_DC");
		}
	}

	@Test
	void emptyPeersYieldsKeep() {
		final HierarchicalPlacementOptimizer opt = optimizer(true, 1L);
		final PlacementTopology topology = new PlacementTopology(
				"primary-1",
				"dc-a",
				List.of(),
				List.of(new PlacementTopology.StreamPlacement("d", 0, null, null, 10))
		);
		final PlacementPlan plan = opt.search(topology, stressedScore());
		assertFalse(plan.hasMigrations());
	}

	@Test
	void calmSensorsDoNotChurn() {
		final HierarchicalPlacementOptimizer opt = optimizer(true, 3L);
		final PlacementScore calm = new PlacementScore(10, 100, 0.2, 0.95, 20, 0.05);
		final PlacementPlan plan = opt.search(sampleTopology(), calm);
		assertFalse(plan.hasMigrations(), "composite below migrateThreshold must not migrate");
	}

	@Test
	void voterSetHintPrefersTaggedRemoteVoter() {
		final HierarchicalPlacementOptimizer opt = new HierarchicalPlacementOptimizer(
				true, 8, 12, 4, 0.35, 0.2, 2, 42L, 0.3, true, 1
		);
		final PlacementTopology topology = new PlacementTopology(
				"primary-1",
				"dc-a",
				List.of(
						new PlacementTopology.PeerEndpoint("local-peer", "dc-a", PeerRole.LEARNER),
						new PlacementTopology.PeerEndpoint("remote-voter", "dc-b", PeerRole.VOTER),
						new PlacementTopology.PeerEndpoint("remote-learner", "dc-b", PeerRole.LEARNER)
				),
				List.of(new PlacementTopology.StreamPlacement("d", 0, "primary-1", null, 10))
		);
		final VoterSetRecommendation rec = opt.recommendRemoteVoters(topology, stressedScore());
		assertTrue(rec.hasRecommendation());
		assertEquals(List.of("remote-voter"), rec.recommendedRemoteVoters());
	}

	@Test
	void voterSetHintNeverIncludesLocalDcPeers() {
		final HierarchicalPlacementOptimizer opt = new HierarchicalPlacementOptimizer(
				true, 6, 8, 3, 0.3, 0.2, 2, 5L, 0.3, true, 1
		);
		final PlacementTopology topology = new PlacementTopology(
				"primary-1",
				"dc-a",
				List.of(
						new PlacementTopology.PeerEndpoint("local-a", "dc-a", PeerRole.VOTER),
						new PlacementTopology.PeerEndpoint("remote-b", "dc-b", PeerRole.LEARNER)
				),
				List.of()
		);
		final VoterSetRecommendation rec = opt.recommendRemoteVoters(topology, stressedScore());
		assertEquals(List.of("remote-b"), rec.recommendedRemoteVoters());
	}

	private static HierarchicalPlacementOptimizer optimizer(boolean enabled, long seed) {
		return new HierarchicalPlacementOptimizer(
				enabled,
				8,
				12,
				4,
				0.35,
				0.2,
				2,
				seed,
				0.3
		);
	}

	private static PlacementTopology sampleTopology() {
		return new PlacementTopology(
				"primary-1",
				"dc-a",
				List.of(
						new PlacementTopology.PeerEndpoint("replica-1", "dc-a"),
						new PlacementTopology.PeerEndpoint("replica-2", "dc-a"),
						new PlacementTopology.PeerEndpoint("replica-b", "dc-b")
				),
				List.of(
						new PlacementTopology.StreamPlacement("d", 0, "primary-1", null, 900),
						new PlacementTopology.StreamPlacement("d", 1, "primary-1", null, 850),
						new PlacementTopology.StreamPlacement("d", 2, "primary-1", null, 700),
						new PlacementTopology.StreamPlacement("d", 3, "primary-1", null, 500)
				)
		);
	}

	private static PlacementScore stressedScore() {
		return new PlacementScore(900, 8000, 0.9, 0.2, 150, 0.85);
	}
}
