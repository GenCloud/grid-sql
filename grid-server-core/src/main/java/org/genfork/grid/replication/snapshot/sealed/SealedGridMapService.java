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
package org.genfork.grid.replication.snapshot.sealed;

import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.fs.GridFs;
import org.genfork.grid.mem.index.IndexPointerRef;
import org.genfork.grid.mem.index.bitmap.GridBitmapIndex;
import org.genfork.grid.mem.index.btree.SingleTreeKey;
import org.genfork.grid.mem.stage.GridEntriesProcessor;
import org.genfork.grid.replication.ReplicationNodeState;
import org.genfork.grid.replication.log.OpLog;
import org.genfork.grid.replication.util.OpLogArchiveUtil;
import org.genfork.grid.replication.util.OpLogStreamKeyUtil;
import org.genfork.grid.utils.ArrayUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Per-domain sealed GridMap lifecycle service.
 * <p>
 * When {@code opLogArchiveRoot} is set, {@link #dumpDomain} archives OpLog ranges
 * before {@link OpLog#truncateTo} (fail-closed). Archive I/O is sync on the calling
 * thread — seal dump must already run off Netty EL.
 *
 * @author: GenCloud
 * @date: 2026/05
 * @since: 1.0
 */
public final class SealedGridMapService {
	private static final Logger log = LoggerFactory.getLogger(SealedGridMapService.class);

	private final Path sealedRoot;
	/** Non-null when {@code grid.durability.oplog-archive.enabled}; else skip archive. */
	private final Path opLogArchiveRoot;
	private final SealedBPTreeService sealedBPTreeService;
	private final SealedBitmapService sealedBitmapService;
	private final Map<String, SealedGridMapReader> readers = new ConcurrentHashMap<>();
	private final Map<String, Function<Integer, GridEntriesProcessor>> processors = new ConcurrentHashMap<>();
	private final Map<String, Set<Integer>> registeredShards = new ConcurrentHashMap<>();
	private final SealedReaderLifecycleGate lifecycleGate = new SealedReaderLifecycleGate();
	/** Cached aggregate sealed identity; invalidated on unpack/seal/purge. */
	private final AtomicLong cachedAggregateIdentityFp = new AtomicLong(0L);
	private final AtomicLong aggregateIdentityEpoch = new AtomicLong(0L);
	private final AtomicLong cachedAggregateEpoch = new AtomicLong(-1L);
	/**
	 * Cleared when a live sealed reader is dropped for overwrite — LAZY hydrate memo must not
	 * stay sticky with {@code sealedReader == null}.
	 */
	private volatile BiConsumer<String, Integer> onSealedReaderReleased;

	public SealedGridMapService(Path sealedRoot) {
		this(sealedRoot, null);
	}

	/**
	 * @param sealedRoot       sealed GridMap root ({@code dataDir/sealed})
	 * @param opLogArchiveRoot PITR archive root, or {@code null} to disable archive-before-truncate
	 */
	public SealedGridMapService(Path sealedRoot, Path opLogArchiveRoot) {
		this.sealedRoot = sealedRoot;
		this.opLogArchiveRoot = opLogArchiveRoot;
		this.sealedBPTreeService = sealedRoot == null ? null : new SealedBPTreeService(sealedRoot);
		this.sealedBitmapService = sealedRoot == null ? null : new SealedBitmapService(sealedRoot);
		if (sealedRoot != null) {
			try {
				GridFs.createDirs(sealedRoot);
			} catch (IOException e) {
				throw new IllegalStateException("sealed dir", e);
			}
		}
	}

	/** Safety cap: Function must return null past last shard (see TableStore). */
	private static final int MAX_SHARD_PROBE = 4_096;

	/**
	 * Per-shard gate: {@code true} skips dump for that shard (open TX / envelope).
	 */
	@FunctionalInterface
	public interface DumpDeferGate {
		boolean shouldDefer(String domainType, int shard);
	}

	public void bindProcessor(String domainType, Function<Integer, GridEntriesProcessor> processorByShard) {
		if (domainType == null || processorByShard == null) {
			return;
		}
		processors.put(domainType, processorByShard);
		final Set<Integer> shards = ConcurrentHashMap.newKeySet();
		for (int shard = 0; shard < MAX_SHARD_PROBE; shard++) {
			final GridEntriesProcessor processor = processorByShard.apply(shard);
			if (processor == null) {
				break;
			}
			shards.add(shard);
		}
		registeredShards.put(domainType, shards);
	}

	/**
	 * Wire LAZY hydrate-memo clear when overwrite drops a bound sealed reader.
	 */
	public void setOnSealedReaderReleased(BiConsumer<String, Integer> onSealedReaderReleased) {
		this.onSealedReaderReleased = onSealedReaderReleased;
	}

	/**
	 * {@code true} when sealed root has any artifact for {@code domain#shard} ({@code .gmap}/
	 * {@code .sbpt}/{@code .sbm}).
	 */
	public boolean hasSealedShardArtifacts(String domainType, int shard) {
		if (sealedRoot == null || domainType == null) {
			return false;
		}
		try {
			return !SealedShardPack.listShardFiles(sealedRoot, domainType, shard).isEmpty();
		} catch (IOException ignored) {
			return false;
		}
	}

	/**
	 * {@code true} when at least one node {@code .gmap} exists for {@code domain#shard}.
	 * <p>
	 * LAZY bind / proven-empty uses map nodes only — orphan {@code .sbpt}/{@code .sbm} after
	 * empty-clear must not trip fail-closed or hot-path disk storms.
	 */
	public boolean hasSealedMapNodes(String domainType, int shard) {
		if (sealedRoot == null || domainType == null) {
			return false;
		}
		return SealedShardPack.hasMapNodes(sealedRoot, domainType, shard);
	}

	/**
	 * Known registered domain names (for HELLO inventory hex→domain resolve).
	 */
	public Set<String> registeredDomainNames() {
		return Set.copyOf(registeredShards.keySet());
	}

	public int dumpDomain(String domainType, OpLog opLog, ReplicationNodeState nodeState) {
		return dumpDomainResult(domainType, opLog, nodeState, null, true).rows();
	}

	/**
	 * @param deferGate optional per-shard skip (open TX / envelope); {@code null} = dump all
	 */
	public int dumpDomain(
			String domainType,
			OpLog opLog,
			ReplicationNodeState nodeState,
			DumpDeferGate deferGate
	) {
		return dumpDomainResult(domainType, opLog, nodeState, deferGate, true).rows();
	}

	/**
	 * @param deferGate      optional per-shard skip (open TX / envelope); {@code null} = dump all
	 * @param truncateOpLog  when {@code true} (periodic seal), archive+truncate through watermark;
	 *                       {@code false} for graceful-stop seal so OpLog prefix remains for
	 *                       torn-tail / tip-behind reopen insurance
	 */
	public int dumpDomain(
			String domainType,
			OpLog opLog,
			ReplicationNodeState nodeState,
			DumpDeferGate deferGate,
			boolean truncateOpLog
	) {
		return dumpDomainResult(domainType, opLog, nodeState, deferGate, truncateOpLog).rows();
	}

	/**
	 * Sealed dump with deferred-shard accounting (at-risk / heal success contract).
	 */
	public DumpDomainResult dumpDomainResult(
			String domainType,
			OpLog opLog,
			ReplicationNodeState nodeState,
			DumpDeferGate deferGate,
			boolean truncateOpLog
	) {
		if (sealedRoot == null || domainType == null) {
			return DumpDomainResult.empty();
		}
		final Function<Integer, GridEntriesProcessor> byShard = processors.get(domainType);
		if (byShard == null) {
			return DumpDomainResult.empty();
		}
		final Set<Integer> shards = collectDumpShards(domainType, opLog);
		final Set<Integer> bound = registeredShards.get(domainType);
		final int tableShardCount = bound != null && !bound.isEmpty()
				? bound.size()
				: Math.max(1, shards.size());

		int rows = 0;
		for (Integer shard : shards) {
			if (deferGate != null && deferGate.shouldDefer(domainType, shard)) {
				continue;
			}
			rows += dumpOneShard(
					domainType, shard, byShard, opLog, nodeState, tableShardCount, truncateOpLog);
		}
		if (rows > 0) {
			invalidateSealedIdentityCache();
		}
		if (deferGate instanceof SealedDumpSafetyUtil.TrackingDeferGate tracking) {
			return new DumpDomainResult(rows, tracking.deferredShards(), tracking.deferredReasons());
		}
		return new DumpDomainResult(rows, List.of(), List.of());
	}

	/**
	 * Shards to visit for dump / seal-prepare: registered table shards ∪ OpLog streams.
	 */
	public Set<Integer> collectDumpShards(String domainType, OpLog opLog) {
		final Set<Integer> shards = new LinkedHashSet<>();
		if (domainType == null) {
			return shards;
		}
		final Set<Integer> bound = registeredShards.get(domainType);
		if (bound != null) {
			shards.addAll(bound);
		}
		if (opLog != null) {
			for (String streamKey : opLog.streamKeys()) {
				if (OpLogStreamKeyUtil.startsWithDomain(streamKey, domainType)) {
					shards.add(SealedDumpMergeUtil.parseShard(streamKey));
				}
			}
		}
		return shards;
	}

	/**
	 * Phase 1: merge under pin (no exclusive) so HELLO/openLazy are not blocked on {@code forEachLive}.
	 * Phase 2: short exclusive — overwrite-close + write + swap (G-00001).
	 */
	private int dumpOneShard(
			String domainType,
			int shard,
			Function<Integer, GridEntriesProcessor> byShard,
			OpLog opLog,
			ReplicationNodeState nodeState,
			int shardCount,
			boolean truncateOpLog
	) {
		final GridEntriesProcessor processor = byShard.apply(shard);
		if (processor == null) {
			return 0;
		}
		final long watermark = Math.max(
				nodeState == null ? 0L : nodeState.appliedWatermark(domainType, shard),
				opLog == null ? 0L : opLog.lastSeq(domainType, shard));
		// Sealed ∪ OpLog-delta ∪ RAM: eviction may drop cold keys from the working set before
		// first seal; OpLog recovers them so truncate after dump cannot lose rows.
		// LAZY unbound: installed reader may be null while .gmap still holds cold keys — open
		// ephemeral disk prior (pin across forEachLive) or tip-only overwrite destroys sealed.
		final LinkedHashMap<SealedDumpMergeUtil.BytesKey, byte[]> merged = new LinkedHashMap<>();
		final SealedDumpMergeUtil.DumpPrior dumpPrior;
		try {
			dumpPrior = SealedDumpMergeUtil.openPriorForDump(
					sealedRoot, domainType, shard, reader(domainType, shard));
		} catch (IOException e) {
			throw new IllegalStateException(
					"sealed map nodes present but openShard failed "
							+ OpLogStreamKeyUtil.format(domainType, shard),
					e);
		}
		final SealedGridMapReader prior = dumpPrior.prior();
		final long priorWm = dumpPrior.priorWm();
		final boolean pinned = prior != null;
		if (pinned) {
			lifecycleGate.pin(domainType, shard);
		}
		try {
			if (prior != null) {
				prior.forEachLive((key, value) -> merged.put(new SealedDumpMergeUtil.BytesKey(key), value));
			}
			SealedDumpMergeUtil.mergeOpLogDeltaInto(merged, domainType, shard, priorWm, opLog);
			// Live puts overwrite; sealed-delete tombstones remove (STRICT displace/repair).
			// Skipping tombstones here used to resurrect losers from prior sealed into .gmap/.sbpt.
			processor.forEachSealDumpOverlay(
					(key, value) -> merged.put(new SealedDumpMergeUtil.BytesKey(key), value),
					key -> merged.remove(new SealedDumpMergeUtil.BytesKey(key)));
		} finally {
			if (pinned) {
				lifecycleGate.unpin(domainType, shard);
			}
			SealedDumpMergeUtil.closeQuietly(dumpPrior.ephemeralToClose());
		}
		if (merged.isEmpty()) {
			final long tip = opLog == null ? 0L : opLog.lastSeq(domainType, shard);
			// STRICT displace tombstones can clear every live key on this shard while prior
			// .gmap still holds the loser. Leaving prior files + truncating OpLog resurrects duals.
			if (prior != null) {
				try {
					return lifecycleGate.callExclusive(domainType, shard, () -> {
						try {
							releaseReadersBeforeOverwriteUnlocked(domainType, shard);
							// Tombstone-empty: drop ALL sealed artifacts (.gmap/.sbpt/.sbm).
							// Empty .sbpt shells left debris that tripped LAZY bind-fail
							// (openShard=null while listShardFiles non-empty) and killed TPS.
							SealedShardPack.purgeOrphansNotIn(
									sealedRoot, domainType, shard, Set.of());
							swapReader(domainType, shard, processor, null);
							invalidateSealedIdentityCache();
							final long through = Math.max(watermark, tip);
							if (truncateOpLog && opLog != null && through > 0L) {
								OpLogArchiveUtil.archiveBeforeTruncate(
										opLog, opLogArchiveRoot, domainType, shard, through);
								opLog.truncateTo(domainType, shard, through);
							}
							log.info(
									"Sealed dump cleared empty shard after tombstone overlay domain={} shard={} throughSeq={}",
									domainType, shard, through);
							return 0;
						} catch (IOException e) {
							throw new UncheckedSealedIo(e);
						}
					});
				} catch (UncheckedSealedIo wrapped) {
					throw new IllegalStateException(
							"sealed empty-clear failed " + OpLogStreamKeyUtil.format(domainType, shard),
							wrapped.io);
				}
			}
			if (tip > priorWm) {
				log.warn(
						"Sealed dump merge empty with OpLog tip ahead domain={} shard={} priorWm={} tip={} (markers/net-empty?)",
						domainType, shard, priorWm, tip);
				// Net-empty / markers-only tip must not permanently fence LAZY reopen:
				// retire the replayed empty delta (same truncate contract as a successful dump).
				if (truncateOpLog && opLog != null && tip > 0L) {
					OpLogArchiveUtil.archiveBeforeTruncate(
							opLog, opLogArchiveRoot, domainType, shard, tip);
					opLog.truncateTo(domainType, shard, tip);
					log.warn(
							"Sealed dump truncated net-empty OpLog tip domain={} shard={} throughSeq={}",
							domainType, shard, tip);
				}
			}
			return 0;
		}
		final List<SealedGridMapWriter.Kv>[] nodeBuckets = SealedGridMapWriter.newNodeBuckets();
		for (Map.Entry<SealedDumpMergeUtil.BytesKey, byte[]> e : merged.entrySet()) {
			SealedGridMapWriter.addToNodeBucket(nodeBuckets, e.getKey().bytes(), e.getValue());
		}
		final int rowCount = merged.size();
		try {
			return lifecycleGate.callExclusive(domainType, shard, () -> {
				try {
					// Publish-next-then-drop (G-00001 + no null-generation EQ gap):
					// 1) write .gmap to unpublished siblings (prior may stay mapped)
					// 2) dumpIndexes: write .sbpt staging → install next → close prior → publish
					// 3) open+install next .gmap (swapReader closes prior)
					// 4) orphan delete + publish staging→canonical + remount + truncate
					final Path[] staging = SealedGridMapWriter.writeNodeBucketsUnpublished(
							sealedRoot, domainType, shard, watermark, nodeBuckets);
					dumpIndexes(domainType, shard, shardCount, processor, rowCount, merged);
					final SealedGridMapReader openedStaging = SealedGridMapReader.openShardFromNodePaths(
							sealedRoot, domainType, shard, staging);
					if (openedStaging == null) {
						SealedGridMapWriter.deleteStagingQuietly(staging);
						throw new IOException("node seal produced no files for "
								+ OpLogStreamKeyUtil.format(domainType, shard));
					}
					swapReader(domainType, shard, processor, openedStaging);
					final List<SealedGridMapWriter.Kv> flatForOrphans =
							SealedDumpMergeUtil.flattenNodeBuckets(nodeBuckets);
					deleteOrphanNodes(domainType, shard, flatForOrphans);
					SealedGridMapWriter.publishNodeBuckets(sealedRoot, domainType, shard, staging);
					final SealedGridMapReader openedCanonical =
							SealedGridMapReader.openShard(sealedRoot, domainType, shard);
					if (openedCanonical == null) {
						SealedGridMapWriter.deleteStagingQuietly(staging);
						throw new IOException("canonical seal open failed for "
								+ OpLogStreamKeyUtil.format(domainType, shard));
					}
					swapReader(domainType, shard, processor, openedCanonical);
					SealedGridMapWriter.deleteStagingQuietly(staging);
					if (truncateOpLog && opLog != null && watermark > 0L) {
						// Archive sync on seal thread (already off Netty EL); fail-closed before truncate.
						OpLogArchiveUtil.archiveBeforeTruncate(
								opLog, opLogArchiveRoot, domainType, shard, watermark);
						opLog.truncateTo(domainType, shard, watermark);
					}
					return rowCount;
				} catch (IOException e) {
					throw new UncheckedSealedIo(e);
				}
			});
		} catch (UncheckedSealedIo wrapped) {
			throw new IllegalStateException("sealed dump failed " + OpLogStreamKeyUtil.format(domainType, shard), wrapped.io);
		}
	}

	/** FULL hydrate: bind every sealed shard and preload live values into its RAM map. */
	public Map<Integer, Long> loadAllIntoMap(String domainType) {
		final Map<Integer, Long> watermarks = new ConcurrentHashMap<>();
		if (sealedRoot == null || domainType == null || !Files.isDirectory(sealedRoot)) {
			return watermarks;
		}
		final Function<Integer, GridEntriesProcessor> byShard = processors.get(domainType);
		if (byShard == null) {
			return watermarks;
		}
		for (Integer shard : listShards(domainType)) {
			loadOneShardIntoMap(domainType, shard, byShard, watermarks);
		}
		return watermarks;
	}

	private void loadOneShardIntoMap(
			String domainType,
			int shard,
			Function<Integer, GridEntriesProcessor> byShard,
			Map<Integer, Long> watermarks
	) {
		final GridEntriesProcessor processor = byShard.apply(shard);
		if (processor == null) {
			return;
		}
		final SealedGridMapReader[] opened = new SealedGridMapReader[1];
		final boolean pinned = lifecycleGate.runExclusiveAndPin(domainType, shard, () -> {
			try {
				final SealedGridMapReader reader = SealedGridMapReader.openShard(sealedRoot, domainType, shard);
				if (reader == null) {
					return false;
				}
				swapReader(domainType, shard, processor, reader);
				bindSealedIndexes(domainType, shard, processor);
				opened[0] = reader;
				registeredShards.computeIfAbsent(domainType, ignored -> ConcurrentHashMap.newKeySet()).add(shard);
				return true;
			} catch (IOException e) {
				throw new IllegalStateException("sealed load failed for " + domainType, e);
			}
		});
		if (!pinned) {
			return;
		}
		final SealedGridMapReader reader = opened[0];
		try {
			final List<GridEntriesProcessor.AddEntry> chunk = new ArrayList<>(SealedDumpMergeUtil.SEAL_DUMP_OPLOG_BATCH);
			reader.forEachLive((key, value) -> {
				processor.putMapOnly(key, value);
				chunk.add(new GridEntriesProcessor.AddEntry(null, key, value));
				if (chunk.size() >= SealedDumpMergeUtil.SEAL_DUMP_OPLOG_BATCH) {
					processor.indexDeltaNow(chunk);
					chunk.clear();
				}
			});
			if (!chunk.isEmpty()) {
				processor.indexDeltaNow(chunk);
				chunk.clear();
			}
			watermarks.put(shard, reader.watermark());
		} finally {
			lifecycleGate.unpin(domainType, shard);
		}
	}

	/** LAZY hydrate: bind a shard reader without preloading its payload. */
	public long openShardLazy(String domainType, int shard) {
		return lifecycleGate.callExclusive(domainType, shard, () -> openShardLazyUnlocked(domainType, shard));
	}

	private long openShardLazyUnlocked(String domainType, int shard) {
		try {
			final SealedGridMapReader reader = SealedGridMapReader.openShard(sealedRoot, domainType, shard);
			if (reader == null) {
				return 0L;
			}
			final Function<Integer, GridEntriesProcessor> byShard = processors.get(domainType);
			final GridEntriesProcessor processor = byShard == null ? null : byShard.apply(shard);
			swapReader(domainType, shard, processor, reader);
			registeredShards.computeIfAbsent(domainType, ignored -> ConcurrentHashMap.newKeySet()).add(shard);
			bindSealedIndexes(domainType, shard, processor);
			return reader.watermark();
		} catch (IOException e) {
			throw new IllegalStateException("sealed open lazy failed for " + OpLogStreamKeyUtil.format(domainType, shard), e);
		}
	}

	public SealedGridMapReader reader(String domainType, int shard) {
		return readers.get(OpLogStreamKeyUtil.format(domainType, shard));
	}

	/**
	 * True when at least one sealed {@code .sbpt} for the shard is authoritative
	 * ({@code entryCount > 0}). Empty shells and truncated debris count as absent
	 * (at-risk / reject-huge-delta fences).
	 */
	public boolean hasAnySealedIndex(String domainType, int shard) {
		return hasUsableSealedIndex(domainType, shard);
	}

	/**
	 * True when at least one {@code .sbpt} has {@code entryCount > 0}.
	 */
	public boolean hasUsableSealedIndex(String domainType, int shard) {
		if (sealedRoot == null || domainType == null || !Files.isDirectory(sealedRoot)) {
			return false;
		}
		final String prefix = SealedShardPack.domainHex(domainType) + "_" + shard + "_idx_";
		try (DirectoryStream<Path> stream = GridFs.newDirectoryStream(sealedRoot, prefix + "*.sbpt")) {
			for (Path idxFile : stream) {
				if (SealedLazyIndexRebuildUtil.isAuthoritativeSealedIndexFile(idxFile)) {
					return true;
				}
			}
			return false;
		} catch (IOException ignored) {
			return false;
		}
	}

	/**
	 * Declared single/composite accelerators missing, truncated, or empty-shell — heal via dump.
	 */
	public boolean shardAcceleratorsIncomplete(String domainType, int shard) {
		if (sealedBPTreeService == null || domainType == null) {
			return false;
		}
		final Function<Integer, GridEntriesProcessor> byShard = processors.get(domainType);
		final GridEntriesProcessor processor = byShard == null ? null : byShard.apply(shard);
		if (processor == null || processor.compositeIndex() == null) {
			return false;
		}
		return SealedLazyIndexRebuildUtil.anyAcceleratorIncomplete(
				sealedBPTreeService, domainType, shard, processor.compositeIndex());
	}

	/**
	 * Pack sealed shard artifacts (GMAP + SBPT + SBM) for peer transfer — used by
	 * {@code ShardMigrator} CATCH_UP via {@link SealedShardPack} (no second protocol).
	 */
	public byte[] packShardArtifacts(String domainType, int shard) throws IOException {
		if (sealedRoot == null) {
			return new byte[0];
		}
		return SealedShardPack.pack(sealedRoot, domainType, shard);
	}

	/**
	 * Aggregate name+size sealed identity for HELLO — entire sealed root (catches node holes
	 * even when catalog domain keys diverge from dump prefixes). Cached until
	 * {@link #invalidateSealedIdentityCache()}.
	 */
	public long aggregateSealedIdentityFingerprint() throws IOException {
		if (sealedRoot == null) {
			return 0L;
		}
		final long epoch = aggregateIdentityEpoch.get();
		if (cachedAggregateEpoch.get() == epoch) {
			return cachedAggregateIdentityFp.get();
		}
		final long computed = SealedPackFingerprint.ofSealedRootIdentity(sealedRoot);
		cachedAggregateIdentityFp.set(computed);
		cachedAggregateEpoch.set(epoch);
		return computed;
	}

	/**
	 * Invalidate cached aggregate sealed identity after unpack / seal / purge.
	 */
	public void invalidateSealedIdentityCache() {
		aggregateIdentityEpoch.incrementAndGet();
	}

	/**
	 * Invoke {@code consumer} for every registered {@code domain#shard} binding plus any
	 * shard that still has on-disk {@code .gmap} under that domain hex (covers hole shards
	 * missing from OpLog stream keys / partial registration).
	 */
	public void forEachRegisteredShard(BiConsumer<String, Integer> consumer) {
		forEachDomainShards(registeredShards.keySet(), consumer);
	}

	/**
	 * Invoke {@code consumer} for registered shards of each known domain plus on-disk
	 * {@code .gmap} hole shards under that domain hex (HELLO inventory / heal fan-out).
	 */
	public void forEachDomainShards(Iterable<String> domains, BiConsumer<String, Integer> consumer) {
		if (consumer == null || domains == null) {
			return;
		}
		final Set<String> emitted = new HashSet<>();
		for (String domain : domains) {
			if (domain == null || domain.isEmpty()) {
				continue;
			}
			final Set<Integer> shards = registeredShards.get(domain);
			if (shards != null) {
				for (Integer shard : shards) {
					if (shard == null) {
						continue;
					}
					if (emitted.add(OpLogStreamKeyUtil.format(domain, shard))) {
						consumer.accept(domain, shard);
					}
				}
			}
			emitOnDiskShards(domain, emitted, consumer);
		}
	}

	private void emitOnDiskShards(
			String domain,
			Set<String> emitted,
			BiConsumer<String, Integer> consumer
	) {
		if (!GridFs.isDirectory(sealedRoot)) {
			return;
		}

		final String hex = SealedShardPack.domainHex(domain);
		final String prefix = hex + "_";
		try (DirectoryStream<Path> stream = GridFs.newDirectoryStream(sealedRoot, prefix + "*_n*.gmap")) {
			for (Path file : stream) {
				final Integer shard = SealedIndexPurgeUtil.parseShard(file.getFileName().toString(), hex);
				if (shard == null || shard < 0) {
					continue;
				}
				if (emitted.add(OpLogStreamKeyUtil.format(domain, shard))) {
					consumer.accept(domain, shard);
				}
			}
		} catch (IOException ignored) {
			// best-effort heal enumeration
		}
	}

	/** Sealed root path when configured; {@code null} when durability sealed is unbound. */
	public Path sealedRootOrNull() {
		return sealedRoot;
	}

	/**
	 * Unpack peer-shipped sealed artifacts into this node's sealed root.
	 * Unmaps live {@code .gmap}/{@code .sbpt} readers for {@code domain#shard} before rewrite
	 * (Windows denies REPLACE on mapped files — G-00001).
	 * After unpack, call {@link #openShardLazy} so peers can open {@link SealedBPTreeReader} via bind.
	 */
	public List<Path> unpackShardArtifacts(String domainType, int shard, byte[] packed) throws IOException {
		if (sealedRoot == null) {
			throw new IllegalStateException("sealed root not configured");
		}
		if (!SealedShardPack.isCompleteShardArtifacts(packed)) {
			throw new IOException("incomplete sealed shard pack for "
					+ OpLogStreamKeyUtil.format(domainType, shard));
		}
		try {
			return lifecycleGate.callExclusive(domainType, shard, () -> {
				try {
					releaseReadersBeforeOverwriteUnlocked(domainType, shard);
					final List<Path> written = SealedShardPack.unpack(packed, sealedRoot);
					final Set<String> keep = SealedShardPack.packedEntryNames(packed);
					SealedShardPack.purgeOrphansNotIn(sealedRoot, domainType, shard, keep);
					invalidateSealedIdentityCache();
					return written;
				} catch (IOException e) {
					throw new UncheckedSealedIo(e);
				}
			});
		} catch (UncheckedSealedIo wrapped) {
			throw wrapped.io;
		}
	}

	/**
	 * Unmap {@code .gmap}/{@code .sbpt} for one shard before overwrite (caller holds lifecycle gate).
	 * Does <em>not</em> {@code clearSealedIndexes} — shared composite keeps other shards' sealed readers
	 * (Windows denies REPLACE on mmap'd sealed — G-00001).
	 */
	private void releaseReadersBeforeOverwriteUnlocked(String domainType, int shard) throws IOException {
		final Function<Integer, GridEntriesProcessor> byShard = processors.get(domainType);
		final GridEntriesProcessor processor = byShard == null ? null : byShard.apply(shard);
		releaseReaderForOverwrite(domainType, shard, processor);
		SealedIndexLifecycleUtil.releaseShardSealedOnly(sealedBPTreeService, domainType, shard);
	}

	public SealedBitmapService sealedBitmapService() {
		return sealedBitmapService;
	}

	public void closeAll() {
		for (SealedGridMapReader reader : readers.values()) {
			SealedDumpMergeUtil.closeQuietly(reader);
		}
		readers.clear();
		final List<GridEntriesProcessor> domainProcessors = new ArrayList<>();
		for (Map.Entry<String, Function<Integer, GridEntriesProcessor>> entry : processors.entrySet()) {
			final GridEntriesProcessor sample = sampleProcessor(entry.getKey(), entry.getValue());
			if (sample != null) {
				domainProcessors.add(sample);
			}
		}
		SealedIndexLifecycleUtil.detachAllDomainsThenCloseBptree(domainProcessors, sealedBPTreeService);
		if (sealedBitmapService != null) {
			sealedBitmapService.close();
		}
	}

	/**
	 * DROP INDEX: detach query sealed refs, then unmap and delete sealed {@code .sbpt}/{@code .sbm}.
	 */
	public void purgeIndexArtifacts(String domainType, String catalogIndexName, List<String> columns) {
		if (sealedRoot == null || domainType == null || domainType.isBlank()) {
			return;
		}
		final Function<Integer, GridEntriesProcessor> byShard = processors.get(domainType);
		final GridEntriesProcessor sample = sampleProcessor(domainType, byShard);
		SealedIndexLifecycleUtil.detachQuerySealedIndexes(
				sample == null ? null : sample.compositeIndex());
		final Set<Integer> known = registeredShards.get(domainType);
		SealedIndexPurgeUtil.purge(
				sealedRoot,
				domainType,
				catalogIndexName,
				columns,
				sealedBPTreeService,
				sealedBitmapService,
				known);
	}

	/**
	 * DROP TABLE / domain retire: unmap readers and delete sealed {@code .gmap}/{@code .sbpt}/{@code .sbm}
	 * for {@code domainType} so recreate does not rehydrate stale artifacts.
	 */
	public void deleteDomain(String domainType) {
		if (sealedRoot == null || domainType == null || domainType.isEmpty()) {
			return;
		}
		final List<String> readerKeys = new ArrayList<>();
		for (String key : readers.keySet()) {
			if (OpLogStreamKeyUtil.startsWithDomain(key, domainType)) {
				readerKeys.add(key);
			}
		}
		for (String key : readerKeys) {
			SealedDumpMergeUtil.closeQuietly(readers.remove(key));
		}
		final Set<Integer> shards = registeredShards.remove(domainType);
		final Function<Integer, GridEntriesProcessor> byShard = processors.get(domainType);
		if (shards != null) {
			for (Integer shard : shards) {
				if (shard != null) {
					try {
						SealedIndexLifecycleUtil.detachAndReleaseShard(
								byShard, sealedBPTreeService, domainType, shard);
					} catch (IOException e) {
						throw new IllegalStateException(
								"sealed release before deleteDomain failed " + OpLogStreamKeyUtil.format(domainType, shard), e);
					}
				}
			}
		}
		processors.remove(domainType);
		final String filePrefix = SealedShardPack.domainHex(domainType) + "_";
		if (!Files.isDirectory(sealedRoot)) {
			return;
		}
		try (DirectoryStream<Path> files = Files.newDirectoryStream(sealedRoot, filePrefix + "*")) {
			for (Path file : files) {
				GridFs.deleteQuietly(file);
			}
		} catch (IOException ignored) {
			// best-effort purge; recreate must not fail closed on leftover delete races
		}
	}

	private void dumpIndexes(
			String domainType,
			int shard,
			int shardCount,
			GridEntriesProcessor processor,
			int mergeRowCount,
			LinkedHashMap<SealedDumpMergeUtil.BytesKey, byte[]> merged
	) throws IOException {
		if (processor.compositeIndex() == null) {
			return;
		}
		final TableSchema schema = processor.compositeIndex().tableSchema();
		// No early releaseShardSealedOnly — dumpIndex install-before-drop keeps prior∨next open.
		try {
			if (sealedBPTreeService != null) {
				processor.compositeIndex().forEachSingleColumnBPTree((property, tree) -> {
					final List<SealedBPTreeWriter.Entry> indexEntries =
							SealedDumpMergeUtil.singleColumnSealedEntries(
									merged, schema, property, shard, shardCount);
					try {
						dumpBpTreeIndexOrPreserve(
								domainType, shard, property, indexEntries, mergeRowCount,
								reader -> processor.compositeIndex().installSealedFallback(property, reader));
					} catch (IOException failure) {
						throw new SealedIndexDumpException(failure);
					}
				});
				processor.compositeIndex().forEachCompositeBPTree((columns, tree) -> {
					final String sealedName = SealedCompositeIndexNames.of(columns);
					final List<SealedBPTreeWriter.Entry> indexEntries =
							SealedDumpMergeUtil.compositeSealedEntries(
									merged, schema, columns, shard, shardCount);
					try {
						dumpBpTreeIndexOrPreserve(
								domainType, shard, sealedName, indexEntries, mergeRowCount,
								reader -> processor.compositeIndex()
										.installSealedCompositeFallback(columns, reader));
					} catch (IOException failure) {
						throw new SealedIndexDumpException(failure);
					}
				});
			}
			if (sealedBitmapService != null) {
				processor.compositeIndex().forEachSingleColumnBitmap((property, bitmap) -> {
					final GridBitmapIndex shardBitmap =
							new GridBitmapIndex(bitmap.getIndexName(), bitmap.getBitmapSize());
					bitmap.forEachEntry((indexKey, rowKey) -> {
						if (Math.abs(ArrayUtil.fastHash(rowKey) % shardCount) == shard) {
							shardBitmap.insert(new SingleTreeKey(indexKey), new IndexPointerRef(0L, rowKey));
						}
					});
					try {
						sealedBitmapService.dumpIndex(domainType, shard, property, shardBitmap);
						final GridBitmapIndex sealed =
								sealedBitmapService.openIfPresent(domainType, shard, property);
						if (sealed != null) {
							processor.compositeIndex().installSealedBitmapFallback(property, shard, sealed);
						}
					} catch (IOException failure) {
						throw new SealedIndexDumpException(failure);
					}
				});
			}
		} catch (SealedIndexDumpException failure) {
			throw (IOException) failure.getCause();
		}
	}

	/**
	 * Write {@code .sbpt} from dump entries. Never atomically replace an authoritative
	 * prior file with an empty shell when the merge map was non-empty (V10).
	 */
	private void dumpBpTreeIndexOrPreserve(
			String domainType,
			int shard,
			String indexName,
			List<SealedBPTreeWriter.Entry> indexEntries,
			int mergeRowCount,
			Consumer<SealedBPTreeReader> install
	) throws IOException {
		final Path idxFile = sealedBPTreeService.indexFile(domainType, shard, indexName);
		final boolean priorAuthoritative =
				SealedLazyIndexRebuildUtil.isAuthoritativeSealedIndexFile(idxFile);
		if (indexEntries == null || indexEntries.isEmpty()) {
			if (mergeRowCount > 0 && priorAuthoritative) {
				// Keep prior good accelerator — empty dump must not stamp over it.
				return;
			}
			if (mergeRowCount > 0 && !priorAuthoritative) {
				throw new IOException(
						"seal dump produced empty .sbpt for non-empty merge domain="
								+ domainType + " shard=" + shard + " index=" + indexName
								+ " mergeRows=" + mergeRowCount);
			}
			// Empty merge / empty table: empty shell OK (DDL shape).
		}
		sealedBPTreeService.dumpIndex(domainType, shard, indexName, indexEntries, install);
	}

	private void bindSealedIndexes(String domainType, int shard, GridEntriesProcessor processor) throws IOException {
		if (processor == null || processor.compositeIndex() == null) {
			return;
		}
		try {
			if (sealedBPTreeService != null) {
				processor.compositeIndex().forEachSingleColumnBPTree((property, tree) -> {
					final Path idxFile = sealedBPTreeService.indexFile(domainType, shard, property);
					// Empty shell / truncated debris = absent — do not bind authoritative empty fallback.
					if (!SealedLazyIndexRebuildUtil.isAuthoritativeSealedIndexFile(idxFile)) {
						return;
					}
					try {
						processor.compositeIndex().installSealedFallback(property,
								sealedBPTreeService.open(domainType, shard, property));
					} catch (IOException failure) {
						throw new SealedIndexDumpException(failure);
					}
				});
				processor.compositeIndex().forEachCompositeBPTree((columns, tree) -> {
					final String sealedName = SealedCompositeIndexNames.of(columns);
					final Path idxFile = sealedBPTreeService.indexFile(domainType, shard, sealedName);
					if (!SealedLazyIndexRebuildUtil.isAuthoritativeSealedIndexFile(idxFile)) {
						return;
					}
					try {
						processor.compositeIndex().installSealedCompositeFallback(columns,
								sealedBPTreeService.open(domainType, shard, sealedName));
					} catch (IOException failure) {
						throw new SealedIndexDumpException(failure);
					}
				});
			}
			if (sealedBitmapService != null) {
				processor.compositeIndex().forEachSingleColumnBitmap((property, bitmap) -> {
					try {
						final GridBitmapIndex loaded =
								sealedBitmapService.openIfPresent(domainType, shard, property);
						if (loaded != null) {
							processor.compositeIndex().installSealedBitmapFallback(property, shard, loaded);
						}
					} catch (IOException failure) {
						throw new SealedIndexDumpException(failure);
					}
				});
			}
		} catch (SealedIndexDumpException failure) {
			throw (IOException) failure.getCause();
		}
	}

	/**
	 * Drop the live sealed reader for {@code domain#shard} before file rewrite/orphan delete.
	 * Fail-closed {@link SealedGridMapReader#closeForOverwrite} — overwrite path only.
	 */
	private void releaseReaderForOverwrite(String domainType, int shard, GridEntriesProcessor processor)
			throws IOException {
		final SealedGridMapReader previous = readers.remove(OpLogStreamKeyUtil.format(domainType, shard));
		if (processor != null) {
			processor.setSealedReader(null);
		}
		final BiConsumer<String, Integer> released = onSealedReaderReleased;
		if (released != null && domainType != null) {
			released.accept(domainType, shard);
		}
		if (previous != null) {
			previous.closeForOverwrite();
		}
	}

	private void swapReader(String domainType, int shard, GridEntriesProcessor processor,
	                        SealedGridMapReader reader) {
		final String streamKey = OpLogStreamKeyUtil.format(domainType, shard);
		final SealedGridMapReader previous;
		if (reader == null) {
			// ConcurrentHashMap disallows null values — empty seal clear after tombstone overlay.
			previous = readers.remove(streamKey);
		} else {
			previous = readers.put(streamKey, reader);
		}
		if (processor != null) {
			processor.setSealedReader(reader);
		}
		if (previous != null && previous != reader) {
			SealedDumpMergeUtil.closeQuietly(previous);
		}
	}

	private void deleteOrphanNodes(String domainType, int shard, List<SealedGridMapWriter.Kv> entries)
			throws IOException {
		final Set<Integer> liveNodes = new HashSet<>();
		for (SealedGridMapWriter.Kv entry : entries) {
			liveNodes.add(Math.abs(ArrayUtil.fastHash(entry.key)
					% SealedGridMapWriter.NODE_COUNT));
		}
		final String pattern = SealedShardPack.domainHex(domainType) + "_" + shard + "_n*.gmap";
		try (DirectoryStream<Path> files = Files.newDirectoryStream(sealedRoot, pattern)) {
			for (Path file : files) {
				final int node = SealedDumpMergeUtil.parseNode(file.getFileName().toString());
				if (!liveNodes.contains(node)) {
					GridFs.deleteIfExists(file);
				}
			}
		}
	}

	private Set<Integer> listShards(String domainType) {
		return SealedIndexPurgeUtil.discoverShards(sealedRoot, domainType);
	}

	/**
	 * First non-null processor for {@code domainType} (any registered/probed shard).
	 */
	private GridEntriesProcessor sampleProcessor(
			String domainType,
			Function<Integer, GridEntriesProcessor> byShard
	) {
		if (byShard == null) {
			return null;
		}
		final Set<Integer> known = registeredShards.get(domainType);
		if (known != null) {
			for (Integer shard : known) {
				if (shard == null) {
					continue;
				}
				final GridEntriesProcessor processor = byShard.apply(shard);
				if (processor != null) {
					return processor;
				}
			}
		}
		for (int shard = 0; shard < MAX_SHARD_PROBE; shard++) {
			final GridEntriesProcessor processor = byShard.apply(shard);
			if (processor != null) {
				return processor;
			}
		}
		return null;
	}

	private static final class SealedIndexDumpException extends RuntimeException {
		private SealedIndexDumpException(IOException cause) {
			super(cause);
		}
	}

	/** Bridges checked {@link IOException} out of {@link SealedReaderLifecycleGate#call}. */
	private static final class UncheckedSealedIo extends RuntimeException {
		private final IOException io;

		private UncheckedSealedIo(IOException io) {
			super(io);
			this.io = io;
		}
	}
}
