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

import org.genfork.grid.replication.swarm.ShardPlacementMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public class ShardPlacementMapTest {

	@Test
	void cutoverChangesOwner() {
		final ShardPlacementMap map = new ShardPlacementMap();
		assertNull(map.owner("d", 0));
		map.setOwner("d", 0, "n1");
		assertEquals("n1", map.owner("d", 0));
		map.cutover("d", 0, "n2");
		assertEquals("n2", map.owner("d", 0));
		assertEquals(ShardPlacementMap.DrainState.CUTOVER_DONE, map.drainState("d", 0));
	}

	@Test
	void drainAndAffinityLifecycle() {
		final ShardPlacementMap map = new ShardPlacementMap();
		map.setOwner("d", 1, "n1");
		map.beginDrain("d", 1, "n2");
		assertEquals("n2", map.affinity("d", 1));
		assertEquals(ShardPlacementMap.DrainState.QUIESCE, map.drainState("d", 1));
		assertTrue(map.isDraining("d", 1));

		map.advanceToCatchUp("d", 1);
		assertEquals(ShardPlacementMap.DrainState.CATCH_UP, map.drainState("d", 1));

		map.cutover("d", 1, "n2");
		assertEquals("n2", map.owner("d", 1));
		assertFalse(map.isDraining("d", 1));
		assertEquals(ShardPlacementMap.DrainState.CUTOVER_DONE, map.drainState("d", 1));
	}
}
