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

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.genfork.grid.fs.GridFs;
import org.genfork.grid.mem.index.IndexPointerRef;
import org.genfork.grid.mem.index.bitmap.GridBitmapIndex;
import org.genfork.grid.mem.index.btree.SingleTreeKey;
import org.genfork.grid.mem.stage.GridEntriesProcessor;
import org.genfork.grid.replication.ReplicationNodeState;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.log.OpLog;
import org.genfork.grid.replication.util.OpLogArchiveUtil;
import org.genfork.grid.utils.ArrayUtil;

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
	private final Path sealedRoot;
	/** Non-null when {@code grid.durability.oplog-archive.enabled}; else skip archive. */
	private final Path opLogArchiveRoot;
	private final SealedBPTreeService sealedBPTreeService;
	private final SealedBitmapService sealedBitmapService;
	private final Map<String, SealedGridMapReader> readers = new ConcurrentHashMap<>();
	private final Map<String, Function<Integer, GridEntriesProcessor>> processors = new ConcurrentHashMap<>();
	private final Map<String, Set<Integer>> registeredShards = new ConcurrentHashMap<>();

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
	/** OpLog read chunk when merging into sealed dump (same order as hydrate). */
	private static final int SEAL_DUMP_OPLOG_BATCH = 2_048;

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

	public int dumpDomain(String domainType, OpLog opLog, ReplicationNodeState nodeState) {
		if (sealedRoot == null || domainType == null) {
			return 0;
		}
		final Function<Integer, GridEntriesProcessor> byShard = processors.get(domainType);
		if (byShard == null) {
			return 0;
		}
		final Set<Integer> shards = new LinkedHashSet<>();
		final Set<Integer> bound = registeredShards.get(domainType);
		if (bound != null) {
			shards.addAll(bound);
		}
		if (opLog != null) {
			for (String streamKey : opLog.streamKeys()) {
				if (streamKey.startsWith(domainType + "#")) {
					shards.add(parseShard(streamKey));
				}
			}
		}

		int rows = 0;
		for (Integer shard : shards) {
			final GridEntriesProcessor processor = byShard.apply(shard);
			if (processor == null) {
				continue;
			}
			final long watermark = Math.max(
					nodeState == null ? 0L : nodeState.appliedWatermark(domainType, shard),
					opLog == null ? 0L : opLog.lastSeq(domainType, shard));
			// Sealed ∪ OpLog-delta ∪ RAM: eviction may drop cold keys from the working set before
			// first seal; OpLog recovers them so truncate after dump cannot lose rows.
			final LinkedHashMap<BytesKey, byte[]> merged = new LinkedHashMap<>();
			final SealedGridMapReader prior = reader(domainType, shard);
			final long priorWm = prior == null ? 0L : prior.watermark();
			if (prior != null) {
				prior.forEachLive((key, value) -> merged.put(new BytesKey(key), value));
			}
			mergeOpLogDeltaInto(merged, domainType, shard, priorWm, opLog);
			processor.forEachCommitted((key, value) -> merged.put(new BytesKey(key), value));
			if (merged.isEmpty()) {
				continue;
			}
			final List<SealedGridMapWriter.Kv>[] nodeBuckets = SealedGridMapWriter.newNodeBuckets();
			for (Map.Entry<BytesKey, byte[]> e : merged.entrySet()) {
				SealedGridMapWriter.addToNodeBucket(nodeBuckets, e.getKey().bytes(), e.getValue());
			}
			final int rowCount = merged.size();
			try {
				// Unmap/close live reader before rewrite — Windows denies REPLACE on mmap'd *.gmap (G-00001).
				releaseReader(domainType, shard, processor);
				SealedGridMapWriter.writeNodeBuckets(sealedRoot, domainType, shard, watermark, nodeBuckets);
				// RAM BPTree may be WS-capped; rebuild index postings from sealed∪OpLog∪RAM merge.
				reindexMergedForSealDump(processor, merged);
				dumpIndexes(domainType, shard, shards.size(), processor);
				final List<SealedGridMapWriter.Kv> flatForOrphans = flattenNodeBuckets(nodeBuckets);
				deleteOrphanNodes(domainType, shard, flatForOrphans);
				final SealedGridMapReader reader = SealedGridMapReader.openShard(sealedRoot, domainType, shard);
				if (reader == null) {
					throw new IOException("node seal produced no files for " + domainType + "#" + shard);
				}
				swapReader(domainType, shard, processor, reader);
				if (opLog != null && watermark > 0L) {
					// Archive sync on seal thread (already off Netty EL); fail-closed before truncate.
					OpLogArchiveUtil.archiveBeforeTruncate(
							opLog, opLogArchiveRoot, domainType, shard, watermark);
					opLog.truncateTo(domainType, shard, watermark);
				}
				rows += rowCount;
			} catch (IOException e) {
				throw new IllegalStateException("sealed dump failed " + domainType + "#" + shard, e);
			}
		}
		return rows;
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
		try {
			for (Integer shard : listShards(domainType)) {
				final GridEntriesProcessor processor = byShard.apply(shard);
				if (processor == null) {
					continue;
				}
				final SealedGridMapReader reader = SealedGridMapReader.openShard(sealedRoot, domainType, shard);
				if (reader == null) {
					continue;
				}
				swapReader(domainType, shard, processor, reader);
				bindSealedIndexes(domainType, shard, processor);
				reader.forEachLive((key, value) -> processor.installCommitted(key, value, false));
				watermarks.put(shard, reader.watermark());
				registeredShards.computeIfAbsent(domainType, ignored -> ConcurrentHashMap.newKeySet()).add(shard);
			}
		} catch (IOException e) {
			throw new IllegalStateException("sealed load failed for " + domainType, e);
		}
		return watermarks;
	}

	/** LAZY hydrate: bind a shard reader without preloading its payload. */
	public long openShardLazy(String domainType, int shard) {
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
			throw new IllegalStateException("sealed open lazy failed for " + domainType + "#" + shard, e);
		}
	}

	public SealedGridMapReader reader(String domainType, int shard) {
		return readers.get(readerKey(domainType, shard));
	}

	/**
	 * True when at least one sealed {@code .sbpt} exists for the shard (cold index path).
	 */
	public boolean hasAnySealedIndex(String domainType, int shard) {
		if (sealedRoot == null || domainType == null || !Files.isDirectory(sealedRoot)) {
			return false;
		}
		final String prefix = SealedShardPack.domainHex(domainType) + "_" + shard + "_idx_";
		try (DirectoryStream<Path> stream = GridFs.newDirectoryStream(sealedRoot, prefix + "*.sbpt")) {
			return stream.iterator().hasNext();
		} catch (IOException ignored) {
			return false;
		}
	}

	/**
	 * List sealed {@code .gmap} + {@code .sbpt} artifacts for peer ship/repair (same domain/shard).
	 */
	public List<Path> listShardArtifacts(String domainType, int shard) throws IOException {
		if (sealedRoot == null) {
			return List.of();
		}
		return SealedShardPack.listShardFiles(sealedRoot, domainType, shard);
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

	/** Sealed root path when configured; {@code null} when durability sealed is unbound. */
	public Path sealedRootOrNull() {
		return sealedRoot;
	}

	/**
	 * Unpack peer-shipped sealed artifacts into this node's sealed root.
	 * After unpack, call {@link #openShardLazy} so peers can open {@link SealedBPTreeReader} via bind.
	 */
	public List<Path> unpackShardArtifacts(byte[] packed) throws IOException {
		if (sealedRoot == null) {
			throw new IllegalStateException("sealed root not configured");
		}
		return SealedShardPack.unpack(packed, sealedRoot);
	}

	public SealedBPTreeService sealedBPTreeService() {
		return sealedBPTreeService;
	}

	public SealedBitmapService sealedBitmapService() {
		return sealedBitmapService;
	}

	public void closeAll() {
		for (SealedGridMapReader reader : readers.values()) {
			closeQuietly(reader);
		}
		readers.clear();
		if (sealedBPTreeService != null) {
			sealedBPTreeService.closeAll();
		}
		if (sealedBitmapService != null) {
			sealedBitmapService.close();
		}
	}

	/**
	 * DROP TABLE / domain retire: unmap readers and delete sealed {@code .gmap}/{@code .sbpt}/{@code .sbm}
	 * for {@code domainType} so recreate does not rehydrate stale artifacts.
	 */
	public void deleteDomain(String domainType) {
		if (sealedRoot == null || domainType == null || domainType.isEmpty()) {
			return;
		}
		final String keyPrefix = domainType + "#";
		final List<String> readerKeys = new ArrayList<>();
		for (String key : readers.keySet()) {
			if (key.startsWith(keyPrefix)) {
				readerKeys.add(key);
			}
		}
		for (String key : readerKeys) {
			closeQuietly(readers.remove(key));
		}
		final Set<Integer> shards = registeredShards.remove(domainType);
		if (shards != null && sealedBPTreeService != null) {
			for (Integer shard : shards) {
				if (shard != null) {
					sealedBPTreeService.releaseShard(domainType, shard.intValue());
				}
			}
		}
		processors.remove(domainType);
		final String filePrefix = safe(domainType) + "_";
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

	private void dumpIndexes(String domainType, int shard, int shardCount, GridEntriesProcessor processor)
			throws IOException {
		if (processor.compositeIndex() == null) {
			return;
		}
		// Drop query-path refs, then unmap .sbpt before atomic replace (Windows mmap lock).
		processor.compositeIndex().clearSealedIndexes();
		if (sealedBPTreeService != null) {
			sealedBPTreeService.releaseShard(domainType, shard);
		}
		try {
			if (sealedBPTreeService != null) {
				processor.compositeIndex().forEachSingleColumnBPTree((property, tree) -> {
					final List<SealedBPTreeWriter.Entry> indexEntries = new ArrayList<>();
					tree.forEachLeafEntry((indexKey, rowKey) -> {
						if (Math.abs(ArrayUtil.fastHash(rowKey) % shardCount) == shard) {
							indexEntries.add(new SealedBPTreeWriter.Entry(indexKey, rowKey));
						}
					});
					try {
						sealedBPTreeService.dumpIndex(domainType, shard, property, indexEntries);
						processor.compositeIndex().installSealedFallback(property,
								sealedBPTreeService.open(domainType, shard, property));
					} catch (IOException failure) {
						throw new SealedIndexDumpException(failure);
					}
				});
				processor.compositeIndex().forEachCompositeBPTree((columns, tree) -> {
					final String sealedName = SealedCompositeIndexNames.of(columns);
					final List<SealedBPTreeWriter.Entry> indexEntries = new ArrayList<>();
					tree.forEachLeafEntry((indexKey, rowKey) -> {
						if (Math.abs(ArrayUtil.fastHash(rowKey) % shardCount) == shard) {
							indexEntries.add(new SealedBPTreeWriter.Entry(
									SealedCompositeIndexKey.encode(indexKey), rowKey));
						}
					});
					try {
						sealedBPTreeService.dumpIndex(domainType, shard, sealedName, indexEntries);
						processor.compositeIndex().installSealedCompositeFallback(columns,
								sealedBPTreeService.open(domainType, shard, sealedName));
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

	private void bindSealedIndexes(String domainType, int shard, GridEntriesProcessor processor) throws IOException {
		if (processor == null || processor.compositeIndex() == null) {
			return;
		}
		try {
			if (sealedBPTreeService != null) {
				processor.compositeIndex().forEachSingleColumnBPTree((property, tree) -> {
					if (!Files.isRegularFile(sealedBPTreeService.indexFile(domainType, shard, property))) {
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
					if (!Files.isRegularFile(sealedBPTreeService.indexFile(domainType, shard, sealedName))) {
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
	 * Holds no map lock across SQL — only unmap/close (see {@link SealedGridMapReader#close}).
	 */
	private void releaseReader(String domainType, int shard, GridEntriesProcessor processor) {
		final SealedGridMapReader previous = readers.remove(readerKey(domainType, shard));
		if (processor != null) {
			processor.setSealedReader(null);
		}
		if (previous != null) {
			closeQuietly(previous);
		}
	}

	private void swapReader(String domainType, int shard, GridEntriesProcessor processor,
	                        SealedGridMapReader reader) {
		final SealedGridMapReader previous = readers.put(readerKey(domainType, shard), reader);
		if (processor != null) {
			processor.setSealedReader(reader);
		}
		if (previous != null && previous != reader) {
			closeQuietly(previous);
		}
	}

	private static List<SealedGridMapWriter.Kv> flattenNodeBuckets(List<SealedGridMapWriter.Kv>[] nodes) {
		final List<SealedGridMapWriter.Kv> flat = new ArrayList<>();
		if (nodes == null) {
			return flat;
		}
		for (List<SealedGridMapWriter.Kv> bucket : nodes) {
			if (bucket != null) {
				flat.addAll(bucket);
			}
		}
		return flat;
	}

	/**
	 * Replay OpLog after sealed watermark into the dump merge map (UPSERT/DELETE only).
	 */
	private static void mergeOpLogDeltaInto(
			LinkedHashMap<BytesKey, byte[]> merged,
			String domainType,
			int shard,
			long priorWm,
			OpLog opLog
	) {
		if (merged == null || opLog == null || domainType == null) {
			return;
		}
		long from = Math.max(1L, priorWm + 1L);
		while (true) {
			final List<ReplicationOp> batch = opLog.readFrom(domainType, shard, from, SEAL_DUMP_OPLOG_BATCH);
			if (batch.isEmpty()) {
				break;
			}
			for (ReplicationOp op : batch) {
				if (op.type() == ReplicationOpType.UPSERT) {
					merged.put(new BytesKey(op.key()), op.value());
				} else if (op.type() == ReplicationOpType.DELETE) {
					merged.remove(new BytesKey(op.key()));
				}
				from = op.opSeq() + 1L;
			}
			if (batch.size() < SEAL_DUMP_OPLOG_BATCH) {
				break;
			}
		}
	}

	/**
	 * Fill RAM secondary/PK trees from the dump merge so {@link #dumpIndexes} writes complete {@code .sbpt}.
	 */
	private static void reindexMergedForSealDump(
			GridEntriesProcessor processor,
			LinkedHashMap<BytesKey, byte[]> merged
	) {
		if (processor == null || merged == null || merged.isEmpty() || processor.compositeIndex() == null) {
			return;
		}
		final List<GridEntriesProcessor.AddEntry> chunk = new ArrayList<>(SEAL_DUMP_OPLOG_BATCH);
		for (Map.Entry<BytesKey, byte[]> e : merged.entrySet()) {
			final byte[] value = e.getValue();
			if (value == null) {
				continue;
			}
			chunk.add(new GridEntriesProcessor.AddEntry(null, e.getKey().bytes(), value));
			if (chunk.size() >= SEAL_DUMP_OPLOG_BATCH) {
				processor.indexDeltaNow(chunk);
				chunk.clear();
			}
		}
		if (!chunk.isEmpty()) {
			processor.indexDeltaNow(chunk);
		}
	}

	/** Equality by wire key bytes for sealed∪RAM merge. */
	private record BytesKey(byte[] bytes) {
		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof BytesKey other)) {
				return false;
			}
			return Arrays.equals(bytes, other.bytes);
		}

		@Override
		public int hashCode() {
			return Arrays.hashCode(bytes);
		}
	}

	private void deleteOrphanNodes(String domainType, int shard, List<SealedGridMapWriter.Kv> entries)
			throws IOException {
		final Set<Integer> liveNodes = new HashSet<>();
		for (SealedGridMapWriter.Kv entry : entries) {
			liveNodes.add(Math.abs(ArrayUtil.fastHash(entry.key)
					% SealedGridMapWriter.NODE_COUNT));
		}
		final String pattern = safe(domainType) + "_" + shard + "_n*.gmap";
		try (DirectoryStream<Path> files = Files.newDirectoryStream(sealedRoot, pattern)) {
			for (Path file : files) {
				final int node = parseNode(file.getFileName().toString());
				if (!liveNodes.contains(node)) {
					GridFs.deleteIfExists(file);
				}
			}
		}
	}

	private Set<Integer> listShards(String domainType) throws IOException {
		final Set<Integer> shards = new LinkedHashSet<>();
		final String prefix = safe(domainType) + "_";
		try (DirectoryStream<Path> files = Files.newDirectoryStream(sealedRoot, prefix + "*_n*.gmap")) {
			for (Path file : files) {
				final String name = file.getFileName().toString();
				final int suffix = name.indexOf("_n", prefix.length());
				if (suffix < 0) {
					continue;
				}
				final int end = suffix;
				if (end <= prefix.length()) {
					continue;
				}
				try {
					shards.add(Integer.parseInt(name.substring(prefix.length(), end)));
				} catch (NumberFormatException ignored) {
					// Ignore unrelated files sharing the hash prefix.
				}
			}
		}
		return shards;
	}

	private static int parseNode(String name) throws IOException {
		final int marker = name.lastIndexOf("_n");
		final int extension = name.lastIndexOf(".gmap");
		if (marker < 0 || extension <= marker + 2) {
			throw new IOException("invalid sealed node filename: " + name);
		}
		try {
			return Integer.parseInt(name.substring(marker + 2, extension));
		} catch (NumberFormatException e) {
			throw new IOException("invalid sealed node filename: " + name, e);
		}
	}

	private static int parseShard(String streamKey) {
		return Integer.parseInt(streamKey.substring(streamKey.lastIndexOf('#') + 1));
	}

	private static String readerKey(String domainType, int shard) {
		return domainType + "#" + shard;
	}

	private static String safe(String domainType) {
		return SealedShardPack.domainHex(domainType);
	}

	private static final class SealedIndexDumpException extends RuntimeException {
		private SealedIndexDumpException(IOException cause) {
			super(cause);
		}
	}

	private static void closeQuietly(SealedGridMapReader reader) {
		try {
			reader.close();
		} catch (IOException ignored) {
			// Reader replacement remains authoritative; close is best effort.
		}
	}
}
