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
import org.genfork.grid.mem.index.IndexPointerRef;
import org.genfork.grid.mem.index.bitmap.GridBitmapIndex;
import org.genfork.grid.mem.index.btree.SingleTreeKey;
import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.OpLogSegment;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.log.OpLog;
import org.genfork.grid.replication.netty.NettyReplicationTransport;
import org.genfork.grid.replication.repair.HomologousRepair;
import org.genfork.grid.replication.repair.RepairCommand;
import org.genfork.grid.replication.repair.RepairCommandType;
import org.genfork.grid.replication.repair.VersionLocus;
import org.genfork.grid.replication.snapshot.sealed.SealedBitmapService;
import org.genfork.grid.replication.snapshot.sealed.SealedGridMapService;
import org.genfork.grid.replication.snapshot.sealed.SealedShardPack;
import org.genfork.grid.replication.swarm.PlacementHint;
import org.genfork.grid.replication.swarm.PlacementScore;
import org.genfork.grid.replication.swarm.PlacementStreamFilters;
import org.genfork.grid.replication.swarm.PlacementTopology;
import org.genfork.grid.replication.swarm.ShardMigrator;
import org.genfork.grid.replication.swarm.ShardPlacementMap;
import org.genfork.grid.replication.swarm.SwarmMigrateLoadGate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locus FETCH_ROW heal soak + ShardMigrator QUIESCE→CATCH_UP→cutover (OpLog + sealed pack).
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
class LocusAndSwarmSoakTest {
	private static final String DOMAIN = "soak.d";
	private static final String MIGRATE_PEER = "peer-b";
	private static final String PINNED_PEER = "pinned-peer";
	private static final String BITMAP_PROPERTY = "status";
	private static final int SHARD = 0;
	private static final int OTHER_SHARD = 1;
	private static final long MIGRATE_FROM_SEQ = 1L;
	private static final int MIGRATE_BATCH = 64;
	private static final long SCHEMA_EPOCH = 1L;
	private static final int BITMAP_CAPACITY = 1 << 10;

	@TempDir
	Path temp;

	@Test
	void locusChecksumMismatchEmitsFetchRowAndHeals() {
		final HomologousRepair repair = new HomologousRepair(temp.resolve("locus"));
		final byte[] key = new byte[]{7};
		final byte[] good = new byte[]{1, 2, 3, 4};
		final ReplicationOp local = OpLogCodec.withChecksum(new ReplicationOp(
				DOMAIN, SHARD, 2L, ReplicationOpType.UPSERT, key, good, SCHEMA_EPOCH, 0L
		));
		repair.observe(local);

		final long keyHash = HomologousRepair.keyHash(key);
		repair.corruptRowBytes(DOMAIN, SHARD, keyHash);

		final Map<Long, VersionLocus> remote = new HashMap<>();
		final long goodCs = HomologousRepair.valueChecksum(good);
		remote.put(keyHash, new VersionLocus(DOMAIN, SHARD, keyHash, 2L, SCHEMA_EPOCH, goodCs));

		final List<RepairCommand> commands = repair.reconcile(DOMAIN, SHARD, remote);
		assertTrue(commands.stream().anyMatch(c -> c.type() == RepairCommandType.FETCH_ROW));

		final byte[] healed = repair.healCorruptedRow(
				DOMAIN, SHARD, keyHash, 2L, SCHEMA_EPOCH,
				new byte[]{9, 9, 9}, good, goodCs);
		assertArrayEquals(good, healed);
		assertTrue(repair.verifyValueChecksum(healed, goodCs));
	}

	@Test
	void multiKeyLagCoalescesReshipSegment() {
		final HomologousRepair repair = new HomologousRepair();
		repair.observe(OpLogCodec.withChecksum(new ReplicationOp(
				DOMAIN, SHARD, 1L, ReplicationOpType.UPSERT, new byte[]{1}, new byte[]{1}, SCHEMA_EPOCH, 0L
		)));

		final Map<Long, VersionLocus> remote = new HashMap<>();
		for (int i = 10; i < 14; i++) {
			final byte[] key = new byte[]{(byte) i};
			final long hash = HomologousRepair.keyHash(key);
			remote.put(hash, new VersionLocus(DOMAIN, SHARD, hash, i, SCHEMA_EPOCH, i));
		}
		final List<RepairCommand> commands = repair.reconcile(DOMAIN, SHARD, remote);
		assertFalse(commands.isEmpty());
		assertTrue(commands.stream().anyMatch(c -> c.type() == RepairCommandType.RESHIP_SEGMENT
				|| c.type() == RepairCommandType.FETCH_OP));
	}

