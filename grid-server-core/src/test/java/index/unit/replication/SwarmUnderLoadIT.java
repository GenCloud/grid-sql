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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.genfork.grid.replication.OrchidNotSyncedException;
import org.genfork.grid.replication.ReplicaAccessGate;
import org.genfork.grid.replication.codec.OpLogSegment;
import org.genfork.grid.replication.log.OpLog;
import org.genfork.grid.replication.netty.NettyReplicationTransport;
import org.genfork.grid.replication.swarm.PlacementHint;
import org.genfork.grid.replication.swarm.PlacementScore;
import org.genfork.grid.replication.swarm.ShardMigrator;
import org.genfork.grid.replication.swarm.ShardPlacementMap;
import org.genfork.grid.replication.swarm.SwarmMigrateLoadGate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Swarm under load: LoadGate lag-suppress, QUIESCE write fence, cutover without orphan drain.
 *
 * @author: GenCloud
 * @date: 2026/06
 * @since: 1.0
 */
class SwarmUnderLoadIT {
	private static final String DOMAIN = "swarm.ul";
	private static final String PEER = "peer-b";
	private static final int SHARD = 0;
	private static final long FROM_SEQ = 1L;
	private static final int BATCH = 64;

	@TempDir
	Path temp;

	@Test
	void loadGateSuppressesHighLagAndArmsWhenCalm() {
		final SwarmMigrateLoadGate gate = new SwarmMigrateLoadGate();
		final PlacementScore highLag = new PlacementScore(
				SwarmMigrateLoadGate.MAX_APPLY_LAG_FOR_MIGRATE, 0.0d, 0.1d, 1.0d, 0.0d, 0.0d);
		final PlacementScore pressure = new PlacementScore(
				0.0d, SwarmMigrateLoadGate.MAX_QUEUE_DEPTH_FOR_MIGRATE + 1.0d, 0.1d, 1.0d, 0.0d, 0.0d);
		final PlacementScore calm = new PlacementScore(0.0d, 0.0d, 0.1d, 1.0d, 0.0d, 0.0d);

		assertFalse(gate.allowMigrateIo(highLag, PlacementHint.SHED_LOAD));
		assertTrue(gate.migrateIoSuppressedLagCount() >= 1L);
		assertFalse(gate.allowMigrateIo(pressure, PlacementHint.SHED_LOAD));
		assertTrue(gate.migrateIoSuppressedPressureCount() >= 1L);

		assertFalse(gate.allowMigrateIo(calm, PlacementHint.SHED_LOAD));
		assertFalse(gate.allowMigrateIo(calm, PlacementHint.SHED_LOAD));
		assertTrue(gate.allowMigrateIo(calm, PlacementHint.SHED_LOAD));
		assertTrue(gate.migrateIoAllowedCount() >= 1L);
	}

	@Test
	void quiesceFencesWritesUntilCutover() throws Exception {
		try (OpLog opLog = new OpLog(temp.resolve("oplog"), false)) {
			final ShardMigrator migrator = new ShardMigrator(opLog, new RecordingTransport());
			assertEquals(0, migrator.migrateRange(PEER, DOMAIN, SHARD, FROM_SEQ, BATCH));
			assertEquals(ShardPlacementMap.DrainState.QUIESCE,
					migrator.getPlacementMap().drainState(DOMAIN, SHARD));
			assertTrue(migrator.getPlacementMap().isDraining(DOMAIN, SHARD));
			assertThrows(OrchidNotSyncedException.class,
					() -> ReplicaAccessGate.ensureShardWritableState(true));

			assertEquals(0, migrator.migrateRange(PEER, DOMAIN, SHARD, FROM_SEQ, BATCH));
			assertEquals(ShardPlacementMap.DrainState.CUTOVER_DONE,
					migrator.getPlacementMap().drainState(DOMAIN, SHARD));
			assertFalse(migrator.getPlacementMap().isDraining(DOMAIN, SHARD));
			assertEquals(PEER, migrator.getPlacementMap().owner(DOMAIN, SHARD));
			ReplicaAccessGate.ensureShardWritableState(false);
		}
	}

	/**
	 * Minimal transport stub for migrator cutover path.
	 */
	private static final class RecordingTransport extends NettyReplicationTransport {
		private RecordingTransport() {
			super("ul-node", "ul-cluster", "dc-a", 1L, "127.0.0.1", 0, 1000L, 1024 * 1024, List.of());
		}

		@Override
		public void pushSegment(String peerId, OpLogSegment segment) {
			// no-op
		}

		@Override
		public void pushSealedShardPack(String peerId, String domainType, int shard, byte[] packed) {
			// no-op
		}
	}
}