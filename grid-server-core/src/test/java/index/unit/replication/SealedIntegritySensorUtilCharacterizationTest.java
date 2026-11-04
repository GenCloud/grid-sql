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

import org.genfork.grid.replication.snapshot.sealed.SealedGridMapWriter;
import org.genfork.grid.replication.util.SealedIntegritySensorUtil;
import org.genfork.grid.replication.util.SealedIntegritySensorUtil.PackDebounce;
import org.genfork.grid.replication.util.SealedIntegritySensorUtil.Probe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sealed header sensor: truncate / bad magic → peer pack; debounce ≤1/shard/window.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
class SealedIntegritySensorUtilCharacterizationTest {

	@TempDir
	Path tempDir;

	@Test
	void missingTruncatedBadMagicNeedPack() throws Exception {
		assertEquals(Probe.MISSING, SealedIntegritySensorUtil.probeGmapHeader(tempDir.resolve("nope.gmap")));
		assertTrue(SealedIntegritySensorUtil.needsPeerPack(Probe.MISSING));

		final Path truncated = tempDir.resolve("t.gmap");
		Files.write(truncated, new byte[]{1, 2, 3});
		assertEquals(Probe.TRUNCATED, SealedIntegritySensorUtil.probeGmapHeader(truncated));

		final Path bad = tempDir.resolve("bad.gmap");
		final byte[] header = new byte[SealedIntegritySensorUtil.GMAP_FIXED_HEADER_BYTES];
		ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(0xDEADBEEF);
		Files.write(bad, header);
		assertEquals(Probe.BAD_MAGIC, SealedIntegritySensorUtil.probeGmapHeader(bad));

		final Path ok = tempDir.resolve("ok.gmap");
		final byte[] good = new byte[SealedIntegritySensorUtil.GMAP_FIXED_HEADER_BYTES];
		ByteBuffer.wrap(good).order(ByteOrder.LITTLE_ENDIAN).putInt(SealedGridMapWriter.MAGIC);
		Files.write(ok, good);
		assertEquals(Probe.OK, SealedIntegritySensorUtil.probeGmapHeader(ok));
		assertFalse(SealedIntegritySensorUtil.needsPeerPack(Probe.OK));
	}

	@Test
	void packDebounceOnePerWindow() {
		final PackDebounce debounce = new PackDebounce();
		assertTrue(debounce.tryAcquire("d", 0, 1L, 5_000L));
		assertFalse(debounce.tryAcquire("d", 0, 100L, 5_000L));
		assertTrue(debounce.tryAcquire("d", 0, 5_001L, 5_000L));
	}
}
