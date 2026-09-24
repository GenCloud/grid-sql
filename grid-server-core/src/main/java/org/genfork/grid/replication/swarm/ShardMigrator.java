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
package org.genfork.grid.replication.swarm;

import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.OpLogSegment;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.log.OpLog;
import org.genfork.grid.replication.netty.NettyReplicationTransport;
import org.genfork.grid.replication.snapshot.sealed.SealedGridMapService;
import org.genfork.grid.replication.snapshot.sealed.SealedPackFingerprint;
import org.genfork.grid.replication.snapshot.sealed.SealedShardPack;
import org.genfork.grid.replication.tx.OpLogTxUnits;

import com.google.common.annotations.VisibleForTesting;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;

/**
 * Shard migrate: quiesce → catch-up push → cut over placement ownership to the target peer.
 * Honors affinity pins on {@link ShardPlacementMap}.
 * Cutover is fail-closed via OpLog: ownership transfers only after the OpLog range is shipped
 * (or the range is empty / already caught up).
 * <p>
 * CATCH_UP ships sealed shard artifacts once per catch-up entry unless the on-disk fingerprint
 * changes ({@link SealedPackShipGate} + {@link SealedPackFingerprint}).
 * <p>
 * TX-aware: ships only complete TX units; refuses cutover while an incomplete open unit remains
 * at the catch-up frontier or while {@code openTxGate} reports a local open TX on the stream.
 *
 * @author: GenCloud
 * @date: 2026/06
 * @since: 1.0
 */
public class ShardMigrator {
	private static final long EMPTY_FINGERPRINT = 0L;

	private final OpLog opLog;
	private final NettyReplicationTransport transport;
	private final ShardPlacementMap placementMap = new ShardPlacementMap();
	private final ShardMigrateCooldown cooldown;
	private final SealedPackShipGate sealedShipGate = new SealedPackShipGate();
	/** Optional gate: {@code (domain, shard) -> true} when local open TX blocks migrate/cutover. */
	private volatile BiPredicate<String, Integer> openTxGate;
	/** Optional sealed pack source for CATCH_UP ship (null = OpLog-only migrate). */
	private volatile SealedGridMapService sealedGridMapService;
	/** When set, migrateRange is a no-op unless the supplier returns true (writer-only). */
	private volatile BooleanSupplier writerEligibleGate;
	private final AtomicLong sealedShipCount = new AtomicLong();

	public ShardMigrator(OpLog opLog, NettyReplicationTransport transport) {
		this(opLog, transport, ShardMigrateCooldown.DEFAULT_CUTOVER_COOLDOWN_TICKS);
	}

	public ShardMigrator(OpLog opLog, NettyReplicationTransport transport, int cutoverCooldownTicks) {
		this.opLog = opLog;
		this.transport = transport;
		this.cooldown = new ShardMigrateCooldown(cutoverCooldownTicks);
	}

	public ShardPlacementMap getPlacementMap() {
		return placementMap;
	}

	public ShardMigrateCooldown getCooldown() {
		return cooldown;
	}

	public SealedPackShipGate getSealedShipGate() {
		return sealedShipGate;
	}

	public void setOpenTxGate(BiPredicate<String, Integer> openTxGate) {
		this.openTxGate = openTxGate;
	}

	/**
	 * Bind sealed pack source so CATCH_UP ships {@link SealedShardPack} alongside OpLog.
	 */
	public void setSealedGridMapService(SealedGridMapService sealedGridMapService) {
		this.sealedGridMapService = sealedGridMapService;
	}

	/**
	 * Writer-only migrate: when set, {@link #migrateRange} returns 0 unless supplier is true.
	 */
	public void setWriterEligibleGate(BooleanSupplier writerEligibleGate) {
		this.writerEligibleGate = writerEligibleGate;
	}

