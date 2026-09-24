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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

import org.genfork.grid.fs.GridFs;
import org.genfork.grid.utils.ArrayUtil;
import org.genfork.grid.utils.ArrayVectors;

/**
 * Writes immutable sealed GridMap files without materializing payloads in one heap buffer.
 *
 * @author: GenCloud
 * @date: 2026/05
 * @since: 1.0
 */
public final class SealedGridMapWriter {
	public static final int MAGIC = 0x474D4150;
	public static final int VERSION_NODE = 2;
	public static final int NODE_COUNT = 16;
	public static final long MAX_FILE_BYTES = 1L << 30;
	private static final int IO_CHUNK_BYTES = 1 << 20;
	private static final int ENTRY_BYTES = 16;
	private static final int BUCKET_BYTES = 8;

	private SealedGridMapWriter() {
	}

	public static final class Kv {
		public final byte[] key;
		public final byte[] value;

		public Kv(byte[] key, byte[] value) {
			this.key = key;
			this.value = value == null ? new byte[0] : value;
		}
	}

	/** Writes one VERSION=2 file for each non-empty GridMap node. */
	public static void writeNodes(Path sealedRoot, String domainType, int shard, long watermark, List<Kv> entries)
			throws IOException {
		final List<Kv>[] nodes = newNodeBuckets();
		if (entries != null) {
			for (Kv kv : entries) {
				if (kv == null || kv.key == null) {
					continue;
				}
				addToNodeBucket(nodes, kv.key, kv.value);
			}
		}
		writeNodeBuckets(sealedRoot, domainType, shard, watermark, nodes);
	}

	/** Empty per-node buckets for streaming seal (avoids one full-shard {@code List} copy). */
	@SuppressWarnings("unchecked")
	public static List<Kv>[] newNodeBuckets() {
		return (List<Kv>[]) new List<?>[NODE_COUNT];
	}

	/** Route one KV into the node bucket list (streaming seal). */
	public static void addToNodeBucket(List<Kv>[] nodes, byte[] key, byte[] value) {
		if (nodes == null || key == null) {
			return;
		}
		final int node = Math.abs(ArrayUtil.fastHash(key) % NODE_COUNT);
		List<Kv> nodeEntries = nodes[node];
		if (nodeEntries == null) {
			nodeEntries = new ArrayList<>();
			nodes[node] = nodeEntries;
		}
		nodeEntries.add(new Kv(key, value));
	}

	/** Write pre-bucketed node lists (streaming seal path). */
	public static void writeNodeBuckets(
			Path sealedRoot,
			String domainType,
			int shard,
			long watermark,
			List<Kv>[] nodes
	) throws IOException {
		GridFs.createDirs(sealedRoot);
		if (nodes == null) {
			return;
		}
		final String safe = Integer.toHexString(domainType.hashCode());
		for (int node = 0; node < NODE_COUNT; node++) {
			final List<Kv> nodeEntries = nodes[node];
			if (nodeEntries == null || nodeEntries.isEmpty()) {
				continue;
			}
			final int capacity = nextPowerOfTwo(Math.max(16, nodeEntries.size() * 2));
			final List<Kv>[][] buckets = createBuckets(1, capacity);
			for (Kv kv : nodeEntries) {
				final int bucket = Math.abs(ArrayUtil.fastHash(kv.key) % capacity);
				addBucket(buckets, 0, bucket, kv);
			}
			final List<Kv> ordered = orderedEntries(buckets, 1, capacity);
			final Path target = sealedRoot.resolve(safe + "_" + shard + "_n" + node + ".gmap");
			final int nodeId = node;
			final int bucketCapacity = capacity;
			final List<Kv>[][] nodeBuckets = buckets;
			final List<Kv> nodeOrdered = ordered;
			GridFs.writeAtomic(target, temporary -> writeFile(
					temporary, VERSION_NODE, 1, nodeId, domainType, shard, watermark, bucketCapacity,
					nodeBuckets, nodeOrdered, true, true));
		}
	}