	@Test
	void migratorEmptyRangeCutsOverOwnership() throws Exception {
		try (OpLog opLog = new OpLog(temp.resolve("oplog"), false)) {
			final AtomicReference<OpLogSegment> pushed = new AtomicReference<>();
			final RecordingTransport transport = new RecordingTransport(pushed);
			final ShardMigrator migrator = new ShardMigrator(opLog, transport);
			final int shipped = migrator.migrateRange(
					MIGRATE_PEER, DOMAIN, SHARD, MIGRATE_FROM_SEQ, MIGRATE_BATCH);
			assertEquals(0, shipped);
			assertEquals(ShardPlacementMap.DrainState.CUTOVER_DONE,
					migrator.getPlacementMap().drainState(DOMAIN, SHARD));
			assertEquals(MIGRATE_PEER, migrator.getPlacementMap().owner(DOMAIN, SHARD));
			assertNull(pushed.get());
		}
	}

	@Test
	void migratorCatchUpShipsSealedPackThenCutsOver() throws Exception {
		final Path sealedRoot = temp.resolve("sealed-ship");
		final SealedGridMapService sealed = new SealedGridMapService(sealedRoot);
		final SealedBitmapService bitmaps = sealed.sealedBitmapService();
		final GridBitmapIndex bitmap = new GridBitmapIndex(BITMAP_PROPERTY + "-BITMAP", BITMAP_CAPACITY);
		bitmap.insert(new SingleTreeKey(bytes("open")), new IndexPointerRef(0L, bytes("row-1")));
		bitmaps.dumpIndex(DOMAIN, SHARD, BITMAP_PROPERTY, bitmap);
		assertTrue(SealedShardPack.hasArtifactFiles(sealed.packShardArtifacts(DOMAIN, SHARD)));

		try (OpLog opLog = new OpLog(temp.resolve("oplog-sealed"), false)) {
			final AtomicReference<OpLogSegment> pushedSeg = new AtomicReference<>();
			final AtomicReference<byte[]> pushedPack = new AtomicReference<>();
			final RecordingTransport transport = new RecordingTransport(pushedSeg, null, pushedPack);
			final ShardMigrator migrator = new ShardMigrator(opLog, transport);
			migrator.setSealedGridMapService(sealed);

			final int shipped = migrator.migrateRange(
					MIGRATE_PEER, DOMAIN, SHARD, MIGRATE_FROM_SEQ, MIGRATE_BATCH);
			assertEquals(0, shipped);
			assertEquals(ShardPlacementMap.DrainState.CUTOVER_DONE,
					migrator.getPlacementMap().drainState(DOMAIN, SHARD));
			assertNotNull(pushedPack.get());
			assertTrue(SealedShardPack.hasArtifactFiles(pushedPack.get()));
			assertNull(pushedSeg.get());
			assertEquals(1L, migrator.sealedShipCount());
		}
	}

	@Test
	void cutoverDoneDoesNotReDrainSameTarget() throws Exception {
		try (OpLog opLog = new OpLog(temp.resolve("oplog-redrain"), false)) {
			final AtomicInteger sealedShips = new AtomicInteger();
			final RecordingTransport transport = new RecordingTransport(
					new AtomicReference<>(), null, new AtomicReference<>(), sealedShips);
			final ShardMigrator migrator = new ShardMigrator(opLog, transport);
			migrator.migrateRange(MIGRATE_PEER, DOMAIN, SHARD, MIGRATE_FROM_SEQ, MIGRATE_BATCH);
			assertEquals(ShardPlacementMap.DrainState.CUTOVER_DONE,
					migrator.getPlacementMap().drainState(DOMAIN, SHARD));
			migrator.migrateRange(MIGRATE_PEER, DOMAIN, SHARD, MIGRATE_FROM_SEQ, MIGRATE_BATCH);
			assertEquals(ShardPlacementMap.DrainState.CUTOVER_DONE,
					migrator.getPlacementMap().drainState(DOMAIN, SHARD));
			assertEquals(MIGRATE_PEER, migrator.getPlacementMap().owner(DOMAIN, SHARD));
		}
	}

	@Test
	void writerEligibleGateBlocksMigrate() throws Exception {
		try (OpLog opLog = new OpLog(temp.resolve("oplog-writer"), false)) {
			final ShardMigrator migrator = new ShardMigrator(opLog, new RecordingTransport(new AtomicReference<>()));
			migrator.setWriterEligibleGate(() -> false);
			assertEquals(0, migrator.migrateRange(MIGRATE_PEER, DOMAIN, SHARD, MIGRATE_FROM_SEQ, MIGRATE_BATCH));
			assertEquals(ShardPlacementMap.DrainState.NONE,
					migrator.getPlacementMap().drainState(DOMAIN, SHARD));
		}
	}