	/**
	 * Drive drain lifecycle toward {@code peerId}: QUIESCE → CATCH_UP (ship sealed + OpLog) → cutover.
	 */
	public int migrateRange(String peerId, String domainType, int shard, long fromSeqInclusive, int limit) {
		final BooleanSupplier writerGate = writerEligibleGate;
		if (writerGate != null && !writerGate.getAsBoolean()) {
			return 0;
		}

		final String affinity = placementMap.affinity(domainType, shard);
		final String target = affinity != null ? affinity : peerId;
		if (target == null) {
			return 0;
		}

		final BiPredicate<String, Integer> gate = openTxGate;
		if (gate != null && gate.test(domainType, shard)) {
			return 0;
		}

		if (cooldown.isCooling(domainType, shard)) {
			return 0;
		}

		ShardPlacementMap.DrainState state = placementMap.drainState(domainType, shard);
		if (state == ShardPlacementMap.DrainState.CUTOVER_DONE) {
			final String owner = placementMap.owner(domainType, shard);
			if (target.equals(owner) || target.equals(affinity)) {
				// Already cut over to target — do not re-drain / re-ship sealed.
				return 0;
			}
		}

		if (state == ShardPlacementMap.DrainState.NONE
				|| state == ShardPlacementMap.DrainState.CUTOVER_DONE) {
			placementMap.beginDrain(domainType, shard, target);
			state = ShardPlacementMap.DrainState.QUIESCE;
		}

		if (state == ShardPlacementMap.DrainState.QUIESCE) {
			placementMap.advanceToCatchUp(domainType, shard);
			sealedShipGate.onCatchUpEntered(domainType, shard);
			state = ShardPlacementMap.DrainState.CATCH_UP;
		}

		if (state != ShardPlacementMap.DrainState.CATCH_UP) {
			return 0;
		}

		shipSealedPack(target, domainType, shard);

		final List<ReplicationOp> raw = opLog.readFrom(domainType, shard, fromSeqInclusive, limit);
		final List<ReplicationOp> ops = OpLogTxUnits.trimToCompleteUnits(raw);
		if (ops.isEmpty()) {
			if (OpLogTxUnits.hasIncompleteOpenTx(raw)) {
				// Mid-unit at frontier — hold cutover until COMMIT/ABORT lands.
				return 0;
			}
			// Caught up with empty range — still cut over ownership.
			finishCutover(domainType, shard, target);
			return 0;
		}
		final long from = ops.getFirst().opSeq();
		final long to = ops.getLast().opSeq();
		final OpLogSegment segment = new OpLogSegment(
				domainType, shard, from, to, ops,
				OpLogCodec.segmentChecksum(ops)
		);
		transport.pushSegment(target, segment);
		if (OpLogTxUnits.hasIncompleteOpenTx(raw)) {
			// Shipped complete prefix only; remain in CATCH_UP for the open unit.
			return ops.size();
		}
		finishCutover(domainType, shard, target);
		return ops.size();
	}

	private void finishCutover(String domainType, int shard, String target) {
		placementMap.cutover(domainType, shard, target);
		sealedShipGate.onDrainFinished(domainType, shard);
		cooldown.onCutover(domainType, shard);
	}

	/**
	 * Push sealed artifacts for {@code domain#shard} when present; skip empty / unchanged /
	 * already-shipped this CATCH_UP. Fail-closed: pack I/O errors abort migrate before ownership cutover.
	 */
	private void shipSealedPack(String peerId, String domainType, int shard) {
		final SealedGridMapService sealed = sealedGridMapService;
		if (sealed == null || transport == null) {
			return;
		}
		try {
			final Path sealedRoot = sealed.sealedRootOrNull();
			if (sealedRoot == null) {
				return;
			}
			final List<Path> files = SealedShardPack.listShardFiles(sealedRoot, domainType, shard);
			if (files.isEmpty()) {
				sealedShipGate.onShipped(domainType, shard, EMPTY_FINGERPRINT);
				return;
			}
			final long fingerprint = SealedPackFingerprint.ofFiles(files);
			if (!sealedShipGate.shouldShip(domainType, shard, fingerprint)) {
				return;
			}
			final byte[] packed = SealedShardPack.packListed(files);
			if (!SealedShardPack.hasArtifactFiles(packed)) {
				sealedShipGate.onShipped(domainType, shard, fingerprint);
				return;
			}
			transport.pushSealedShardPack(peerId, domainType, shard, packed);
			sealedShipGate.onShipped(domainType, shard, fingerprint);
			sealedShipCount.incrementAndGet();
		} catch (IOException failure) {
			throw new IllegalStateException(
					"sealed pack ship failed " + domainType + "#" + shard, failure);
		}
	}

	@VisibleForTesting
	public long sealedShipCount() {
		return sealedShipCount.get();
	}
}
