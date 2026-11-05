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

import org.genfork.grid.context.config.GridConfigurationProperties;
import org.genfork.grid.mem.GridScalableMap;
import org.genfork.grid.mem.stage.GridEntriesProcessor;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.replication.snapshot.sealed.SealedGridMapWriter;
import org.genfork.grid.replication.snapshot.sealed.SealedPackFingerprint;
import org.genfork.grid.replication.snapshot.sealed.SealedShardPack;
import org.genfork.grid.replication.util.SealedIntegritySensorUtil;
import org.genfork.grid.replication.util.SealedShardInventoryEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Forever guard: aggregate root FP mismatch must not ship-all / permanent incomplete.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
class SealedHealStormGuardIT {

	private static final String DOMAIN_A = "storm_a";
	private static final String DOMAIN_B = "storm_b";
	private static final int SHARD = 0;

	@TempDir
	Path tmp;

	@Test
	void rootMismatchWithoutInventoryDoesNotMarkIncomplete() throws Exception {
		final Path sealedA = tmp.resolve("a");
		final Path sealedB = tmp.resolve("b");
		Files.createDirectories(sealedA);
		Files.createDirectories(sealedB);
		SealedGridMapWriter.writeNodes(sealedA, DOMAIN_A, SHARD, 1L,
				List.of(new SealedGridMapWriter.Kv(new byte[]{1}, new byte[]{2})));
		SealedGridMapWriter.writeNodes(sealedB, DOMAIN_B, SHARD, 1L,
				List.of(new SealedGridMapWriter.Kv(new byte[]{3}, new byte[]{4})));
		final long rootA = SealedPackFingerprint.ofSealedRootIdentity(sealedA);
		final long rootB = SealedPackFingerprint.ofSealedRootIdentity(sealedB);
		assertTrue(rootA != rootB);

		final GridConfigurationProperties props = new GridConfigurationProperties();
		props.getDurability().setEnabled(true);
		props.getDurability().setHydrateMode("LAZY");
		props.getReplication().setEnabled(false);
		props.getReplication().getOpLog().setDataDir(tmp.resolve("storm-repl").toString());
		props.getReplication().setClusterId("c1");
		props.getReplication().setNodeId("n1");

		final ReplicationCoordinator coord = new ReplicationCoordinator(props);
		coord.registerDomain(DOMAIN_A, shard -> shard == SHARD
				? new GridEntriesProcessor(SHARD, new GridScalableMap(), null, null)
				: null, true);
		try {
			final Path sealedRoot = coord.getSealedGridMapService().sealedRootOrNull();
			Files.createDirectories(sealedRoot);
			SealedShardPack.unpack(SealedShardPack.pack(sealedA, DOMAIN_A, SHARD), sealedRoot);
			coord.observePeerSealedFence("peer-x");
			assertFalse(coord.isSealedArtifactsFingerprintStale());
			assertFalse(coord.isReplicaDataIncomplete());
		} finally {
			coord.stop();
		}
	}

	@Test
	void structuralUndersizeMarksIncompleteWithoutPeer() throws Exception {
		final Path donor = tmp.resolve("donor");
		Files.createDirectories(donor);
		SealedGridMapWriter.writeNodes(donor, DOMAIN_A, SHARD, 9L,
				List.of(new SealedGridMapWriter.Kv(new byte[]{9, 9}, new byte[]{1})));

		final GridConfigurationProperties props = new GridConfigurationProperties();
		props.getDurability().setEnabled(true);
		props.getDurability().setHydrateMode("LAZY");
		props.getReplication().setEnabled(false);
		props.getReplication().getOpLog().setDataDir(tmp.resolve("hole-repl").toString());
		props.getReplication().setClusterId("c1");
		props.getReplication().setNodeId("n1");

		final ReplicationCoordinator coord = new ReplicationCoordinator(props);
		coord.registerDomain(DOMAIN_A, shard -> shard == SHARD
				? new GridEntriesProcessor(SHARD, new GridScalableMap(), null, null)
				: null, true);
		try {
			final Path sealedRoot = coord.getSealedGridMapService().sealedRootOrNull();
			Files.createDirectories(sealedRoot);
			SealedShardPack.unpack(SealedShardPack.pack(donor, DOMAIN_A, SHARD), sealedRoot);
			final Path gmap = SealedShardPack.listShardFiles(sealedRoot, DOMAIN_A, SHARD).stream()
					.filter(p -> p.getFileName().toString().endsWith(".gmap"))
					.findFirst()
					.orElseThrow();
			final byte[] bytes = Files.readAllBytes(gmap);
			Files.write(gmap, java.util.Arrays.copyOf(
					bytes, Math.max(SealedIntegritySensorUtil.GMAP_FIXED_HEADER_BYTES, bytes.length - 40)));
			assertEquals(
					SealedIntegritySensorUtil.Probe.TRUNCATED,
					SealedIntegritySensorUtil.probeAllGmapHeaders(sealedRoot, DOMAIN_A, SHARD));
			assertTrue(coord.isSealedArtifactsFingerprintStale());
			assertTrue(coord.isReplicaDataIncomplete());
		} finally {
			coord.stop();
		}
	}

	@Test
	void matchingInventoryClearsShardStale() throws Exception {
		final Path donor = tmp.resolve("match");
		Files.createDirectories(donor);
		SealedGridMapWriter.writeNodes(donor, DOMAIN_A, SHARD, 3L,
				List.of(new SealedGridMapWriter.Kv(new byte[]{5}, new byte[]{6})));
		final long shardFp = SealedPackFingerprint.ofShardIdentity(donor, DOMAIN_A, SHARD);

		final GridConfigurationProperties props = new GridConfigurationProperties();
		props.getDurability().setEnabled(true);
		props.getDurability().setHydrateMode("LAZY");
		props.getReplication().setEnabled(false);
		props.getReplication().getOpLog().setDataDir(tmp.resolve("match-repl").toString());
		props.getReplication().setClusterId("c1");
		props.getReplication().setNodeId("n1");

		final ReplicationCoordinator coord = new ReplicationCoordinator(props);
		coord.registerDomain(DOMAIN_A, shard -> shard == SHARD
				? new GridEntriesProcessor(SHARD, new GridScalableMap(), null, null)
				: null, true);
		try {
			final Path sealedRoot = coord.getSealedGridMapService().sealedRootOrNull();
			Files.createDirectories(sealedRoot);
			SealedShardPack.unpack(SealedShardPack.pack(donor, DOMAIN_A, SHARD), sealedRoot);
			coord.observePeerSealedFence(
					"peer-y",
                    List.of(new SealedShardInventoryEntry(DOMAIN_A, SHARD, shardFp)));
			assertFalse(coord.isSealedArtifactsFingerprintStale());
		} finally {
			coord.stop();
		}
	}
}