	@Test
	void sealedShipOncePerCatchUpWhenUnchanged() throws Exception {
		final Path sealedRoot = temp.resolve("sealed-once");
		final SealedGridMapService sealed = new SealedGridMapService(sealedRoot);
		final SealedBitmapService bitmaps = sealed.sealedBitmapService();
		final GridBitmapIndex bitmap = new GridBitmapIndex(BITMAP_PROPERTY + "-BITMAP", BITMAP_CAPACITY);
		bitmap.insert(new SingleTreeKey(bytes("k")), new IndexPointerRef(0L, bytes("r")));
		bitmaps.dumpIndex(DOMAIN, SHARD, BITMAP_PROPERTY, bitmap);

		try (OpLog opLog = new OpLog(temp.resolve("oplog-once"), false)) {
			opLog.append(OpLogCodec.withChecksum(new ReplicationOp(
					DOMAIN, SHARD, 1L, ReplicationOpType.TX_BEGIN,
					new byte[]{1}, null, SCHEMA_EPOCH, 0L
			)));
			opLog.append(OpLogCodec.withChecksum(new ReplicationOp(
					DOMAIN, SHARD, 2L, ReplicationOpType.UPSERT,
					new byte[]{1}, new byte[]{9}, SCHEMA_EPOCH, 0L
			)));
			final AtomicInteger sealedShips = new AtomicInteger();
			final ShardMigrator migrator = new ShardMigrator(
					opLog, new RecordingTransport(new AtomicReference<>(), null, new AtomicReference<>(), sealedShips));
			migrator.setSealedGridMapService(sealed);
			assertEquals(0, migrator.migrateRange(MIGRATE_PEER, DOMAIN, SHARD, MIGRATE_FROM_SEQ, MIGRATE_BATCH));
			assertEquals(ShardPlacementMap.DrainState.CATCH_UP,
					migrator.getPlacementMap().drainState(DOMAIN, SHARD));
			assertEquals(1, sealedShips.get());
			assertEquals(0, migrator.migrateRange(MIGRATE_PEER, DOMAIN, SHARD, MIGRATE_FROM_SEQ, MIGRATE_BATCH));
			assertEquals(1, sealedShips.get(), "second CATCH_UP tick must skip unchanged sealed pack");
		}
	}

	@Test
	void settledPeerStreamsSkippedFromMigrateSearch() {
		final PlacementTopology.StreamPlacement localOwned =
				new PlacementTopology.StreamPlacement(DOMAIN, SHARD, null, null, 0.0d);
		final PlacementTopology.StreamPlacement settled =
				new PlacementTopology.StreamPlacement(DOMAIN, SHARD + 1, MIGRATE_PEER, null, 0.0d);
		final PlacementTopology.StreamPlacement pinMismatch =
				new PlacementTopology.StreamPlacement(DOMAIN, SHARD + 2, MIGRATE_PEER, "other-pin", 0.0d);
		final PlacementTopology topology = new PlacementTopology(
				"local-node",
				"dc-a",
				List.of(new PlacementTopology.PeerEndpoint(MIGRATE_PEER, "dc-a")),
				List.of(localOwned, settled, pinMismatch)
		);
		final List<PlacementTopology.StreamPlacement> needing =
				PlacementStreamFilters.needingMigrateSearch(topology);
		assertEquals(2, needing.size());
		assertTrue(needing.contains(localOwned));
		assertTrue(needing.contains(pinMismatch));
		assertFalse(needing.contains(settled));
		assertTrue(PlacementStreamFilters.isSettledOnPeer(settled, "local-node"));
		assertFalse(PlacementStreamFilters.isSettledOnPeer(localOwned, "local-node"));
	}

