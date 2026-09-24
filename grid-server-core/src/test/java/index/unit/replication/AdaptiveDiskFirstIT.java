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

import org.genfork.grid.mem.GridScalableMap;
import org.genfork.grid.mem.adaptive.AdaptiveDiskFirstController;
import org.genfork.grid.mem.adaptive.AdaptiveDiskFirstMode;
import org.genfork.grid.mem.stage.GridEntriesProcessor;
import org.genfork.grid.mem.stage.WorkingSetBudget;
import org.genfork.grid.replication.metrics.ReplicationMetrics;
import org.genfork.grid.replication.snapshot.sealed.SealedGridMapReader;
import org.genfork.grid.replication.snapshot.sealed.SealedGridMapWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2.3: adaptive disk-first — fill → HIGH → evict / sealed miss; pressure drop → warm again.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
class AdaptiveDiskFirstIT {
	@TempDir
	Path tmp;

	@Test
	void fillHighEvictsThenLowWarmsAgain() throws Exception {
		final List<SealedGridMapWriter.Kv> kvs = new ArrayList<>();
		for (int i = 0; i < 40; i++) {
			kvs.add(new SealedGridMapWriter.Kv(
					("k" + i).getBytes(StandardCharsets.UTF_8),
					("v" + i).getBytes(StandardCharsets.UTF_8)));
		}
		SealedGridMapWriter.writeNodes(tmp, "adaptive", 0, 10L, kvs);

		final AdaptiveDiskFirstController controller = new AdaptiveDiskFirstController(32, false);
		final GridEntriesProcessor proc = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
		final WorkingSetBudget budget = new WorkingSetBudget(controller.effectiveMaxEntries());
		budget.setEvictHandler((shard, key) -> proc.evictCommitted(key));
		proc.setWorkingSetBudget(budget);
		controller.registerBudget(budget);

		final long modeBefore = ReplicationMetrics.adaptiveModeChanges();

		try (SealedGridMapReader reader = SealedGridMapReader.openShard(tmp, "adaptive", 0)) {
			proc.setSealedReader(reader);

			// Warm under LOW-ish free ratio (NORMAL/LOW path loads into WS).
			controller.setFreeRatioOverride(0.55);
			controller.evaluate(0.55, 0, controller.effectiveMaxEntries(), 0L);
			assertTrue(controller.mode() == AdaptiveDiskFirstMode.LOW
					|| controller.mode() == AdaptiveDiskFirstMode.NORMAL);

			for (int i = 0; i < 40; i++) {
				final byte[] got = proc.getCommitted(("k" + i).getBytes(StandardCharsets.UTF_8));
				assertArrayEquals(("v" + i).getBytes(StandardCharsets.UTF_8), got);
			}
			assertTrue(proc.mapSize() > 0, "expected warm residency before HIGH");

			// Pressure HIGH → tighten + prefer sealed-only.
			controller.evaluate(0.10, budget.size(), Math.max(1, budget.maxEntries()), 0L);
			assertEquals(AdaptiveDiskFirstMode.HIGH, controller.mode());
			assertTrue(budget.preferSealedOnly());
			assertTrue(budget.maxEntries() <= 32 / 8,
					"tightened cap=" + budget.maxEntries());
			assertTrue(proc.mapSize() <= budget.maxEntries() + 2,
					"mapSize=" + proc.mapSize() + " cap=" + budget.maxEntries());
			assertTrue(ReplicationMetrics.adaptiveModeChanges() > modeBefore);

			// Sealed miss still serves correctly without growing RAM under HIGH.
			final int mapBeforeMiss = proc.mapSize();
			assertArrayEquals("v0".getBytes(StandardCharsets.UTF_8),
					proc.getCommitted("k0".getBytes(StandardCharsets.UTF_8)));
			assertTrue(proc.mapSize() <= mapBeforeMiss + 1,
					"HIGH must not aggressively warm map");

			// Pressure drop → LOW: relax cap, allow warm again.
			controller.evaluate(0.55, 0, controller.effectiveMaxEntries(), 0L);
			assertEquals(AdaptiveDiskFirstMode.LOW, controller.mode());
			assertTrue(!budget.preferSealedOnly());
			assertEquals(32, budget.maxEntries());

			for (int i = 0; i < 16; i++) {
				proc.getCommitted(("k" + i).getBytes(StandardCharsets.UTF_8));
			}
			assertTrue(proc.mapSize() >= 8, "LOW should warm hot keys mapSize=" + proc.mapSize());
		}
	}

	@Test
	void sealedMissBurstDoesNotEnterHighOnHealthyHeap() {
		final AdaptiveDiskFirstController controller = new AdaptiveDiskFirstController(1_048_576, true);
		controller.setFreeRatioOverride(0.90);
		controller.evaluate(0.90, 0, controller.effectiveMaxEntries(), 0L);
		assertEquals(AdaptiveDiskFirstMode.LOW, controller.mode());

		// Sparse Capacity EQ produces continuous sealed-miss ticks; must not pin HIGH /
		// preferSealedOnlyReads while the heap is healthy (READ_ONLY death spiral).
		controller.evaluate(0.90, 1_000, controller.effectiveMaxEntries(), 10_000L);
		assertTrue(controller.mode() == AdaptiveDiskFirstMode.LOW
				|| controller.mode() == AdaptiveDiskFirstMode.NORMAL);
		assertTrue(!controller.preferSealedOnlyReads());
	}
}
