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
package index.unit.overlay;

import org.genfork.grid.overlay.OverlayStore;
import org.genfork.grid.replication.metrics.ReplicationMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Durable overlay sidecar (.ovl) reload + pin/unpin.
 *
 * @author: GenCloud
 * @date: 2026/02
 * @since: 1.0
 */
public class OverlayDurableStoreTest {

	@TempDir
	Path tempDir;

	@Test
	void durableOverlaySurvivesReload() {
		final Path dir = tempDir.resolve("overlay");
		final OverlayStore first = new OverlayStore(true, dir);
		first.put("demo.Domain", 42L, 60_000L, true, "gold");
		assertEquals(1, first.size());

		final OverlayStore second = new OverlayStore(true, dir);
		assertTrue(second.get("demo.Domain", 42L).isPresent());
		assertTrue(second.get("demo.Domain", 42L).get().pin());
		assertEquals("gold", second.get("demo.Domain", 42L).get().qosTag());
		assertTrue(second.hasAnyPinned());
	}

	@Test
	void durablePinReloadAndUnpinDeletesOvl() throws Exception {
		final Path dir = tempDir.resolve("overlay-pin");
		final OverlayStore first = new OverlayStore(true, dir);
		first.put("orders", 7L, null, true, "qos-a");
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.ovl")) {
			assertTrue(stream.iterator().hasNext());
		}

		final OverlayStore reloaded = new OverlayStore(true, dir);
		assertTrue(reloaded.hasAnyPinned());
		assertTrue(reloaded.get("orders", 7L).isPresent());

		assertTrue(reloaded.remove("orders", 7L));
		assertFalse(reloaded.hasAnyPinned());
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.ovl")) {
			assertFalse(stream.iterator().hasNext());
		}

		final OverlayStore afterUnpin = new OverlayStore(true, dir);
		assertFalse(afterUnpin.hasAnyPinned());
		assertTrue(afterUnpin.get("orders", 7L).isEmpty());
	}

	@Test
	void pinIncrementsOverlayPinnedKeysMetric() {
		final long before = ReplicationMetrics.overlayPinnedKeys();
		final OverlayStore store = new OverlayStore(true);
		store.put("t", 1L, null, true, null);
		assertTrue(ReplicationMetrics.overlayPinnedKeys() >= before + 1L);
		store.remove("t", 1L);
	}
}