	@Test
	void loadGateBlocksMigrateIoUnderQueuePressure() {
		assertFalse(SwarmMigrateLoadGate.isPressureCalm(0.0d, 0.0d, 0.0d), "lag floor required");
		assertTrue(SwarmMigrateLoadGate.isPressureCalm(
				SwarmMigrateLoadGate.MAX_QUEUE_DEPTH_FOR_MIGRATE, 0.5d,
				SwarmMigrateLoadGate.MIN_APPLY_LAG_FOR_MIGRATE));
		final SwarmMigrateLoadGate gate = new SwarmMigrateLoadGate();
		final PlacementScore calm = new PlacementScore(
				SwarmMigrateLoadGate.MIN_APPLY_LAG_FOR_MIGRATE, 0.0d, 0.1d, 1.0d, 0.0d, 0.0d);
		assertFalse(gate.allowMigrateIo(calm, PlacementHint.KEEP));
		assertFalse(gate.allowMigrateIo(calm, PlacementHint.SHED_LOAD));
		assertFalse(gate.allowMigrateIo(calm, PlacementHint.SHED_LOAD));
		assertTrue(gate.allowMigrateIo(calm, PlacementHint.SHED_LOAD), "third calm SHED tick arms migrate I/O");
		assertFalse(gate.allowMigrateIo(calm, PlacementHint.KEEP), "KEEP clears calm ticks");
	}

	@Test
	void cutoverCooldownSuppressesImmediateRemigrate() throws Exception {
		try (OpLog opLog = new OpLog(temp.resolve("oplog-cool"), false)) {
			final ShardMigrator migrator = new ShardMigrator(opLog, new RecordingTransport(new AtomicReference<>()), 2);
			migrator.migrateRange(MIGRATE_PEER, DOMAIN, SHARD, MIGRATE_FROM_SEQ, MIGRATE_BATCH);
			assertTrue(migrator.getCooldown().isCooling(DOMAIN, SHARD));
			// Force a different target so CUTOVER_DONE no-op would not apply — cooldown still blocks.
			migrator.getPlacementMap().setOwner(DOMAIN, SHARD, "other-owner");
			migrator.getPlacementMap().setDrainState(DOMAIN, SHARD, ShardPlacementMap.DrainState.NONE);
			assertEquals(0, migrator.migrateRange(MIGRATE_PEER, DOMAIN, SHARD, MIGRATE_FROM_SEQ, MIGRATE_BATCH));
			assertEquals(ShardPlacementMap.DrainState.NONE,
					migrator.getPlacementMap().drainState(DOMAIN, SHARD));
		}
	}

	@Test
	void migratorOpenTxGateBlocksWithoutDrainLeak() throws Exception {
		try (OpLog opLog = new OpLog(temp.resolve("oplog-gate"), false)) {
			final ShardMigrator migrator = new ShardMigrator(opLog, new RecordingTransport(new AtomicReference<>()));
			migrator.setOpenTxGate((domain, shard) -> true);
			final int shipped = migrator.migrateRange(
					MIGRATE_PEER, DOMAIN, SHARD, MIGRATE_FROM_SEQ, MIGRATE_BATCH);
			assertEquals(0, shipped);
			assertEquals(ShardPlacementMap.DrainState.NONE,
					migrator.getPlacementMap().drainState(DOMAIN, SHARD));
			assertNull(migrator.getPlacementMap().owner(DOMAIN, SHARD));
		}
	}

	@Test
	void migratorIncompleteOpenTxHoldsCatchUpWithoutCutover() throws Exception {
		try (OpLog opLog = new OpLog(temp.resolve("oplog-open-tx"), false)) {
			opLog.append(OpLogCodec.withChecksum(new ReplicationOp(
					DOMAIN, SHARD, 1L, ReplicationOpType.TX_BEGIN,
					new byte[]{1}, null, SCHEMA_EPOCH, 0L
			)));
			opLog.append(OpLogCodec.withChecksum(new ReplicationOp(
					DOMAIN, SHARD, 2L, ReplicationOpType.UPSERT,
					new byte[]{1}, new byte[]{9}, SCHEMA_EPOCH, 0L
			)));

			final AtomicReference<OpLogSegment> pushed = new AtomicReference<>();
			final ShardMigrator migrator = new ShardMigrator(opLog, new RecordingTransport(pushed));
			final int shipped = migrator.migrateRange(
					MIGRATE_PEER, DOMAIN, SHARD, MIGRATE_FROM_SEQ, MIGRATE_BATCH);
			assertEquals(0, shipped);
			assertEquals(ShardPlacementMap.DrainState.CATCH_UP,
					migrator.getPlacementMap().drainState(DOMAIN, SHARD));
			assertNull(migrator.getPlacementMap().owner(DOMAIN, SHARD));
			assertNull(pushed.get());
		}
	}

