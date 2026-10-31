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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.genfork.grid.diag.VisibilityDiag;
import org.genfork.grid.mem.adaptive.AdaptiveDiskFirstController;
import org.genfork.grid.mem.adaptive.AdaptiveDiskFirstMode;
import org.genfork.grid.mem.stage.WorkingSetBudget;
import org.genfork.grid.nio.EncodeBuffers;
import org.genfork.grid.replication.apply.ReplicaApplier;
import org.genfork.grid.replication.codec.OpLogSegment;
import org.genfork.grid.replication.log.OpLog;
import org.genfork.grid.replication.metrics.ReplicationMetrics;
import org.genfork.grid.replication.netty.ReplicationRpcCodec.SealedShardPackMsg;
import org.genfork.grid.replication.orchid.OrchidNode;
import org.genfork.grid.replication.repair.HomologousRepair;
import org.genfork.grid.replication.snapshot.IndexCheckpointService;
import org.genfork.grid.replication.snapshot.SnapshotService;
import org.genfork.grid.replication.snapshot.sealed.DumpDomainResult;
import org.genfork.grid.replication.snapshot.sealed.SealedDumpSafetyUtil;
import org.genfork.grid.replication.snapshot.sealed.SealedGridMapReader;
import org.genfork.grid.replication.snapshot.sealed.SealedGridMapService;
import org.genfork.grid.replication.tx.TxEnvelopeCoordinator;
import org.genfork.grid.replication.util.EnvelopeClosureHydrateUtil;
import org.genfork.grid.replication.util.LazyShardHydrateFlightUtil;
import org.genfork.grid.replication.util.OpLogStreamKeyUtil;
import org.genfork.grid.replication.util.OrchidTipCatchUpUtil;
import org.genfork.grid.replication.util.SealedHydrateTxFenceUtil;
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
	 * Default sealed checkpoint cadence ({@code grid.durability.sealed-dump-interval-ms}).
	 * Kept above short Capacity QG windows (45 s) so a dump does not collide with living
	 * upsert fsync on the same volume.
	 */
	public static final long SEALED_DUMP_INTERVAL_MS = 60_000L;
	/** Floor for configured interval (reject pathologically tight dump loops). */
	public static final long SEALED_DUMP_INTERVAL_MIN_MS = 15_000L;
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
	private final long sealedDumpIntervalMs;
	private final AdaptiveDiskFirstController adaptiveDiskFirstController;
	private final ReplicationNodeState nodeState;
	private final OpLog opLog;
	private final SnapshotService snapshotService;
	private final IndexCheckpointService indexCheckpointService;
	private final SealedGridMapService sealedGridMapService;
	private final HomologousRepair homologousRepair;
	private final Map<String, ReplicaApplier> appliers;
	final Set<String> hydratedShards = ConcurrentHashMap.newKeySet();
	/**
	 * Single-flight LAZY shard hydrate: losers must wait for the winner to finish binding
	 * sealed {@code .sbpt} — early {@code return} after lost {@code add} left UNIQUE indexes
	 * empty and {@code ON CONFLICT} inserted duplicate IDENTITY rows.
	 */
	final ConcurrentHashMap<String, CompletableFuture<Void>> lazyShardHydrateFlights =
			new ConcurrentHashMap<>();
	private final AtomicInteger hydrateShardsDone = new AtomicInteger();
	private final AtomicInteger hydrateShardsTotal = new AtomicInteger();
	/**
	 * False until {@link #markCatalogBootstrapComplete()} — HELLO sealed packs are queued so they
	 * cannot race nested FULL hydrate during {@code _catalog} DDL replay. Repl-only (no SQL)
	 * opens the gate from {@link ReplicationCoordinator#start()} when catalog was never bound.
	 */
	private final AtomicBoolean catalogBootstrapComplete = new AtomicBoolean(false);
	private final ConcurrentLinkedQueue<SealedShardPackMsg> pendingSealedPacks = new ConcurrentLinkedQueue<>();
	/** Single-flight drain of {@link #pendingSealedPacks} + tip catch-up (no monitor wait). */
	private final AtomicBoolean sealedPackDrainRunning = new AtomicBoolean(false);

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
		this(enabled, hydrateMode, workingSetMaxEntries, SEALED_DUMP_INTERVAL_MS,
				adaptiveDiskFirstController, nodeState, opLog, snapshotService,
				indexCheckpointService, sealedGridMapService, homologousRepair, appliers);
	}

	public SealedHydrateService(
			boolean enabled,
			String hydrateMode,
			int workingSetMaxEntries,
			long sealedDumpIntervalMs,
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
		this.sealedDumpIntervalMs = clampSealedDumpIntervalMs(sealedDumpIntervalMs);
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
				if (VisibilityDiag.enabled()) {
					VisibilityDiag.debugf(VisibilityDiag.WHERE_HYDRATE_ADVANCE_APPLIED,
							"mode=full domain=%s shard=%d appliedWm=%d node=%s",
							domainType, e.getKey(), e.getValue(), nodeState.getNodeId());
				}
			}
		}
		final int hydrated = snapshotService.hydrateDomain(domainType, applier, homologousRepair);
		if (hydrated > 0 || !sealedWm.isEmpty()) {
			log.info("Hydrated domain={} sealedShards={} oplogOps={}",
					domainType, sealedWm.size(), hydrated);
		}
		final Set<Integer> shardIds = ConcurrentHashMap.newKeySet();
		shardIds.addAll(sealedWm.keySet());
		shardIds.addAll(snapshotService.listShards(domainType));
		for (Integer shard : shardIds) {
			hydratedShards.add(OpLogStreamKeyUtil.format(domainType, shard));
		}
		hydrateShardsDone.set(hydratedShards.size());
		hydrateShardsTotal.set(Math.max(hydrateShardsTotal.get(), hydratedShards.size()));
		catchUpTipFromJournal();
		return sealedWm;
	}

	/**
	 * LAZY first-touch hydrate for one shard.
	 * <p>
	 * Single-flight: losers park on the winner's future until sealed {@code .sbpt} bind completes.
	 * <strong>Logic VT / SQL mailbox only</strong> — never call from Netty EL
	 * ({@code reactive-vt.mdc}). Does not use {@code CompletableFuture.*Async} / commonPool.
	 */
	public void ensureShardHydrated(String domainType, int shard) {
		if (!enabled || domainType == null) {
			return;
		}
		if (!isLazyHydrate()) {
			return;
		}
		final String key = OpLogStreamKeyUtil.format(domainType, shard);
		// Hot path: shard already fully hydrated (sealed indexes bound).
		if (hydratedShards.contains(key)) {
			return;
		}
		final CompletableFuture<Void> inFlight = lazyShardHydrateFlights.get(key);
		if (inFlight != null) {
			LazyShardHydrateFlightUtil.await(inFlight, key);
			return;
		}
		final CompletableFuture<Void> created = new CompletableFuture<>();
		final CompletableFuture<Void> winner = lazyShardHydrateFlights.putIfAbsent(key, created);
		if (winner != null) {
			LazyShardHydrateFlightUtil.await(winner, key);
			return;
		}
		try {
			if (runLazyShardHydrate(domainType, shard, key)) {
				hydratedShards.add(key);
				hydrateShardsDone.set(hydratedShards.size());
				hydrateShardsTotal.set(Math.max(hydrateShardsTotal.get(), hydratedShards.size()));
			}
			created.complete(null);
		} catch (RuntimeException | Error ex) {
			lazyShardHydrateFlights.remove(key, created);
			created.completeExceptionally(ex);
			throw ex;
		} finally {
			lazyShardHydrateFlights.remove(key, created);
		}
	}

	/**
	 * Open sealed mmap, bind {@code .sbpt}, replay OpLog tail.
	 *
	 * @return {@code true} when hydrate ran (applier present); {@code false} when skipped — do not
	 *         mark {@link #hydratedShards} (no false done)
	 */
	private boolean runLazyShardHydrate(String domainType, int shard, String key) {
		final ReplicaApplier applier = appliers.get(domainType);
		if (applier == null) {
			return false;
		}
		// Drop live open TX before watermark reset (hydrateOpLogFrom fences again).
		SealedHydrateTxFenceUtil.beforeSealedOrLazyOpLogHydrate(applier, domainType, shard);
		long wm = sealedGridMapService.openShardLazy(domainType, shard);
		if (wm > 0) {
			nodeState.resetApplied(domainType, shard);
			nodeState.advanceApplied(domainType, shard, wm);
			if (VisibilityDiag.enabled()) {
				VisibilityDiag.debugf(VisibilityDiag.WHERE_HYDRATE_ADVANCE_APPLIED,
						"mode=lazy domain=%s shard=%d appliedWm=%d node=%s",
						domainType, shard, wm, nodeState.getNodeId());
			}
		}
		// Orphan multi-stream membership can block dump forever; close before the huge-delta fence
		// so at-risk seal can create .sbpt (mid-TX ENVELOPE defer unchanged).
		closeOrphanApplyEnvelopesAfterStreamHydrate(domainType, shard, applier);
		maybeDumpAtRiskShardBeforeHugeDeltaReject(domainType, shard, wm);
		rejectHugeOpLogDeltaWithoutSbpt(domainType, shard, wm);
		final int n = snapshotService.hydrateOpLogFrom(domainType, shard, wm, applier, homologousRepair);
		closeOrphanApplyEnvelopesAfterStreamHydrate(domainType, shard, applier);
		if (n > 0 || wm > 0) {
			log.debug("Lazy-hydrated domain={} shard={} ops={} sealedWm={}", domainType, shard, n, wm);
		}
		maybeHealIncompleteSealedIndexes(domainType, shard);
		final byte[] ckpt = indexCheckpointService.loadIndexBytes(domainType, -1L);
		if (ckpt != null && ckpt.length > 0) {
			log.debug("Index checkpoint present domain={} shard={} bytes={} (miss path uses sealed mmap)",
					domainType, shard, ckpt.length);
		}
		catchUpTipFromJournal();
		return true;
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

	/**
	 * {@code true} when {@code domain#shard} completed LAZY/FULL hydrate memo (serving stream).
	 */
	public boolean isStreamHydrated(String streamKey) {
		return streamKey != null && hydratedShards.contains(streamKey);
	}

	/** Configured hydrate mode string ({@code FULL} or {@code LAZY}). */
	public String hydrateModeLabel() {
		return hydrateMode;
	}

	/**
	 * Effective hydrate semantics after adaptive force-lazy (FULL or LAZY).
	 */
	public String hydrateModeEffective() {
		return isLazyHydrate() ? HYDRATE_MODE_LAZY : HYDRATE_MODE_FULL;
	}

	/** Adaptive mode name, or {@code null} when adaptive off. */
	public String adaptiveModeName() {
		if (adaptiveDiskFirstController == null) {
			return null;
		}
		final AdaptiveDiskFirstMode mode = adaptiveDiskFirstController.mode();
		return mode == null ? null : mode.name();
	}

	public int hydrateShardsDone() {
		return hydrateShardsDone.get();
	}

	public int hydrateShardsTotal() {
		return hydrateShardsTotal.get();
	}

	/**
	 * Effective working-set cap (adaptive override when enabled).
	 */
	public int workingSetMaxEntries() {
		if (adaptiveDiskFirstController != null) {
			return adaptiveDiskFirstController.effectiveMaxEntries();
		}
		return workingSetMaxEntries;
	}

	/** YAML / ctor configured working-set cap (before adaptive override). */
	public int workingSetMaxEntriesConfigured() {
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
				? dumpDomainSealed(domainType)
				: 0;
	}

	/**
	 * STRICT UNIQUE ghost EQ heal: one sealed dump; {@code false} when any shard deferred.
	 * <p>
	 * Does not truncate OpLog (checkpoint dump retains that); EQ heal must not race concurrent
	 * seal truncate under load.
	 */
	boolean tryHealSealedDomainIndexes(String domainType) {
		if (sealedGridMapService == null || domainType == null) {
			return true;
		}
		final DumpDomainResult result = dumpDomainSealedResult(domainType, false);
		return !result.anyDeferred();
	}

	/**
	 * Effective periodic sealed-dump interval after clamp.
	 */
	long sealedDumpIntervalMs() {
		return sealedDumpIntervalMs;
	}

	/**
	 * Clamp configured interval to [{@link #SEALED_DUMP_INTERVAL_MIN_MS}, Long.MAX] with
	 * default {@link #SEALED_DUMP_INTERVAL_MS} when {@code <= 0}.
	 */
	public static long clampSealedDumpIntervalMs(long configuredMs) {
		if (configuredMs <= 0L) {
			return SEALED_DUMP_INTERVAL_MS;
		}
		return Math.max(SEALED_DUMP_INTERVAL_MIN_MS, configuredMs);
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
		LazyShardHydrateFlightUtil.removeMatching(
				lazyShardHydrateFlights,
				key -> key.startsWith(prefix));
	}

	/**
	 * Complete multi-stream apply envelopes left open after per-stream OpLog hydrate, or
	 * fail-closed abort so ENVELOPE cannot starve sealed dump. Mid-TX dump defer unchanged.
	 * <p>
	 * Sibling replay is {@link SnapshotService#hydrateOpLogFrom} only — never
	 * {@link #runLazyShardHydrate} / this method — so closure cannot re-enter.
	 */
	private void closeOrphanApplyEnvelopesAfterStreamHydrate(
			String domainType,
			int shard,
			ReplicaApplier applier
	) {
		if (applier == null) {
			return;
		}
		final TxEnvelopeCoordinator gate = applier.envelopeCoordinator();
		if (gate == null) {
			return;
		}
		EnvelopeClosureHydrateUtil.completeOrAbortAfterStreamHydrate(
				gate,
				appliers,
				domainType,
				shard,
				this::hydrateSiblingOpLogForEnvelopeClosure);
	}

	/**
	 * OpLog-tail replay for a sibling stream during envelope closure (no LAZY reject / memo).
	 */
	private void hydrateSiblingOpLogForEnvelopeClosure(
			String siblingDomain,
			int siblingShard,
			ReplicaApplier siblingApplier
	) {
		final long siblingWm = nodeState == null
				? 0L
				: nodeState.appliedWatermark(siblingDomain, siblingShard);
		snapshotService.hydrateOpLogFrom(
				siblingDomain, siblingShard, siblingWm, siblingApplier, homologousRepair);
	}

	/**
	 * When OpLog tip already exceeds the without-{@code .sbpt} ceiling, attempt one gated dump
	 * (prepareSeal inside {@link #dumpDomainSealed}) so LAZY reopen is not permanently fail-closed.
	 * Mid-TX / live ENVELOPE defer still blocks dump; ceiling is never raised.
	 */
	private void maybeDumpAtRiskShardBeforeHugeDeltaReject(String domainType, int shard, long sealedWm) {
		if (opLog == null || sealedGridMapService == null || domainType == null) {
			return;
		}
		if (sealedGridMapService.hasUsableSealedIndex(domainType, shard)) {
			return;
		}
		final long last = opLog.lastSeq(domainType, shard);
		final long delta = Math.max(0L, last - Math.max(0L, sealedWm));
		if (delta <= MAX_OPLOG_DELTA_WITHOUT_SBPT) {
			return;
		}
		DumpDomainResult result = DumpDomainResult.empty();
		try {
			result = dumpDomainSealedResult(domainType, true);
		} catch (RuntimeException ex) {
			log.warn("At-risk dump before huge-delta reject failed domain={} shard={} tip={} wm={}: {}",
					domainType, shard, last, sealedWm, ex.toString());
		}
		if (!sealedGridMapService.hasUsableSealedIndex(domainType, shard)) {
			final long tipAfter = opLog.lastSeq(domainType, shard);
			final long deltaAfter = Math.max(0L, tipAfter - Math.max(0L, sealedWm));
			final ReplicaApplier applier = appliers.get(domainType);
			final SealedDumpSafetyUtil.DeferReason defer =
					SealedDumpSafetyUtil.deferReason(applier, domainType, shard);
			log.warn(
					"At-risk dump left no usable .sbpt domain={} shard={} tipBefore={} tipAfter={} wm={} deltaAfter={} rows={} deferred={} reasons={} deferNow={}",
					domainType, shard, last, tipAfter, sealedWm, deltaAfter, result.rows(),
					result.deferredShards(), result.deferReasons(), defer);
		}
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
		// Empty shells count as absent (hasUsableSealedIndex).
		if (sealedGridMapService.hasUsableSealedIndex(domainType, shard)) {
			return;
		}
		hydratedShards.remove(OpLogStreamKeyUtil.format(domainType, shard));
		final ReplicaApplier applier = appliers.get(domainType);
		final SealedDumpSafetyUtil.DeferReason defer =
				SealedDumpSafetyUtil.deferReason(applier, domainType, shard);
		throw new IllegalStateException(
				"OpLog delta " + delta + " after wm=" + sealedWm
						+ " exceeds " + MAX_OPLOG_DELTA_WITHOUT_SBPT
						+ " without sealed .sbpt for " + OpLogStreamKeyUtil.format(domainType, shard)
						+ " defer=" + defer
						+ " — force seal dump before reopen");
	}

	/**
	 * Incomplete {@code .sbpt} (missing / empty shell) with sealed∪OpLog∪RAM rows:
	 * heal via existing {@link #dumpDomainSealed} under OPEN_TX/ENVELOPE gates — not RAM-on-open.
	 */
	private void maybeHealIncompleteSealedIndexes(String domainType, int shard) {
		if (sealedGridMapService == null || domainType == null) {
			return;
		}
		if (!sealedGridMapService.shardAcceleratorsIncomplete(domainType, shard)) {
			return;
		}
		final SealedGridMapReader reader = sealedGridMapService.reader(domainType, shard);
		final boolean sealedHasRows = reader != null && reader.entryCount() > 0;
		final long last = opLog == null ? 0L : opLog.lastSeq(domainType, shard);
		final long wm = Math.max(nodeState == null ? 0L : nodeState.appliedWatermark(domainType, shard), 0L);
		final boolean oplogTail = last > wm;
		if (!sealedHasRows && !oplogTail) {
			// Mode B with nothing to merge — index rewrite cannot invent rows.
			return;
		}
		try {
			final DumpDomainResult result = dumpDomainSealedResult(domainType, true);
			if (sealedGridMapService.hasUsableSealedIndex(domainType, shard)) {
				log.debug("Healed incomplete .sbpt via sealed dump domain={} shard={} rows={}",
						domainType, shard, result.rows());
			} else {
				log.warn(
						"Incomplete .sbpt heal dump did not produce usable index domain={} shard={} rows={} deferred={} reasons={}",
						domainType, shard, result.rows(), result.deferredShards(), result.deferReasons());
			}
		} catch (RuntimeException ex) {
			log.warn("Incomplete .sbpt heal dump failed domain={} shard={}: {}",
					domainType, shard, ex.toString());
		}
	}

	public int installFromOpLog(String domainType, int shard, long fromSeqInclusive, int limit) {
		return snapshotService.installFromOpLog(domainType, shard, fromSeqInclusive, limit, appliers.get(domainType));
	}

	public void maybeDumpSealedAllDomains() {
		if (ReplicationMetrics.oplogFsyncSampleCount() >= SEALED_DUMP_FSYNC_MIN_SAMPLES
				&& ReplicationMetrics.oplogFsyncP50Ns() >= SEALED_DUMP_FSYNC_PRESSURE_P50_NS) {
			log.debug("Sealed dump full pass skipped under OpLog fsync pressure p50Ns={} — at-risk only",
					ReplicationMetrics.oplogFsyncP50Ns());
			// Do not starve shards with no .sbpt and huge OpLog: fence would fail-close on first touch.
			dumpAtRiskDomainsOnly(false);
			return;
		}
		dumpAllDomainsInternal(false);
		dumpAtRiskDomainsOnly(false);
	}

	/**
	 * Seal domains that have at least one shard with OpLog delta above
	 * {@link #MAX_OPLOG_DELTA_WITHOUT_SBPT} and no usable {@code .sbpt} (empty shell = absent),
	 * or incomplete declared accelerators with sealed/OpLog rows. OPEN_TX/ENVELOPE gates still apply.
	 */
	void dumpAtRiskDomainsOnly(boolean shutdownForce) {
		if (sealedGridMapService == null || appliers == null || appliers.isEmpty() || opLog == null) {
			return;
		}
		final boolean truncateOpLog = !shutdownForce;
		for (String domain : appliers.keySet()) {
			if (!domainHasAtRiskShardWithoutSbpt(domain) && !domainHasIncompleteAccelerators(domain)) {
				continue;
			}
			try {
				dumpDomainSealed(domain, truncateOpLog);
			} catch (RuntimeException ex) {
				log.warn("At-risk sealed dump failed domain={}: {}", domain, ex.toString());
			}
		}
	}

	private boolean domainHasAtRiskShardWithoutSbpt(String domainType) {
		if (domainType == null || opLog == null || sealedGridMapService == null) {
			return false;
		}
		boolean atRisk = false;
		for (String streamKey : opLog.streamKeys()) {
			if (!OpLogStreamKeyUtil.startsWithDomain(streamKey, domainType)) {
				continue;
			}
			final OpLogStreamKeyUtil.StreamKey parsed = OpLogStreamKeyUtil.parse(streamKey);
			if (parsed == null) {
				continue;
			}
			final int shard = parsed.shard();
			final long last = opLog.lastSeq(domainType, shard);
			final long wm = Math.max(
					nodeState == null ? 0L : nodeState.appliedWatermark(domainType, shard),
					0L);
			final boolean hasUsableSbpt = sealedGridMapService.hasUsableSealedIndex(domainType, shard);
			if (!SealedDumpSafetyUtil.isAtRiskWithoutSbpt(
					last, wm, hasUsableSbpt, MAX_OPLOG_DELTA_WITHOUT_SBPT)) {
				continue;
			}
			atRisk = true;
			SealedDumpSafetyUtil.warnAtRiskWithoutSbpt(
					domainType, shard, Math.max(0L, last - wm), MAX_OPLOG_DELTA_WITHOUT_SBPT);
		}
		return atRisk;
	}

	private boolean domainHasIncompleteAccelerators(String domainType) {
		if (domainType == null || sealedGridMapService == null) {
			return false;
		}
		for (String streamKey : opLog.streamKeys()) {
			if (!OpLogStreamKeyUtil.startsWithDomain(streamKey, domainType)) {
				continue;
			}
			final OpLogStreamKeyUtil.StreamKey parsed = OpLogStreamKeyUtil.parse(streamKey);
			if (parsed == null) {
				continue;
			}
			final int shard = parsed.shard();
			if (!sealedGridMapService.shardAcceleratorsIncomplete(domainType, shard)) {
				continue;
			}
			final SealedGridMapReader reader = sealedGridMapService.reader(domainType, shard);
			if (reader != null && reader.entryCount() > 0) {
				return true;
			}
			final long last = opLog.lastSeq(domainType, shard);
			final long wm = Math.max(
					nodeState == null ? 0L : nodeState.appliedWatermark(domainType, shard),
					0L);
			if (last > wm) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Graceful stop / shutdown hook: sealed dump for all domains, bypassing fsync-pressure
	 * skip. Open-TX / envelope defer gates remain. Does <em>not</em> truncate OpLog — keeps
	 * prefix for torn-tail / tip-behind reopen; periodic dump still truncates.
	 */
	public void dumpAllDomainsForShutdown() {
		dumpAllDomainsInternal(true);
	}

	private void dumpAllDomainsInternal(boolean shutdownForce) {
		if (sealedGridMapService == null || appliers == null || appliers.isEmpty()) {
			return;
		}
		final boolean truncateOpLog = !shutdownForce;
		int domains = 0;
		int rowsTotal = 0;
		try {
			for (String domain : appliers.keySet()) {
				final int sealedRows = dumpDomainSealed(domain, truncateOpLog);
				domains++;
				rowsTotal += Math.max(0, sealedRows);
			}
			if (shutdownForce) {
				log.info("Sealed shutdown dump domains={} rows={} truncateOpLog=false",
						domains, rowsTotal);
			}
		} catch (RuntimeException ex) {
			log.warn(shutdownForce ? "Sealed shutdown dump failed" : "Sealed checkpoint failed", ex);
		}
	}

	private int dumpDomainSealed(String domain) {
		return dumpDomainSealedResult(domain, true).rows();
	}

	private int dumpDomainSealed(String domain, boolean truncateOpLog) {
		return dumpDomainSealedResult(domain, truncateOpLog).rows();
	}

	/**
	 * Single choke for gated sealed dump: {@code prepareSealForDomain} then dump with tracking gate.
	 */
	private DumpDomainResult dumpDomainSealedResult(String domain, boolean truncateOpLog) {
		if (sealedGridMapService == null || domain == null) {
			return DumpDomainResult.empty();
		}
		final ReplicaApplier applier = appliers.get(domain);
		prepareSealForDomainDump(domain, applier);
		final SealedDumpSafetyUtil.TrackingDeferGate gate = applier == null
				? null
				: SealedDumpSafetyUtil.trackingDeferGate(applier);
		final DumpDomainResult result = sealedGridMapService.dumpDomainResult(
				domain, opLog, nodeState, gate, truncateOpLog);
		if (result.rows() > 0) {
			long maxWm = 0L;
			if (opLog != null) {
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
			}
			writeIndexKeyCatalog(domain, maxWm);
			log.debug("Sealed GridMap domain={} rows={} truncateOpLog={} deferred={}",
					domain, result.rows(), truncateOpLog, result.deferredShards());
		} else if (result.anyDeferred() && domainHasAtRiskShardWithoutSbpt(domain)) {
			log.warn(
					"Sealed dump fully/partially deferred for at-risk domain={} rows={} deferred={} reasons={}",
					domain, result.rows(), result.deferredShards(), result.deferReasons());
		}
		return result;
	}

	private void prepareSealForDomainDump(String domain, ReplicaApplier applier) {
		if (applier == null || sealedGridMapService == null) {
			return;
		}
		final TxEnvelopeCoordinator gate = applier.envelopeCoordinator();
		if (gate == null) {
			return;
		}
		EnvelopeClosureHydrateUtil.prepareSealForDomain(
				gate,
				appliers,
				domain,
				sealedGridMapService.collectDumpShards(domain, opLog),
				this::hydrateSiblingOpLogForEnvelopeClosure);
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
		pendingSealedPacks.offer(msg);
		if (!catalogBootstrapComplete.get()) {
			log.debug("Deferred sealed shard pack until catalog bootstrap from={} domain={} shard={}",
					msg.fromNodeId(), msg.domainType(), msg.shard());
			return;
		}
		drainPendingSealedPacksWithTipCatchUp();
	}

	/**
	 * SQL-only: re-gate HELLO sealed packs until catalog recover finishes
	 * (bind may run after a repl-only {@link #markCatalogBootstrapComplete()} on start).
	 */
	public void beginCatalogBootstrap() {
		catalogBootstrapComplete.set(false);
	}

	/**
	 * Allow HELLO sealed packs after SqlEngine catalog bind + deferred table hydrates.
	 * Drains packs queued during bootstrap (shard-local apply, not full-domain stampede).
	 */
	public void markCatalogBootstrapComplete() {
		catalogBootstrapComplete.set(true);
		drainPendingSealedPacksWithTipCatchUp();
	}

	/**
	 * Drain queued sealed packs then journal / peer-tip catch-up (retired OpLog gap).
	 * Single-flight via CAS — no monitor wait on Netty / VT; no recursive re-entry.
	 */
	private void drainPendingSealedPacksWithTipCatchUp() {
		while (catalogBootstrapComplete.get()) {
			if (!sealedPackDrainRunning.compareAndSet(false, true)) {
				return;
			}
			int applied = 0;
			try {
				SealedShardPackMsg pending;
				while ((pending = pendingSealedPacks.poll()) != null) {
					if (applySealedShardPackLocal(pending)) {
						applied++;
					}
				}
				catchUpTipFromJournal();
				if (applied >= OrchidTipCatchUpUtil.MIN_SEALED_PACKS_FOR_PEER_TIP) {
					final OrchidNode orchid = nodeState.getOrchidNode();
					if (orchid != null) {
						OrchidTipCatchUpUtil.catchUpToPeerTipAfterSealedBaselineRound(
								orchid,
								opLog,
								orchid.maxSeenPeerCommittedSeq(),
								applied);
					}
				}
			} finally {
				sealedPackDrainRunning.set(false);
			}
			if (pendingSealedPacks.isEmpty() || !catalogBootstrapComplete.get()) {
				return;
			}
		}
	}

	private void catchUpTipFromJournal() {
		final OrchidNode orchid = nodeState.getOrchidNode();
		if (orchid == null || opLog == null) {
			return;
		}
		OrchidTipCatchUpUtil.catchUpFromJournal(orchid, opLog);
	}

	/**
	 * Unpack one shard and reopen + OpLog-tail hydrate that shard only.
	 * Must not call {@link #hydrateDomainFull} per pack (stampede × shardCount).
	 *
	 * @return {@code true} when unpack + open completed without I/O failure
	 */
	private boolean applySealedShardPackLocal(SealedShardPackMsg msg) {
		final String domainType = msg.domainType();
		final int shard = msg.shard();
		try {
			// Unmap live readers before REPLACE — Windows AccessDenied on mmap'd sealed files.
			sealedGridMapService.unpackShardArtifacts(domainType, shard, msg.packed());
			log.debug("Unpacked sealed shard pack from={} domain={} shard={} bytes={}",
					msg.fromNodeId(), domainType, shard, msg.packed().length);
			final ReplicaApplier applier = appliers.get(domainType);
			// Drop live open TX before watermark reset (hydrateOpLogFrom fences again).
			SealedHydrateTxFenceUtil.beforeSealedOrLazyOpLogHydrate(applier, domainType, shard);
			final long wm = sealedGridMapService.openShardLazy(domainType, shard);
			if (wm > 0L) {
				nodeState.resetApplied(domainType, shard);
				nodeState.advanceApplied(domainType, shard, wm);
			}
			if (applier != null) {
				snapshotService.hydrateOpLogFrom(domainType, shard, wm, applier, homologousRepair);
				closeOrphanApplyEnvelopesAfterStreamHydrate(domainType, shard, applier);
			}
			final String key = OpLogStreamKeyUtil.format(domainType, shard);
			hydratedShards.add(key);
			hydrateShardsDone.set(hydratedShards.size());
			hydrateShardsTotal.set(Math.max(hydrateShardsTotal.get(), hydratedShards.size()));
			return true;
		} catch (IOException failure) {
			log.warn("Failed to unpack sealed shard pack from={} domain={} shard={}: {}",
					msg.fromNodeId(), domainType, shard, failure.toString());
			return false;
		}
	}

	public static String normalizeHydrateMode(String raw) {
		if (raw == null) {
			return HYDRATE_MODE_FULL;
		}
		return raw.trim().toUpperCase(Locale.ROOT);
	}
}
