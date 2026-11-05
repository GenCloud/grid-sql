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
import org.genfork.grid.replication.util.SealedShardInventoryEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression matrix for sealed hole heal — fix one path without breaking siblings.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
class SealedHoleRegressionMatrixIT {

	private static final String DOMAIN = "reg_matrix";
	private static final int SHARD = 0;

	@TempDir
	Path tmp;

	@Test
	void purgeOrphansRemovesStaleSiblingNotInPack() throws Exception {
		final Path root = tmp.resolve("purge");
		Files.createDirectories(root);
		SealedGridMapWriter.writeNodes(root, DOMAIN, SHARD, 3L,
				List.of(new SealedGridMapWriter.Kv(new byte[]{1}, new byte[]{2})));
		final byte[] packed = SealedShardPack.pack(root, DOMAIN, SHARD);
		final String hex = SealedShardPack.domainHex(DOMAIN);
		final Path orphan = root.resolve(hex + "_" + SHARD + "_n15.gmap");
		Files.write(orphan, new byte[]{1, 2, 3, 4});
		assertTrue(Files.isRegularFile(orphan));

		final Set<String> keep = SealedShardPack.packedEntryNames(packed);
		final int deleted = SealedShardPack.purgeOrphansNotIn(root, DOMAIN, SHARD, keep);
		assertTrue(deleted >= 1);
		assertFalse(Files.exists(orphan));
		assertTrue(SealedShardPack.isCompleteShardArtifacts(packed));
	}

	@Test
	void rootFingerprintMismatchAloneIsNotIncomplete() throws Exception {
		final Path donor = tmp.resolve("donor");
		Files.createDirectories(donor);
		SealedGridMapWriter.writeNodes(donor, DOMAIN, SHARD, 5L,
				List.of(new SealedGridMapWriter.Kv(new byte[]{3, 3}, new byte[]{7})));
		final long donorFp = SealedPackFingerprint.ofShardIdentity(donor, DOMAIN, SHARD);
		assertTrue(donorFp != 0L);

		final GridConfigurationProperties props = new GridConfigurationProperties();
		props.getDurability().setEnabled(true);
		props.getDurability().setHydrateMode("LAZY");
		props.getReplication().setEnabled(false);
		props.getReplication().getOpLog().setDataDir(tmp.resolve("fence-repl").toString());
		props.getReplication().setClusterId("c1");
		props.getReplication().setNodeId("n1");

		final ReplicationCoordinator coord = new ReplicationCoordinator(props);
		coord.registerDomain(DOMAIN, shard -> shard == SHARD
				? new GridEntriesProcessor(SHARD, new GridScalableMap(), null, null)
				: null, true);
		try {
			final Path sealedRoot = coord.getSealedGridMapService().sealedRootOrNull();
			Files.createDirectories(sealedRoot);
			SealedShardPack.unpack(SealedShardPack.pack(donor, DOMAIN, SHARD), sealedRoot);
			assertFalse(coord.isSealedArtifactsFingerprintStale());
			assertFalse(coord.isReplicaDataIncomplete());

			// Aggregate root ≠ alone must not fence (storm root cause).
			coord.observePeerSealedFence("peer-b");
			assertFalse(coord.isSealedArtifactsFingerprintStale());
		} finally {
			coord.stop();
		}
	}

	@Test
	void perShardInventoryMismatchDoesNotFenceAdmit() throws Exception {
		final Path donor = tmp.resolve("donor-inv");
		Files.createDirectories(donor);
		SealedGridMapWriter.writeNodes(donor, DOMAIN, SHARD, 5L,
				List.of(new SealedGridMapWriter.Kv(new byte[]{3, 3}, new byte[]{7})));
		final long donorFp = SealedPackFingerprint.ofShardIdentity(donor, DOMAIN, SHARD);

		final GridConfigurationProperties props = new GridConfigurationProperties();
		props.getDurability().setEnabled(true);
		props.getDurability().setHydrateMode("LAZY");
		props.getReplication().setEnabled(false);
		props.getReplication().getOpLog().setDataDir(tmp.resolve("fence-inv").toString());
		props.getReplication().setClusterId("c1");
		props.getReplication().setNodeId("n1");

		final ReplicationCoordinator coord = new ReplicationCoordinator(props);
		coord.registerDomain(DOMAIN, shard -> shard == SHARD
				? new GridEntriesProcessor(SHARD, new GridScalableMap(), null, null)
				: null, true);
		try {
			final Path sealedRoot = coord.getSealedGridMapService().sealedRootOrNull();
			Files.createDirectories(sealedRoot);
			SealedShardPack.unpack(SealedShardPack.pack(donor, DOMAIN, SHARD), sealedRoot);
			final long localFp = SealedPackFingerprint.ofShardIdentity(sealedRoot, DOMAIN, SHARD);

			// Inventory mismatch heals differentially; healthy local files must not deny admit.
			coord.observePeerSealedFence(
					"peer-b",
                    List.of(new SealedShardInventoryEntry(DOMAIN, SHARD, localFp ^ 0x22L)));
			assertFalse(coord.isSealedArtifactsFingerprintStale());
		} finally {
			coord.stop();
		}
	}

	@Test
	void packIdentityMatchesOnDiskAfterUnpack() throws Exception {
		final Path src = tmp.resolve("src");
		final Path dst = tmp.resolve("dst");
		Files.createDirectories(src);
		SealedGridMapWriter.writeNodes(src, DOMAIN, SHARD, 9L,
				List.of(new SealedGridMapWriter.Kv(new byte[]{8, 8}, new byte[]{1})));
		final byte[] packed = SealedShardPack.pack(src, DOMAIN, SHARD);
		final long packFp = SealedShardPack.identityFingerprintOfPacked(packed);
		SealedShardPack.unpack(packed, dst);
		assertEquals(packFp, SealedPackFingerprint.ofShardIdentity(dst, DOMAIN, SHARD));
	}

	@Test
	void sealedRootIdentitySeesUndersizedGmap() throws Exception {
		final Path good = tmp.resolve("good-root");
		final Path bad = tmp.resolve("bad-root");
		Files.createDirectories(good);
		SealedGridMapWriter.writeNodes(good, DOMAIN, SHARD, 9L,
				List.of(new SealedGridMapWriter.Kv(new byte[]{1, 2}, new byte[]{3})));
		final byte[] packed = SealedShardPack.pack(good, DOMAIN, SHARD);
		SealedShardPack.unpack(packed, bad);
		final Path gmap = SealedShardPack.listShardFiles(bad, DOMAIN, SHARD).stream()
				.filter(p -> p.getFileName().toString().endsWith(".gmap"))
				.findFirst()
				.orElseThrow();
		final byte[] bytes = Files.readAllBytes(gmap);
		Files.write(gmap, java.util.Arrays.copyOf(bytes, Math.max(32, bytes.length - 40)));
		assertTrue(SealedPackFingerprint.ofSealedRootIdentity(good)
				!= SealedPackFingerprint.ofSealedRootIdentity(bad));
	}
}