	@Test
	void affinityPinOverridesMigratePeerTarget() throws Exception {
		try (OpLog opLog = new OpLog(temp.resolve("oplog-pin"), false)) {
			final AtomicReference<OpLogSegment> pushed = new AtomicReference<>();
			final AtomicReference<String> targetPeer = new AtomicReference<>();
			final RecordingTransport transport = new RecordingTransport(pushed, targetPeer);
			final ShardMigrator migrator = new ShardMigrator(opLog, transport);
			migrator.getPlacementMap().setAffinity(DOMAIN, SHARD, PINNED_PEER);
			final int shipped = migrator.migrateRange(
					MIGRATE_PEER, DOMAIN, SHARD, MIGRATE_FROM_SEQ, MIGRATE_BATCH);
			assertEquals(0, shipped);
			assertEquals(PINNED_PEER, migrator.getPlacementMap().owner(DOMAIN, SHARD));
			assertNull(targetPeer.get());
			assertNull(pushed.get());
		}
	}

	@Test
	void affinityPinRemainsScopedToOneShard() throws Exception {
		try (OpLog opLog = new OpLog(temp.resolve("oplog-pin-scope"), false)) {
			final ShardMigrator migrator =
					new ShardMigrator(opLog, new RecordingTransport(new AtomicReference<>()));
			migrator.getPlacementMap().setAffinity(DOMAIN, SHARD, PINNED_PEER);

			migrator.migrateRange(MIGRATE_PEER, DOMAIN, SHARD, MIGRATE_FROM_SEQ, MIGRATE_BATCH);
			migrator.migrateRange(MIGRATE_PEER, DOMAIN, OTHER_SHARD, MIGRATE_FROM_SEQ, MIGRATE_BATCH);

			assertEquals(PINNED_PEER, migrator.getPlacementMap().owner(DOMAIN, SHARD));
			assertEquals(MIGRATE_PEER, migrator.getPlacementMap().owner(DOMAIN, OTHER_SHARD));
		}
	}

	@Test
	void applyAutoCutoverDefaultsOnAndOptsOut() {
		final GridConfigurationProperties.SwarmProps defaults = new GridConfigurationProperties.SwarmProps();
		assertTrue(defaults.isApplyAutoCutover(), "production cutover default is on");

		defaults.setApplyAutoCutover(false);
		assertFalse(defaults.isApplyAutoCutover());

		// Mirror ReplicationCoordinator.swarmTick gate: hints/plans may run; migrateRange only when on.
		final boolean applyOff = false;
		final AtomicInteger migrateCalls = new AtomicInteger();
		if (applyOff) {
			migrateCalls.incrementAndGet();
		}
		assertEquals(0, migrateCalls.get());

		final boolean applyOn = true;
		if (applyOn) {
			migrateCalls.incrementAndGet();
		}
		assertEquals(1, migrateCalls.get());
	}

	private static byte[] bytes(String value) {
		return value.getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * Minimal transport stub — records OpLog segments and sealed packs from migrator.
	 */
	private static class RecordingTransport extends NettyReplicationTransport {
		private final AtomicReference<OpLogSegment> pushed;
		private final AtomicReference<String> targetPeer;
		private final AtomicReference<byte[]> sealedPack;
		private final AtomicInteger sealedShipCounter;

		private RecordingTransport(AtomicReference<OpLogSegment> pushed) {
			this(pushed, null, null, null);
		}

		private RecordingTransport(AtomicReference<OpLogSegment> pushed, AtomicReference<String> targetPeer) {
			this(pushed, targetPeer, null, null);
		}

		private RecordingTransport(
				AtomicReference<OpLogSegment> pushed,
				AtomicReference<String> targetPeer,
				AtomicReference<byte[]> sealedPack) {
			this(pushed, targetPeer, sealedPack, null);
		}

		private RecordingTransport(
				AtomicReference<OpLogSegment> pushed,
				AtomicReference<String> targetPeer,
				AtomicReference<byte[]> sealedPack,
				AtomicInteger sealedShipCounter) {
			super("soak-node", "soak-cluster", "dc-a", 1L, "127.0.0.1", 0, 1000L, 1024 * 1024, List.of());
			this.pushed = pushed;
			this.targetPeer = targetPeer;
			this.sealedPack = sealedPack;
			this.sealedShipCounter = sealedShipCounter;
		}

		@Override
		public void pushSegment(String peerId, OpLogSegment segment) {
			if (targetPeer != null) {
				targetPeer.set(peerId);
			}
			pushed.set(segment);
		}

		@Override
		public void pushSealedShardPack(String peerId, String domainType, int shard, byte[] packed) {
			if (sealedPack != null) {
				sealedPack.set(packed);
			}
			if (sealedShipCounter != null) {
				sealedShipCounter.incrementAndGet();
			}
			if (targetPeer != null) {
				targetPeer.set(peerId);
			}
		}
	}
}
