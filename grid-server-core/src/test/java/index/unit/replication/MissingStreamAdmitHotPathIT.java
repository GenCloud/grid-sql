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
import org.genfork.grid.replication.util.SealedShardInventoryEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Admit hot path must not directory-walk sealed identity on every SELECT.
 * <p>
 * Product API only — disk probes may rise during inventory rebuild / HELLO observe,
 * not during repeated {@code isClientApplyLagStale} after inventory is ready.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
class MissingStreamAdmitHotPathIT {

	private static final String DOMAIN = "admit_hot";
	private static final int SHARD = 0;
	private static final int SELECT_STORM = 200;
	private static final String PEER = "peer-n1";

	@TempDir
	Path tmp;

	@Test
	void repeatedAdmitDoesNotGrowDiskProbesAfterInventoryReady() throws Exception {
		final Path donor = tmp.resolve("donor");
		Files.createDirectories(donor);
		SealedGridMapWriter.writeNodes(donor, DOMAIN, SHARD, 7L,
				List.of(new SealedGridMapWriter.Kv(new byte[]{1}, new byte[]{2})));
		final long shardFp = SealedPackFingerprint.ofShardIdentity(donor, DOMAIN, SHARD);

		final GridConfigurationProperties props = new GridConfigurationProperties();
		props.getDurability().setEnabled(true);
		props.getDurability().setHydrateMode("LAZY");
		props.getDurability().setAdaptiveDiskFirst(false);
		props.getReplication().setEnabled(false);
		props.getReplication().getOpLog().setDataDir(tmp.resolve("repl").toString());
		props.getReplication().setClusterId("c-admit-hot");
		props.getReplication().setNodeId("n2");

		final ReplicationCoordinator coord = new ReplicationCoordinator(props);
		coord.registerDomain(DOMAIN, shard -> shard == SHARD
				? new GridEntriesProcessor(SHARD, new GridScalableMap(), null, null)
				: null, true);
		try {
			final Path sealedRoot = coord.getSealedGridMapService().sealedRootOrNull();
			Files.createDirectories(sealedRoot);
			SealedShardPack.unpack(SealedShardPack.pack(donor, DOMAIN, SHARD), sealedRoot);

			coord.sealedShardInventory();
			assertTrue(coord.isSealedInventoryAdmitReady());

			coord.observePeerSealedFence(
					PEER,
                    List.of(new SealedShardInventoryEntry(DOMAIN, SHARD, shardFp)));

			final long probesBefore = ReplicationMetrics.sealedInventoryDiskProbes();
			for (int i = 0; i < SELECT_STORM; i++) {
				coord.isClientApplyLagStale();
				coord.isReplicaDataIncomplete();
			}
			final long probesAfter = ReplicationMetrics.sealedInventoryDiskProbes();
			assertEquals(probesBefore, probesAfter,
					"admit storm must not ofShardIdentity-walk sealed root");
			assertTrue(coord.isSealedInventoryAdmitReady());
		} finally {
			coord.stop();
		}
	}

	@Test
	void invalidateThenRebuildClearsFailClosed() throws Exception {
		final Path donor = tmp.resolve("donor2");
		Files.createDirectories(donor);
		SealedGridMapWriter.writeNodes(donor, DOMAIN, SHARD, 3L,
				List.of(new SealedGridMapWriter.Kv(new byte[]{3}, new byte[]{4})));

		final GridConfigurationProperties props = new GridConfigurationProperties();
		props.getDurability().setEnabled(true);
		props.getDurability().setHydrateMode("LAZY");
		props.getReplication().setEnabled(false);
		props.getReplication().getOpLog().setDataDir(tmp.resolve("repl2").toString());
		props.getReplication().setClusterId("c-admit-inv");
		props.getReplication().setNodeId("n2");

		final ReplicationCoordinator coord = new ReplicationCoordinator(props);
		coord.registerDomain(DOMAIN, shard -> shard == SHARD
				? new GridEntriesProcessor(SHARD, new GridScalableMap(), null, null)
				: null, true);
		try {
			final Path sealedRoot = coord.getSealedGridMapService().sealedRootOrNull();
			Files.createDirectories(sealedRoot);
			SealedShardPack.unpack(SealedShardPack.pack(donor, DOMAIN, SHARD), sealedRoot);
			coord.sealedShardInventory();
			assertTrue(coord.isSealedInventoryAdmitReady());

			coord.invalidateSealedStructuralHoleCache();
			// nettyTransport null → sync rebuild from enqueue
			assertTrue(coord.isSealedInventoryAdmitReady(),
					"eager rebuild must restore inventoryReady without waiting HELLO");
		} finally {
			coord.stop();
		}
	}
}
