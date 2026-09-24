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
package org.genfork.grid.replication.log;

import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.OpLogSegment;
import org.genfork.grid.replication.codec.ReplicationOp;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Append-only op log keyed by (domain, shard). Always on-disk under {@code dataDir/oplog/}.
 * Heap keeps only {@code seq -> fileOffset} index; payloads live in MMF.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public class OpLog implements AutoCloseable {
	private final Map<String, NavigableMap<Long, Long>> offsetIndex = new ConcurrentHashMap<>();
	private final Map<String, FileDurableOpStore> stores = new ConcurrentHashMap<>();
	private final Path dataDir;
	private final boolean fsync;
	private final AtomicBoolean closed = new AtomicBoolean(false);

	public OpLog(Path dataDir, boolean fsync) {
		this.dataDir = Objects.requireNonNull(dataDir, "dataDir");
		this.fsync = fsync;
		try {
			loadAll();
		} catch (IOException e) {
			throw new IllegalStateException("Failed to load OpLog from " + dataDir, e);
		}
	}

	private static String key(String domainType, int shard) {
		return domainType + "#" + shard;
	}

	private static String streamId(String domainType, int shard) {
		return Integer.toHexString(domainType.hashCode()) + "_" + shard;
	}

	/** Loaded {@code domain#shard} keys (for watermark seed). */
	public Set<String> streamKeys() {
		return Set.copyOf(offsetIndex.keySet());
	}

	/**
	 * Appends an op. {@code opSeq} is the global ORCHID sequence projected onto this
	 * {@code (domain, shard)} stream — numbers need not be dense (other shards consume seqs).
	 */
	public void append(ReplicationOp op) {
		final String k = key(op.domainType(), op.shard());
		final NavigableMap<Long, Long> offsets = offsetIndex.computeIfAbsent(k, ignored -> new ConcurrentSkipListMap<>());
		if (offsets.containsKey(op.opSeq())) {
			return;
		}
		final Long last = offsets.isEmpty() ? null : offsets.lastKey();
		if (last != null && op.opSeq() <= last) {
			throw new IllegalStateException(
					"Out-of-order append: last=" + last + " got=" + op.opSeq() + " stream=" + k);
		}
		try {
			final long offset = storeFor(op.domainType(), op.shard()).append(op);
			offsets.put(op.opSeq(), offset);
		} catch (IOException e) {
			throw new IllegalStateException("OpLog append failed", e);
		}
	}

	/**
	 * Append without fsync. Pair with {@link #force(String, int)} after a contiguous unit so
	 * map/orchid apply visibility is not separated from durable OpLog by a long propose pipeline.
	 */
	public void appendDeferred(ReplicationOp op) {
		final String k = key(op.domainType(), op.shard());
		final NavigableMap<Long, Long> offsets = offsetIndex.computeIfAbsent(k, ignored -> new ConcurrentSkipListMap<>());
		if (offsets.containsKey(op.opSeq())) {
			return;
		}
		final Long last = offsets.isEmpty() ? null : offsets.lastKey();
		if (last != null && op.opSeq() <= last) {
			throw new IllegalStateException(
					"Out-of-order append: last=" + last + " got=" + op.opSeq() + " stream=" + k);
		}
		try {
			final long offset = storeFor(op.domainType(), op.shard()).appendDeferred(op);
			offsets.put(op.opSeq(), offset);
		} catch (IOException e) {
			throw new IllegalStateException("OpLog appendDeferred failed", e);
		}
	}

	/** Group fsync for deferred appends on one stream. */
	public void force(String domainType, int shard) {
		try {
			storeFor(domainType, shard).force();
		} catch (IOException e) {
			throw new IllegalStateException("OpLog force failed", e);
		}
	}

	/** Batch append to one stream with a single fsync (when enabled). */
	public void appendBatch(List<ReplicationOp> ops) {
		if (ops == null || ops.isEmpty()) {
			return;
		}
		final ReplicationOp first = ops.getFirst();
		final String k = key(first.domainType(), first.shard());
		final NavigableMap<Long, Long> offsets = offsetIndex.computeIfAbsent(k, ignored -> new ConcurrentSkipListMap<>());
		try {
			final long[] fileOff = storeFor(first.domainType(), first.shard()).appendBatch(ops);
			for (int i = 0; i < ops.size(); i++) {
				offsets.put(ops.get(i).opSeq(), fileOff[i]);
			}
		} catch (IOException e) {
			throw new IllegalStateException("OpLog appendBatch failed", e);
		}
	}

	public List<ReplicationOp> readFrom(String domainType, int shard, long fromSeqInclusive, int limit) {
		final NavigableMap<Long, Long> offsets = offsetIndex.get(key(domainType, shard));
		if (offsets == null || offsets.isEmpty()) {
			return List.of();
		}
		final List<ReplicationOp> result = new ArrayList<>();
		final FileDurableOpStore store;
		try {
			store = storeFor(domainType, shard);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		for (Map.Entry<Long, Long> e : offsets.tailMap(fromSeqInclusive, true).entrySet()) {
			result.add(store.readAt(e.getValue()));
			if (result.size() >= limit) {
				break;
			}
		}
		return result;
	}

	public OpLogSegment segmentFrom(String domainType, int shard, long fromSeqInclusive, int limit) {
		final List<ReplicationOp> ops = readFrom(domainType, shard, fromSeqInclusive, limit);
		if (ops.isEmpty()) {
			return new OpLogSegment(domainType, shard, fromSeqInclusive, fromSeqInclusive - 1, List.of(), 0L);
		}
		final long from = ops.getFirst().opSeq();
		final long to = ops.getLast().opSeq();
		return new OpLogSegment(domainType, shard, from, to, ops, OpLogCodec.segmentChecksum(ops));
	}

	public void truncateTo(String domainType, int shard, long inclusiveMaxSeq) {
		final String k = key(domainType, shard);
		final NavigableMap<Long, Long> offsets = offsetIndex.get(k);
		if (offsets != null) {
			offsets.headMap(inclusiveMaxSeq, true).clear();
		}
		try {
			storeFor(domainType, shard).setTruncatedThrough(inclusiveMaxSeq);
		} catch (IOException e) {
			throw new IllegalStateException("OpLog truncate failed", e);
		}
	}

	/**
	 * Durable truncate watermark for this stream (ops with {@code seq <=} this value are retired).
	 * Used by archive-before-truncate to choose {@code fromSeq}.
	 */
	public long truncatedThrough(String domainType, int shard) {
		try {
			return storeFor(domainType, shard).truncatedThrough();
		} catch (IOException e) {
			throw new IllegalStateException("OpLog truncatedThrough failed", e);
		}
	}

	public long lastSeq(String domainType, int shard) {
		final NavigableMap<Long, Long> offsets = offsetIndex.get(key(domainType, shard));
		if (offsets == null || offsets.isEmpty()) {
			return 0L;
		}
		return offsets.lastKey();
	}

	/** True if this stream already indexed {@code seq} (for ordered-append waiters). */
	public boolean containsSeq(String domainType, int shard, long seq) {
		final NavigableMap<Long, Long> offsets = offsetIndex.get(key(domainType, shard));
		return offsets != null && offsets.containsKey(seq);
	}

	public int size(String domainType, int shard) {
		final NavigableMap<Long, Long> offsets = offsetIndex.get(key(domainType, shard));
		return offsets == null ? 0 : offsets.size();
	}

	private FileDurableOpStore storeFor(String domainType, int shard) throws IOException {
		final String k = key(domainType, shard);
		FileDurableOpStore existing = stores.get(k);
		if (existing != null) {
			return existing;
		}
		synchronized (stores) {
			existing = stores.get(k);
			if (existing != null) {
				return existing;
			}
			final FileDurableOpStore created = new FileDurableOpStore(
					dataDir.resolve("oplog"),
					streamId(domainType, shard),
					fsync
			);
			stores.put(k, created);
			return created;
		}
	}

	private void loadAll() throws IOException {
		final Path oplogDir = dataDir.resolve("oplog");
		if (!java.nio.file.Files.exists(oplogDir)) {
			return;
		}
		try (var paths = java.nio.file.Files.list(oplogDir)) {
			paths.filter(p -> p.getFileName().toString().endsWith(".log"))
					.forEach(path -> {
						try {
							final String name = path.getFileName().toString().replace(".log", "");
							final FileDurableOpStore store = new FileDurableOpStore(oplogDir, name, fsync);
							for (FileDurableOpStore.Record rec : store.replayWithOffsets()) {
								final String k = key(rec.op().domainType(), rec.op().shard());
								stores.put(k, store);
								offsetIndex.computeIfAbsent(k, ignored -> new ConcurrentSkipListMap<>())
										.put(rec.op().opSeq(), rec.offset());
							}
						} catch (IOException e) {
							throw new IllegalStateException("Failed replaying " + path, e);
						}
					});
		}
	}

	@Override
	public void close() {
		if (!closed.compareAndSet(false, true)) {
			return;
		}
		for (FileDurableOpStore store : stores.values()) {
			try {
				store.close();
			} catch (IOException ignored) {
			}
		}
		stores.clear();
	}
}
