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
package org.genfork.grid.replication;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.genfork.grid.mem.adaptive.AdaptiveDiskFirstController;
import org.genfork.grid.mem.stage.WorkingSetBudget;
import org.genfork.grid.nio.EncodeBuffers;
import org.genfork.grid.replication.apply.ReplicaApplier;
import org.genfork.grid.replication.codec.OpLogSegment;
import org.genfork.grid.replication.log.OpLog;
import org.genfork.grid.replication.metrics.ReplicationMetrics;
import org.genfork.grid.replication.netty.ReplicationRpcCodec.SealedShardPackMsg;
import org.genfork.grid.replication.repair.HomologousRepair;
import org.genfork.grid.replication.snapshot.IndexCheckpointService;
import org.genfork.grid.replication.snapshot.SnapshotService;
import org.genfork.grid.replication.snapshot.sealed.SealedGridMapReader;
import org.genfork.grid.replication.snapshot.sealed.SealedGridMapService;
import org.genfork.grid.replication.util.OpLogStreamKeyUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sealed hydrate, working-set budgets, periodic dump, and snapshot install helpers.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public final class SealedHydrateService {
	private static final Logger log = LoggerFactory.getLogger(SealedHydrateService.class);

	/**
	 * Sealed checkpoint cadence. Kept above short Capacity QG windows (45 s) so a dump
	 * does not collide with living upsert fsync on the same volume.
	 */
	public static final long SEALED_DUMP_INTERVAL_MS = 180_000L;
	private static final long SEALED_DUMP_FSYNC_PRESSURE_P50_NS = 1_500_000L;
	private static final long SEALED_DUMP_FSYNC_MIN_SAMPLES = 16L;
	private static final int INDEX_KEY_CATALOG_MAX_SHARD = 64;
	private static final byte[] KEYS_MAGIC = new byte[]{'K', 'E', 'Y', 'S'};
	private static final String HYDRATE_MODE_FULL = "FULL";
	private static final String HYDRATE_MODE_LAZY = "LAZY";
	/**
	 * Fail-closed: OpLog tail after sealed watermark without any {@code .sbpt} must not exceed this
	 * (forces seal / reject unbounded map-only hydrate).
	 */
	static final long MAX_OPLOG_DELTA_WITHOUT_SBPT = 100_000L;

	private final boolean enabled;
	private final String hydrateMode;
	private final int workingSetMaxEntries;
	private final AdaptiveDiskFirstController adaptiveDiskFirstController;
	private final ReplicationNodeState nodeState;
	private final OpLog opLog;
	private final SnapshotService snapshotService;
	private final IndexCheckpointService indexCheckpointService;
	private final SealedGridMapService sealedGridMapService;
	private final HomologousRepair homologousRepair;
	private final Map<String, ReplicaApplier> appliers;
	private final Set<String> hydratedShards = ConcurrentHashMap.newKeySet();

	public SealedHydrateService(
			boolean enabled,
			String hydrateMode,
			int workingSetMaxEntries,
			AdaptiveDiskFirstController adaptiveDiskFirstController,
			ReplicationNodeState nodeState,
			OpLog opLog,
			SnapshotService snapshotService,
			IndexCheckpointService indexCheckpointService,
			SealedGridMapService sealedGridMapService,
			HomologousRepair homologousRepair,
			Map<String, ReplicaApplier> appliers
	) {
		this.enabled = enabled;
		this.hydrateMode = hydrateMode;
		this.workingSetMaxEntries = workingSetMaxEntries;
		this.adaptiveDiskFirstController = adaptiveDiskFirstController;
		this.nodeState = nodeState;
		this.opLog = opLog;
		this.snapshotService = snapshotService;
		this.indexCheckpointService = indexCheckpointService;
		this.sealedGridMapService = sealedGridMapService;
		this.homologousRepair = homologousRepair;
		this.appliers = appliers;
	}

	public Map<Integer, Long> hydrateDomainFull(String domainType, ReplicaApplier applier) {
		final Map<Integer, Long> sealedWm = sealedGridMapService.loadAllIntoMap(domainType);
		for (Map.Entry<Integer, Long> e : sealedWm.entrySet()) {
			if (e.getValue() > 0) {
				nodeState.resetApplied(domainType, e.getKey());
				nodeState.advanceApplied(domainType, e.getKey(), e.getValue());
			}
		}
		final int hydrated = snapshotService.hydrateDomain(domainType, applier, homologousRepair);
		if (hydrated > 0 || !sealedWm.isEmpty()) {
			log.info("Hydrated domain={} sealedShards={} oplogOps={}",
					domainType, sealedWm.size(), hydrated);
		}
		for (Integer shard : snapshotService.listShards(domainType)) {
			hydratedShards.add(OpLogStreamKeyUtil.format(domainType, shard));
		}
		for (Integer shard : sealedWm.keySet()) {
			hydratedShards.add(OpLogStreamKeyUtil.format(domainType, shard));
		}
		return sealedWm;
	}

	public void ensureShardHydrated(String domainType, int shard) {
		if (!enabled || domainType == null) {
			return;
		}
		if (!isLazyHydrate()) {
			return;
		}
		final String key = OpLogStreamKeyUtil.format(domainType, shard);
		// contains before add: hot getProcessor path hits already-hydrated shards under load.
		if (hydratedShards.contains(key)) {
			return;
		}
		if (!hydratedShards.add(key)) {
			return;
		}
		final ReplicaApplier applier = appliers.get(domainType);
		if (applier == null) {
			hydratedShards.remove(key);
			return;
		}
		long wm = sealedGridMapService.openShardLazy(domainType, shard);
		if (wm > 0) {
			nodeState.resetApplied(domainType, shard);
			nodeState.advanceApplied(domainType, shard, wm);
		}
		rejectHugeOpLogDeltaWithoutSbpt(domainType, shard, wm);
		final int n = snapshotService.hydrateOpLogFrom(domainType, shard, wm, applier, homologousRepair);
		if (n > 0 || wm > 0) {
			log.debug("Lazy-hydrated domain={} shard={} ops={} sealedWm={}", domainType, shard, n, wm);
		}
		final byte[] ckpt = indexCheckpointService.loadIndexBytes(domainType, -1L);
		if (ckpt != null && ckpt.length > 0) {
			log.debug("Index checkpoint present domain={} shard={} bytes={} (miss path uses sealed mmap)",
					domainType, shard, ckpt.length);
		}
	}

	public boolean isLazyHydrate() {
		if (!enabled) {
			return false;
		}
		if (HYDRATE_MODE_LAZY.equals(hydrateMode)) {
			return true;
		}
		return adaptiveDiskFirstController != null && adaptiveDiskFirstController.forceLazySemantics();
	}

	public int workingSetMaxEntries() {
		if (adaptiveDiskFirstController != null) {
			return adaptiveDiskFirstController.effectiveMaxEntries();
		}
		return workingSetMaxEntries;
	}

	public boolean isAdaptiveDiskFirst() {
		return adaptiveDiskFirstController != null;
	}

	public AdaptiveDiskFirstController adaptiveDiskFirstController() {
		return adaptiveDiskFirstController;
	}

	public WorkingSetBudget createWorkingSetBudget() {
		if (!enabled) {
			return null;
		}
		final int cap;
		if (adaptiveDiskFirstController != null) {
			cap = adaptiveDiskFirstController.effectiveMaxEntries();
		} else if (workingSetMaxEntries > 0) {
			cap = workingSetMaxEntries;
		} else {
			return null;
		}
		final WorkingSetBudget budget = new WorkingSetBudget(cap);
		if (adaptiveDiskFirstController != null) {
			adaptiveDiskFirstController.registerBudget(budget);
		}
		return budget;
	}

	public OpLogSegment installSnapshot(String domainType, int shard) {
		return snapshotService.createMarkerSegment(domainType, shard);
	}

	public int dumpDomainSnapshot(String domainType) {
		return sealedGridMapService != null
				? sealedGridMapService.dumpDomain(domainType, opLog, nodeState)
				: 0;
	}

	/**
	 * DROP TABLE: drop hydrate memo so recreate does not skip sealed reopen.
	 */
	public void forgetDomain(String domainType) {
		if (domainType == null || domainType.isEmpty()) {
			return;
		}
		final String prefix = domainType + "#";
		hydratedShards.removeIf(key -> key != null && key.startsWith(prefix));
	}

	private void rejectHugeOpLogDeltaWithoutSbpt(String domainType, int shard, long sealedWm) {
		if (opLog == null || sealedGridMapService == null) {
			return;
		}
		final long last = opLog.lastSeq(domainType, shard);
		final long delta = Math.max(0L, last - Math.max(0L, sealedWm));
		if (delta <= MAX_OPLOG_DELTA_WITHOUT_SBPT) {
			return;
		}
		if (sealedGridMapService.hasAnySealedIndex(domainType, shard)) {
			return;
		}
		hydratedShards.remove(OpLogStreamKeyUtil.format(domainType, shard));
		throw new IllegalStateException(
				"OpLog delta " + delta + " after wm=" + sealedWm
						+ " exceeds " + MAX_OPLOG_DELTA_WITHOUT_SBPT
						+ " without sealed .sbpt for " + domainType + "#" + shard
						+ " — force seal dump before reopen");
	}

	public int installFromOpLog(String domainType, int shard, long fromSeqInclusive, int limit) {
		return snapshotService.installFromOpLog(domainType, shard, fromSeqInclusive, limit, appliers.get(domainType));
	}

	public void maybeDumpSealedAllDomains() {
		if (ReplicationMetrics.oplogFsyncSampleCount() >= SEALED_DUMP_FSYNC_MIN_SAMPLES
				&& ReplicationMetrics.oplogFsyncP50Ns() >= SEALED_DUMP_FSYNC_PRESSURE_P50_NS) {
			log.debug("Sealed dump skipped under OpLog fsync pressure p50Ns={}",
					ReplicationMetrics.oplogFsyncP50Ns());
			return;
		}
		try {
			for (String domain : appliers.keySet()) {
				dumpDomainSealed(domain);
			}
		} catch (RuntimeException ex) {
			log.warn("Sealed checkpoint failed", ex);
		}
	}

	private void dumpDomainSealed(String domain) {
		final int sealedRows = sealedGridMapService.dumpDomain(domain, opLog, nodeState);
		long maxWm = 0L;
		for (String streamKey : opLog.streamKeys()) {
			if (!OpLogStreamKeyUtil.startsWithDomain(streamKey, domain)) {
				continue;
			}
			final OpLogStreamKeyUtil.StreamKey parsed = OpLogStreamKeyUtil.parse(streamKey);
			if (parsed == null) {
				continue;
			}
			final int shard = parsed.shard();
			maxWm = Math.max(maxWm, nodeState.appliedWatermark(domain, shard));
			maxWm = Math.max(maxWm, opLog.lastSeq(domain, shard));
		}
		writeIndexKeyCatalog(domain, maxWm);
		if (sealedRows > 0) {
			log.debug("Sealed GridMap domain={} rows={}", domain, sealedRows);
		}
	}

	public void restoreOrMarkIndexCheckpoint(String domainType, long maxSeq) {
		final byte[] payload = indexCheckpointService.loadIndexBytes(domainType, maxSeq);
		if (payload != null && payload.length > 0) {
			log.info("Index checkpoint hit domain={} seq={} bytes={}", domainType, maxSeq, payload.length);
			return;
		}
		final byte[] any = indexCheckpointService.loadIndexBytes(domainType, -1L);
		if (any != null && any.length > 0) {
			log.info("Index checkpoint present domain={} bytes={} (seq soft-match)", domainType, any.length);
			return;
		}
		indexCheckpointService.markRebuilt(domainType, maxSeq, 0L);
	}

	private void writeIndexKeyCatalog(String domain, long throughSeq) {
		final ByteArrayOutputStream bos = new ByteArrayOutputStream();
		try {
			bos.write(KEYS_MAGIC);
			final ArrayList<byte[]> keys = new ArrayList<>();
			for (int shard = 0; shard < INDEX_KEY_CATALOG_MAX_SHARD; shard++) {
				final SealedGridMapReader reader =
						sealedGridMapService.reader(domain, shard);
				if (reader == null) {
					continue;
				}
				reader.forEachLive((k, v) -> keys.add(k));
			}
			final ByteBuffer countBuf = EncodeBuffers.allocateHeapLe(Integer.BYTES);
			countBuf.putInt(keys.size());
			bos.write(EncodeBuffers.toByteArray(countBuf));
			for (byte[] keyBytes : keys) {
				final ByteBuffer lb = EncodeBuffers.allocateHeapLe(Integer.BYTES);
				lb.putInt(keyBytes.length);
				bos.write(EncodeBuffers.toByteArray(lb));
				bos.write(keyBytes);
			}
			indexCheckpointService.writeIndexBytes(domain, throughSeq, bos.toByteArray());
			indexCheckpointService.markRebuilt(domain, throughSeq, keys.size());
		} catch (IOException ex) {
			log.warn("index key catalog write failed domain={}: {}", domain, ex.toString());
		}
	}

	public void onSealedShardPack(SealedShardPackMsg msg) {
		if (msg == null || sealedGridMapService == null || msg.packed() == null || msg.packed().length == 0) {
			return;
		}
		try {
			sealedGridMapService.unpackShardArtifacts(msg.packed());
			log.debug("Unpacked sealed shard pack from={} domain={} shard={} bytes={}",
					msg.fromNodeId(), msg.domainType(), msg.shard(), msg.packed().length);
		} catch (IOException failure) {
			log.warn("Failed to unpack sealed shard pack from={} domain={} shard={}: {}",
					msg.fromNodeId(), msg.domainType(), msg.shard(), failure.toString());
		}
	}

	public static String normalizeHydrateMode(String raw) {
		if (raw == null) {
			return HYDRATE_MODE_FULL;
		}
		return raw.trim().toUpperCase(Locale.ROOT);
	}
}