	private static void writeFile(Path file, int version, int nodeCount, int nodeId, String domainType,
	                              int shard, long watermark, int capacity, List<Kv>[][] buckets,
	                              List<Kv> ordered, boolean withNodeId, boolean withCrc) throws IOException {
		final byte[] domainBytes = domainType.getBytes(StandardCharsets.UTF_8);
		final long headerBytes = 32L + domainBytes.length + (withNodeId ? 4L : 0L);
		final long bucketTableBytes = (long) nodeCount * capacity * BUCKET_BYTES;
		final long entryTableBytes = (long) ordered.size() * ENTRY_BYTES;
		final long directoryBytes = headerBytes + bucketTableBytes + entryTableBytes;
		if (directoryBytes > MAX_FILE_BYTES || directoryBytes > Integer.MAX_VALUE) {
			failSize("sealed directory exceeds soft limit: " + directoryBytes);
		}
		long payloadBytes = 0L;
		for (Kv kv : ordered) {
			payloadBytes = Math.addExact(payloadBytes, (long) kv.key.length + kv.value.length);
		}
		final long fileBytes = Math.addExact(directoryBytes, Math.addExact(payloadBytes, withCrc ? 4L : 0L));
		if (fileBytes > MAX_FILE_BYTES) {
			failSize("sealed file exceeds 1 GiB soft limit: " + fileBytes);
		}

		try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
			writeHeader(channel, version, nodeCount, capacity, shard, watermark, domainBytes, nodeId, withNodeId);
			writeBuckets(channel, buckets, nodeCount, capacity);
			writeEntryTable(channel, ordered, directoryBytes);
			final CRC32 crc = withCrc ? new CRC32() : null;
			for (Kv kv : ordered) {
				writePayload(channel, kv.key, crc);
				writePayload(channel, kv.value, crc);
			}
			if (withCrc) {
				final ByteBuffer footer = nativeBuffer(4);
				footer.putInt((int) crc.getValue()).flip();
				writeFully(channel, footer);
			}
			channel.force(true);
		}
	}

	private static void writeHeader(FileChannel channel, int version, int nodeCount, int capacity, int shard,
	                                long watermark, byte[] domainBytes, int nodeId, boolean withNodeId)
			throws IOException {
		final ByteBuffer header = nativeBuffer(32 + domainBytes.length + (withNodeId ? 4 : 0));
		header.putInt(MAGIC);
		header.putInt(version);
		header.putInt(nodeCount);
		header.putInt(capacity);
		header.putInt(shard);
		header.putLong(watermark);
		header.putInt(domainBytes.length);
		header.put(domainBytes);
		if (withNodeId) {
			header.putInt(nodeId);
		}
		header.flip();
		writeFully(channel, header);
	}

	private static void writeBuckets(FileChannel channel, List<Kv>[][] buckets, int nodeCount, int capacity)
			throws IOException {
		final ByteBuffer buffer = nativeBuffer(Math.min(IO_CHUNK_BYTES, capacity * BUCKET_BYTES));
		int entryCursor = 0;
		for (int node = 0; node < nodeCount; node++) {
			for (int bucket = 0; bucket < capacity; bucket++) {
				if (buffer.remaining() < BUCKET_BYTES) {
					buffer.flip();
					writeFully(channel, buffer);
					buffer.clear();
				}
				final List<Kv> list = buckets[node][bucket];
				final int count = list == null ? 0 : list.size();
				buffer.putInt(entryCursor).putInt(count);
				entryCursor += count;
			}
		}
		buffer.flip();
		writeFully(channel, buffer);
	}

	private static void writeEntryTable(FileChannel channel, List<Kv> ordered, long payloadOffset)
			throws IOException {
		final ByteBuffer buffer = nativeBuffer(IO_CHUNK_BYTES);
		long offset = payloadOffset;
		for (Kv kv : ordered) {
			if (buffer.remaining() < ENTRY_BYTES) {
				buffer.flip();
				writeFully(channel, buffer);
				buffer.clear();
			}
			buffer.putLong(offset).putInt(kv.key.length).putInt(kv.value.length);
			offset += (long) kv.key.length + kv.value.length;
		}
		buffer.flip();
		writeFully(channel, buffer);
	}

	private static void writePayload(FileChannel channel, byte[] bytes, CRC32 crc) throws IOException {
		ArrayVectors.updateCrc32(crc, bytes);
		int offset = 0;
		while (offset < bytes.length) {
			final int length = Math.min(IO_CHUNK_BYTES, bytes.length - offset);
			writeFully(channel, ByteBuffer.wrap(bytes, offset, length));
			offset += length;
		}
	}

	private static void writeFully(FileChannel channel, ByteBuffer buffer) throws IOException {
		while (buffer.hasRemaining()) {
			channel.write(buffer);
		}
	}

	private static ByteBuffer nativeBuffer(int bytes) {
		return ByteBuffer.allocate(bytes).order(ByteOrder.nativeOrder());
	}

	private static void failSize(String message) throws IOException {
		SealedMetrics.SEAL_FAIL_SIZE.incrementAndGet();
		throw new IOException(message);
	}

	@SuppressWarnings("unchecked")
	private static List<Kv>[][] createBuckets(int nodes, int capacity) {
		return (List<Kv>[][]) new List<?>[nodes][capacity];
	}

	private static void addBucket(List<Kv>[][] buckets, int node, int bucket, Kv kv) {
		List<Kv> list = buckets[node][bucket];
		if (list == null) {
			list = new ArrayList<>(2);
			buckets[node][bucket] = list;
		}
		list.add(kv);
	}

	private static List<Kv> orderedEntries(List<Kv>[][] buckets, int nodes, int capacity) {
		final List<Kv> ordered = new ArrayList<>();
		for (int node = 0; node < nodes; node++) {
			for (int bucket = 0; bucket < capacity; bucket++) {
				final List<Kv> list = buckets[node][bucket];
				if (list != null) {
					ordered.addAll(list);
				}
			}
		}
		return ordered;
	}

	private static int nextPowerOfTwo(int value) throws IOException {
		if (value <= 1) {
			return 1;
		}
		if (value > 1 << 26) {
			failSize("sealed bucket capacity exceeds directory limit: " + value);
		}
		return Integer.highestOneBit(value - 1) << 1;
	}
}