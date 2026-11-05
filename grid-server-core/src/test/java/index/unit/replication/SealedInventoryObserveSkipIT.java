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
import org.genfork.grid.replication.metrics.ReplicationMetrics;
import org.genfork.grid.replication.snapshot.sealed.SealedGridMapWriter;
import org.genfork.grid.replication.snapshot.sealed.SealedPackFingerprint;
import org.genfork.grid.replication.snapshot.sealed.SealedShardPack;
import org.genfork.grid.replication.util.SealedInventoryIdentityUtil;
import org.genfork.grid.replication.util.SealedShardInventoryEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unchanged peer inventory skips disk ofShardIdentity; invalidate re-probes heal path.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
class SealedInventoryObserveSkipIT {

	private static final String DOMAIN = "inv_skip";
	private static final int SHARD = 0;
	private static final String PEER = "peer-skip";

	@TempDir
	Path tmp;

	@Test
	void secondIdenticalInventorySkipsDiskProbes() throws Exception {
		final Path donor = tmp.resolve("donor");
		Files.createDirectories(donor);
		SealedGridMapWriter.writeNodes(donor, DOMAIN, SHARD, 1L,
				List.of(new SealedGridMapWriter.Kv(new byte[]{1}, new byte[]{2})));
		final long shardFp = SealedPackFingerprint.ofShardIdentity(donor, DOMAIN, SHARD);
		final List<SealedShardInventoryEntry> inventory =
				List.of(new SealedShardInventoryEntry(DOMAIN, SHARD, shardFp));
		assertTrue(SealedInventoryIdentityUtil.identityHash(inventory) != 0L);

		final GridConfigurationProperties props = new GridConfigurationProperties();
		props.getDurability().setEnabled(true);
		props.getDurability().setHydrateMode("LAZY");
		props.getReplication().setEnabled(false);
		props.getReplication().getOpLog().setDataDir(tmp.resolve("skip-repl").toString());
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

			final long probesBefore = ReplicationMetrics.sealedInventoryDiskProbes();
			final long skipsBefore = ReplicationMetrics.sealedInventoryObserveSkipped();
			coord.observePeerSealedFence(PEER, inventory);
			final long probesAfterFirst = ReplicationMetrics.sealedInventoryDiskProbes();
			assertTrue(probesAfterFirst > probesBefore, "first observe must disk-probe");

			coord.observePeerSealedFence(PEER, inventory);
			assertEquals(probesAfterFirst, ReplicationMetrics.sealedInventoryDiskProbes(),
					"unchanged inventory must not disk-probe again");
			assertTrue(ReplicationMetrics.sealedInventoryObserveSkipped() > skipsBefore,
					"skip metric must increment");
		} finally {
			coord.stop();
		}
	}

	@Test
	void identicalInventoryBypassesSkipWhenLocalFpStillDiffers() throws Exception {
		final Path donor = tmp.resolve("donor-bypass");
		Files.createDirectories(donor);
		SealedGridMapWriter.writeNodes(donor, DOMAIN, SHARD, 3L,
				List.of(new SealedGridMapWriter.Kv(new byte[]{9}, new byte[]{8})));
		final long shardFp = SealedPackFingerprint.ofShardIdentity(donor, DOMAIN, SHARD);
		final List<SealedShardInventoryEntry> inventory =
				List.of(new SealedShardInventoryEntry(DOMAIN, SHARD, shardFp));

		final GridConfigurationProperties props = new GridConfigurationProperties();
		props.getDurability().setEnabled(true);
		props.getDurability().setHydrateMode("LAZY");
		props.getReplication().setEnabled(false);
		props.getReplication().getOpLog().setDataDir(tmp.resolve("bypass-repl").toString());
		props.getReplication().setClusterId("c1");
		props.getReplication().setNodeId("n1");

		final ReplicationCoordinator coord = new ReplicationCoordinator(props);
		coord.registerDomain(DOMAIN, shard -> shard == SHARD
				? new GridEntriesProcessor(SHARD, new GridScalableMap(), null, null)
				: null, true);
		try {
			// Local sealed empty → FP 0 ≠ peer FP; second identical HELLO must not skip forever.
			final long bypassBefore = ReplicationMetrics.sealedInventoryObserveSkipBypassed();
			final long probesBefore = ReplicationMetrics.sealedInventoryDiskProbes();
			coord.observePeerSealedFence(PEER, inventory);
			final long probesAfterFirst = ReplicationMetrics.sealedInventoryDiskProbes();
			assertTrue(probesAfterFirst > probesBefore);

			coord.observePeerSealedFence(PEER, inventory);
			assertTrue(ReplicationMetrics.sealedInventoryDiskProbes() > probesAfterFirst,
					"differential hole must re-probe despite identical inventory hash");
			assertTrue(ReplicationMetrics.sealedInventoryObserveSkipBypassed() > bypassBefore);
		} finally {
			coord.stop();
		}
	}

	@Test
	void invalidateClearsSkipSoHealReprobes() throws Exception {
		final Path donor = tmp.resolve("donor2");
		Files.createDirectories(donor);
		SealedGridMapWriter.writeNodes(donor, DOMAIN, SHARD, 2L,
				List.of(new SealedGridMapWriter.Kv(new byte[]{3}, new byte[]{4})));
		final long shardFp = SealedPackFingerprint.ofShardIdentity(donor, DOMAIN, SHARD);
		final List<SealedShardInventoryEntry> inventory =
				List.of(new SealedShardInventoryEntry(DOMAIN, SHARD, shardFp));

		final GridConfigurationProperties props = new GridConfigurationProperties();
		props.getDurability().setEnabled(true);
		props.getDurability().setHydrateMode("LAZY");
		props.getReplication().setEnabled(false);
		props.getReplication().getOpLog().setDataDir(tmp.resolve("inv-repl").toString());
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

			coord.observePeerSealedFence(PEER, inventory);
			coord.observePeerSealedFence(PEER, inventory);
			final long probesAfterSkip = ReplicationMetrics.sealedInventoryDiskProbes();

			coord.invalidateSealedStructuralHoleCache();
			coord.observePeerSealedFence(PEER, inventory);
			assertTrue(ReplicationMetrics.sealedInventoryDiskProbes() > probesAfterSkip,
					"after invalidate identical inventory must probe again");
		} finally {
			coord.stop();
		}
	}

	@Test
	void inventoryCacheReturnsSameUntilInvalidate() throws Exception {
		final Path donor = tmp.resolve("cache");
		Files.createDirectories(donor);
		SealedGridMapWriter.writeNodes(donor, DOMAIN, SHARD, 3L,
				List.of(new SealedGridMapWriter.Kv(new byte[]{5}, new byte[]{6})));

		final GridConfigurationProperties props = new GridConfigurationProperties();
		props.getDurability().setEnabled(true);
		props.getDurability().setHydrateMode("LAZY");
		props.getReplication().setEnabled(false);
		props.getReplication().getOpLog().setDataDir(tmp.resolve("cache-repl").toString());
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

			final List<SealedShardInventoryEntry> first = coord.sealedShardInventory();
			final List<SealedShardInventoryEntry> second = coord.sealedShardInventory();
			assertTrue(first == second, "cache must return same list instance until invalidate");
			coord.invalidateSealedStructuralHoleCache();
			final List<SealedShardInventoryEntry> third = coord.sealedShardInventory();
			assertTrue(first != third, "invalidate must rebuild inventory list");
			assertEquals(first.size(), third.size());
		} finally {
			coord.stop();
		}
	}
}
