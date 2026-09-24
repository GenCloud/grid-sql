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

import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.repair.HomologousRepair;
import org.genfork.grid.replication.repair.RepairCommand;
import org.genfork.grid.replication.repair.RepairCommandType;
import org.genfork.grid.replication.repair.VersionLocus;
import org.genfork.grid.replication.swarm.AdaptiveReplicaSwarm;
import org.genfork.grid.replication.swarm.HierarchicalPlacementOptimizer;
import org.genfork.grid.replication.swarm.PlacementHint;
import org.genfork.grid.replication.swarm.PlacementPlan;
import org.genfork.grid.replication.swarm.PlacementTopology;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public class HomologousRepairAndSwarmTest {

	@Test
	void reconcileEmitsFetchWhenMissingLocally() {
		final HomologousRepair repair = new HomologousRepair();
		final ReplicationOp local = OpLogCodec.withChecksum(new ReplicationOp(
				"d", 0, 1L, ReplicationOpType.UPSERT, new byte[]{1}, new byte[]{2}, 1L, 0L
		));
		repair.observe(local);

		final Map<Long, VersionLocus> remote = new HashMap<>();
		final long otherHash = HomologousRepair.keyHash(new byte[]{9});
		remote.put(otherHash, new VersionLocus("d", 0, otherHash, 5L, 1L, 99L));
		remote.put(HomologousRepair.keyHash(new byte[]{1}),
				new VersionLocus("d", 0, HomologousRepair.keyHash(new byte[]{1}), 3L, 1L, 1L));

		final List<RepairCommand> commands = repair.reconcile("d", 0, remote);
		assertFalse(commands.isEmpty());
		assertTrue(commands.stream().anyMatch(c -> c.type() == RepairCommandType.FETCH_OP));
		assertTrue(commands.stream().anyMatch(c -> c.type() == RepairCommandType.RESHIP_SEGMENT));
		assertTrue(repair.repairIssued() >= 2);
	}

	@Test
	void swarmProducesHintsFromSensors() {
		final AdaptiveReplicaSwarm swarm = new AdaptiveReplicaSwarm(1000, 0.3);
		swarm.registerSensor("applyLag", () -> 900.0);
		swarm.registerSensor("queueDepth", () -> 8000.0);
		swarm.registerSensor("heapPressure", () -> 0.9);
		swarm.registerSensor("hitRate", () -> 0.2);
		swarm.registerSensor("rttMs", () -> 150.0);
		final PlacementHint hint = swarm.tick();
		assertEquals(PlacementHint.SHED_LOAD, hint);
		assertTrue(swarm.shipUrgencyMultiplier() > 1.0);
		assertTrue(swarm.preferCatchUp());
	}

	@Test
	void swarmOptimizerDrivesConcreteMigratePlan() {
		final HierarchicalPlacementOptimizer optimizer = new HierarchicalPlacementOptimizer(
				true, 8, 12, 4, 0.35, 0.2, 2, 5L, 0.3
		);
		final AdaptiveReplicaSwarm swarm = new AdaptiveReplicaSwarm(1000, 0.3, optimizer);
		swarm.registerSensor("applyLag", () -> 900.0);
		swarm.registerSensor("queueDepth", () -> 8000.0);
		swarm.registerSensor("heapPressure", () -> 0.9);
		swarm.registerSensor("hitRate", () -> 0.2);
		swarm.registerSensor("rttMs", () -> 40.0);
		swarm.tick();
		assertTrue(swarm.placementOptimizerEnabled());

		final PlacementTopology topology = new PlacementTopology(
				"primary-1",
				"dc-a",
				List.of(
						new PlacementTopology.PeerEndpoint("replica-1", "dc-a"),
						new PlacementTopology.PeerEndpoint("replica-2", "dc-a")
				),
				List.of(
						new PlacementTopology.StreamPlacement("d", 0, "primary-1", null, 900),
						new PlacementTopology.StreamPlacement("d", 1, "primary-1", null, 800)
				)
		);
		final PlacementPlan plan = swarm.optimizePlacement(topology);
		assertTrue(plan.hasMigrations());
		assertEquals(plan.hint(), swarm.getLastHint().get());
		assertEquals(plan, swarm.getLastPlan().get());
	}
}
