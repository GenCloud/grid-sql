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
import org.genfork.grid.replication.snapshot.sealed.SealedPackFingerprint;
import org.genfork.grid.replication.snapshot.sealed.SealedShardPack;
import org.genfork.grid.replication.util.SealedIntegritySensorUtil;
import org.genfork.grid.replication.util.SealedIntegritySensorUtil.PackDebounce;
import org.genfork.grid.replication.util.SealedIntegritySensorUtil.Probe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sealed structural sensor + peer per-shard FP: magic-OK undersize is TRUNCATED locally.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
class SealedIntegritySensorUtilCharacterizationTest {

	private static final String DOMAIN = "agit_auction";
	private static final int SHARD = 3;

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

		final Path magicOnly = tempDir.resolve("magic-only.gmap");
		final byte[] good = new byte[SealedIntegritySensorUtil.GMAP_FIXED_HEADER_BYTES];
		ByteBuffer.wrap(good).order(ByteOrder.LITTLE_ENDIAN).putInt(SealedGridMapWriter.MAGIC);
		Files.write(magicOnly, good);
		final Probe magicOnlyProbe = SealedIntegritySensorUtil.probeGmapHeader(magicOnly);
		assertTrue(SealedIntegritySensorUtil.needsPeerPack(magicOnlyProbe),
				"magic alone is not structurally complete: " + magicOnlyProbe);
		assertFalse(SealedIntegritySensorUtil.needsPeerPack(Probe.OK));
	}

	@Test
	void magicOkUndersizeIsStructuralTruncated() throws Exception {
		final Path donor = tempDir.resolve("donor");
		final Path hole = tempDir.resolve("hole");
		Files.createDirectories(donor);
		Files.createDirectories(hole);

		final byte[] key = new byte[]{4, 4};
		final byte[] val = new byte[]{44};
		SealedGridMapWriter.writeNodes(donor, DOMAIN, SHARD, 11L,
				List.of(new SealedGridMapWriter.Kv(key, val)));
		final byte[] packed = SealedShardPack.pack(donor, DOMAIN, SHARD);
		assertTrue(SealedShardPack.isCompleteShardArtifacts(packed));
		SealedShardPack.unpack(packed, hole);

		final List<Path> holeFiles = SealedShardPack.listShardFiles(hole, DOMAIN, SHARD);
		Path shrinkTarget = null;
		for (Path file : holeFiles) {
			final String name = file.getFileName().toString();
			if (name.endsWith(".gmap") && Files.size(file) > SealedIntegritySensorUtil.GMAP_FIXED_HEADER_BYTES) {
				shrinkTarget = file;
				break;
			}
		}
		assertTrue(shrinkTarget != null, "need a gmap larger than fixed header");
		final long donorSize = Files.size(shrinkTarget);
		final byte[] original = Files.readAllBytes(shrinkTarget);
		final int shrinkSize = Math.max(
				SealedIntegritySensorUtil.GMAP_FIXED_HEADER_BYTES,
				original.length - 64);
		Files.write(shrinkTarget, java.util.Arrays.copyOf(original, shrinkSize));

		assertEquals(Probe.TRUNCATED, SealedIntegritySensorUtil.probeGmapHeader(shrinkTarget),
				"forever: magic-OK undersize is structural TRUNCATED without peer root FP");
		assertEquals(Probe.TRUNCATED, SealedIntegritySensorUtil.probeAllGmapHeaders(hole, DOMAIN, SHARD));
		assertTrue(SealedIntegritySensorUtil.needsPeerPack(Probe.TRUNCATED));

		final long donorFp = SealedPackFingerprint.ofShardIdentity(donor, DOMAIN, SHARD);
		final long holeFp = SealedPackFingerprint.ofShardIdentity(hole, DOMAIN, SHARD);
		assertNotEquals(donorFp, holeFp);
		assertTrue(donorSize > shrinkSize);

		final Probe fpProbe = SealedIntegritySensorUtil.probeFingerprintMismatch(holeFp, donorFp);
		assertEquals(Probe.SIZE_OR_FP_MISMATCH, fpProbe);
		assertTrue(SealedIntegritySensorUtil.needsPeerPack(
				SealedIntegritySensorUtil.combine(Probe.TRUNCATED, fpProbe)));
	}

	@Test
	void packDebounceOnePerWindow() {
		final PackDebounce debounce = new PackDebounce();
		assertTrue(debounce.tryAcquire("d", 0, 1L, 5_000L));
		assertFalse(debounce.tryAcquire("d", 0, 100L, 5_000L));
		assertTrue(debounce.tryAcquire("d", 0, 5_001L, 5_000L));
	}
}
