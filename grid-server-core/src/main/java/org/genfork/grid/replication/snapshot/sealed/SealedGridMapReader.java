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
import java.lang.reflect.Field;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

import com.google.common.annotations.VisibleForTesting;

import org.genfork.grid.fs.GridFs;
import org.genfork.grid.utils.ArrayUtil;
import org.genfork.grid.mem.LifecycleRwGate;
import org.genfork.grid.utils.UnsafeMemory;

/**
 * Read-only sealed GridMap with a fully mapped directory and <strong>windowed</strong> payload mmap.
 * <p>
 * Default payload path is bounded windowed mmap ({@link #WINDOW_BYTES}) with
 * {@link GridFs#unmap} / {@code invokeCleaner} on remap. Optional sticky full-payload mmap is
	 * allowed only when {@code payloadBytes <=} one {@link #WINDOW_BYTES} window (not 1 GiB).
 * Gets take {@link LifecycleRwGate#lockRead()} around directory/payload access so {@link #close()}
 * write-lock cannot unmap underfoot; sticky install is single-flight via {@link AtomicReference} CAS.
 *
 * @author: GenCloud
 * @date: 2026/05
 * @since: 1.0
 */
public final class SealedGridMapReader implements AutoCloseable {
	/** Default / test-overridable payload window size (bytes). */
	private static final int DEFAULT_WINDOW_BYTES = 64 * 1024 * 1024;
	public static int WINDOW_BYTES = DEFAULT_WINDOW_BYTES;
	/**
	 * Cap for optional sticky full-payload mmap — must stay ≪ former 1 GiB soft max;
	 * equals current {@link #WINDOW_BYTES} so large sealed files never pin full native VA.
	 */
	private static long stickyMaxBytes() {
		return WINDOW_BYTES;
	}
	private static final int BUCKET_BYTES = 8;
	private static final int ENTRY_BYTES = 16;
	private static final long ADDRESS_OFFSET = addressOffset();
	private static final Pattern NODE_FILE_PATTERN = Pattern.compile(".*_n(\\d+)\\.gmap$");
	private static final boolean METRICS_ENABLED =
			!"false".equalsIgnoreCase(System.getProperty("sealed.metrics", "true"));

	private final Path path;
	private final FileChannel channel;
	private final MappedByteBuffer directory;
	private final int version;
	private final int nodeCount;
	private final int nodeId;
	private final int capacity;
	private final int shard;
	private final long watermark;
	private final String domainType;
	private final int bucketTableOffset;
	private final int entryTableOffset;
	private final int entryCount;
	private final long payloadStart;
	private final long payloadEnd;
	/** Eager node pack; null when using {@link #nodePaths} lazy pack. */
	private final SealedGridMapReader[] nodeReaders;
	/** Lazy node-pack paths (directory mmap only on first miss per node). */
	private final Path[] nodePaths;
	private final AtomicReferenceArray<SealedGridMapReader> lazyNodes;
	private final LifecycleRwGate lifecycle = new LifecycleRwGate();
	private final AtomicInteger generation = new AtomicInteger();
	private final ConcurrentLinkedQueue<PayloadWindow> payloadWindowRegistry = new ConcurrentLinkedQueue<>();
	private final ThreadLocal<PayloadWindow> payloadWindows = ThreadLocal.withInitial(() -> {
		final PayloadWindow window = new PayloadWindow();
		payloadWindowRegistry.add(window);
		return window;
	});
	/** Sticky full-payload mapping when installed; null = windowed-only. */
	private final AtomicReference<StickyPayload> stickyPayload = new AtomicReference<>();
	/** Latches sticky-reject metric so large payloads do not flood the counter. */
	private final AtomicInteger stickyRejectLatched = new AtomicInteger();
	private volatile boolean closed;

	private SealedGridMapReader(Path path, FileChannel channel, MappedByteBuffer directory, int version,
	                            int nodeCount, int nodeId, int capacity, int shard, long watermark,
	                            String domainType, int bucketTableOffset, int entryTableOffset,
	                            int entryCount, long payloadStart, long payloadEnd) {
		this.path = path;
		this.channel = channel;
		this.directory = directory;
		this.version = version;
		this.nodeCount = nodeCount;
		this.nodeId = nodeId;
		this.capacity = capacity;
		this.shard = shard;
		this.watermark = watermark;
		this.domainType = domainType;
		this.bucketTableOffset = bucketTableOffset;
		this.entryTableOffset = entryTableOffset;
		this.entryCount = entryCount;
		this.payloadStart = payloadStart;
		this.payloadEnd = payloadEnd;
		this.nodeReaders = null;
		this.nodePaths = null;
		this.lazyNodes = null;
	}

	private SealedGridMapReader(Path sealedRoot, String domainType, int shard,
	                            SealedGridMapReader[] nodeReaders) {
		this.path = sealedRoot;
		this.channel = null;
		this.directory = null;
		this.version = SealedGridMapWriter.VERSION_NODE;
		this.nodeCount = SealedGridMapWriter.NODE_COUNT;
		this.nodeId = -1;
		this.capacity = 0;
		this.shard = shard;
		this.watermark = maxWatermark(nodeReaders);
		this.domainType = domainType;
		this.bucketTableOffset = 0;
		this.entryTableOffset = 0;
		this.entryCount = 0;
		this.payloadStart = 0L;
		this.payloadEnd = 0L;
		this.nodeReaders = nodeReaders;
		this.nodePaths = null;
		this.lazyNodes = null;
	}

	/** Lazy node-pack: paths only until first payload miss per node (hybrid RAM hits stay mmap-free). */
	private SealedGridMapReader(Path sealedRoot, String domainType, int shard, Path[] nodePaths,
	                            long watermarkHint) {
		this.path = sealedRoot;
		this.channel = null;
		this.directory = null;
		this.version = SealedGridMapWriter.VERSION_NODE;
		this.nodeCount = SealedGridMapWriter.NODE_COUNT;
		this.nodeId = -1;
		this.capacity = 0;
		this.shard = shard;
		this.watermark = watermarkHint;
		this.domainType = domainType;
		this.bucketTableOffset = 0;
		this.entryTableOffset = 0;
		this.entryCount = 0;
		this.payloadStart = 0L;
		this.payloadEnd = 0L;
		this.nodeReaders = null;
		this.nodePaths = nodePaths;
		this.lazyNodes = new AtomicReferenceArray<>(SealedGridMapWriter.NODE_COUNT);
	}

	/**
	 * Opens one physical VERSION=2 node GMAP file.
	 */
	@VisibleForTesting
	public static SealedGridMapReader open(Path path) throws IOException {
		return openPhysicalFile(path);
	}

	private static SealedGridMapReader openPhysicalFile(Path path) throws IOException {
		Objects.requireNonNull(path, "path");
		final FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
		MappedByteBuffer initialDirectory = null;
		try {
			final long fileSize = channel.size();
			if (fileSize < 32L) {
				throw new IOException("sealed file too small: " + path);
			}
			final ByteBuffer fixed = readAt(channel, 0L, 32);
			final int magic = fixed.getInt();
			if (magic != SealedGridMapWriter.MAGIC) {
				throw new IOException("bad GMAP magic: " + path);
			}
			final int version = fixed.getInt();
			if (version != SealedGridMapWriter.VERSION_NODE) {
				throw new IOException("unsupported GMAP version " + version + " (node-pack VERSION=2 only): " + path);
			}
			final int nodeCount = fixed.getInt();
			final int capacity = fixed.getInt();
			final int shard = fixed.getInt();
			final long watermark = fixed.getLong();
			final int domainLength = fixed.getInt();
			if (domainLength < 0 || domainLength > 1 << 20 || 32L + domainLength > fileSize) {
				throw new IOException("invalid GMAP domain length: " + domainLength);
			}
			if (capacity <= 0 || (capacity & (capacity - 1)) != 0) {
				throw new IOException("invalid GMAP capacity: " + capacity);
			}
			if (nodeCount != 1) {
				throw new IOException("invalid VERSION=2 node count: " + nodeCount);
			}
			final ByteBuffer variable = readAt(channel, 32L, domainLength + 4);
			final byte[] domainBytes = new byte[domainLength];
			variable.get(domainBytes);
			final String domainType = new String(domainBytes, StandardCharsets.UTF_8);
			final int nodeId = variable.getInt();
			if (nodeId < 0 || nodeId >= SealedGridMapWriter.NODE_COUNT) {
				throw new IOException("invalid GMAP node id: " + nodeId);
			}
			final long headerBytes = 32L + domainLength + 4L;
			final long bucketBytes = Math.multiplyExact(Math.multiplyExact((long) nodeCount, capacity), BUCKET_BYTES);
			final long minimumDirectory = Math.addExact(headerBytes, bucketBytes);
			if (minimumDirectory > SealedGridMapWriter.MAX_FILE_BYTES || minimumDirectory > Integer.MAX_VALUE
					|| minimumDirectory > fileSize) {
				throw new IOException("invalid or oversized GMAP bucket directory: " + minimumDirectory);
			}
			initialDirectory = GridFs.mapReadOnly(channel, 0L, minimumDirectory);
			initialDirectory.order(ByteOrder.nativeOrder());
			final int bucketTableOffset = Math.toIntExact(headerBytes);
			int entryCount = 0;
			final long slots = (long) nodeCount * capacity;
			for (long slot = 0; slot < slots; slot++) {
				final int position = Math.toIntExact(headerBytes + slot * BUCKET_BYTES);
				final int start = initialDirectory.getInt(position);
				final int count = initialDirectory.getInt(position + 4);
				if (start < 0 || count < 0) {
					throw new IOException("negative GMAP bucket directory value");
				}
				entryCount = Math.max(entryCount, Math.addExact(start, count));
			}
			final int entryTableOffset = Math.toIntExact(minimumDirectory);
			final long directoryBytes = Math.addExact(minimumDirectory,
					Math.multiplyExact((long) entryCount, ENTRY_BYTES));
			if (directoryBytes > SealedGridMapWriter.MAX_FILE_BYTES || directoryBytes > Integer.MAX_VALUE
					|| directoryBytes > fileSize) {
				throw new IOException("invalid or oversized GMAP directory: " + directoryBytes);
			}
			GridFs.unmap(initialDirectory);
			initialDirectory = GridFs.mapReadOnly(channel, 0L, directoryBytes);
			initialDirectory.order(ByteOrder.nativeOrder());
			final long payloadEnd = fileSize - 4L;
			if (payloadEnd < directoryBytes) {
				throw new IOException("GMAP payload overlaps directory: " + path);
			}
			validateEntries(initialDirectory, entryTableOffset, entryCount, directoryBytes, payloadEnd);
			final SealedGridMapReader reader = new SealedGridMapReader(path, channel, initialDirectory, version,
					nodeCount, nodeId, capacity, shard, watermark, domainType, bucketTableOffset,
					entryTableOffset, entryCount, directoryBytes, payloadEnd);
			initialDirectory = null;
			reader.verifyCrc(readIntAt(channel, payloadEnd));
			// Drop CRC windows so directory-only residency remains until first live payload get.
			reader.releasePayloadMaps();
			return reader;
		} catch (IOException | RuntimeException failure) {
			GridFs.unmap(initialDirectory);
			channel.close();
			throw failure;
		}
	}

	private static final int FIXED_HEADER_BYTES = 32;
	private static final int MAX_DOMAIN_NAME_BYTES = 1 << 20;
	private static final int VERSION_NODE_ID_BYTES = 4;

	/**
	 * Header-only peek for node-pack discovery (no directory/payload mmap).
	 *
	 * @author: GenCloud
	 * @date: 2026/05
	 * @since: 1.0
	 */
	private record NodeHeaderPeek(int version, int nodeId, int shard, long watermark, String domainType) {
	}

	/** Read GMAP fixed+domain(+nodeId) header without mapping directory or payload. */
	static NodeHeaderPeek peekNodeHeader(Path path) throws IOException {
		Objects.requireNonNull(path, "path");
		try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
			final long fileSize = channel.size();
			if (fileSize < FIXED_HEADER_BYTES) {
				throw new IOException("sealed file too small: " + path);
			}
			final ByteBuffer fixed = readAt(channel, 0L, FIXED_HEADER_BYTES);
			final int magic = fixed.getInt();
			if (magic != SealedGridMapWriter.MAGIC) {
				throw new IOException("bad GMAP magic: " + path);
			}
			final int version = fixed.getInt();
			if (version != SealedGridMapWriter.VERSION_NODE) {
				throw new IOException("unsupported GMAP version " + version + " (node-pack VERSION=2 only): " + path);
			}
			fixed.getInt(); // nodeCount
			fixed.getInt(); // capacity
			final int shard = fixed.getInt();
			final long watermark = fixed.getLong();
			final int domainLength = fixed.getInt();
			if (domainLength < 0 || domainLength > MAX_DOMAIN_NAME_BYTES
					|| FIXED_HEADER_BYTES + domainLength > fileSize) {
				throw new IOException("invalid GMAP domain length: " + domainLength);
			}
			final ByteBuffer variable = readAt(channel, FIXED_HEADER_BYTES, domainLength + VERSION_NODE_ID_BYTES);
			final byte[] domainBytes = new byte[domainLength];
			variable.get(domainBytes);
			final String domainType = new String(domainBytes, StandardCharsets.UTF_8);
			final int nodeId = variable.getInt();
			if (nodeId < 0 || nodeId >= SealedGridMapWriter.NODE_COUNT) {
				throw new IOException("invalid GMAP node id: " + nodeId);
			}
			return new NodeHeaderPeek(version, nodeId, shard, watermark, domainType);
		}
	}

	public static SealedGridMapReader openShard(Path sealedRoot, String domainType, int shard) throws IOException {
		Objects.requireNonNull(sealedRoot, "sealedRoot");
		Objects.requireNonNull(domainType, "domainType");
		final String safe = Integer.toHexString(domainType.hashCode());
		final Path[] paths = new Path[SealedGridMapWriter.NODE_COUNT];
		boolean found = false;
		long watermarkHint = 0L;
		if (Files.isDirectory(sealedRoot)) {
			try (DirectoryStream<Path> files = Files.newDirectoryStream(sealedRoot,
					safe + "_" + shard + "_n*.gmap")) {
				for (Path file : files) {
					final Matcher matcher = NODE_FILE_PATTERN.matcher(file.getFileName().toString());
					if (!matcher.matches()) {
						continue;
					}
					final int nameNodeId = Integer.parseInt(matcher.group(1));
					if (nameNodeId < 0 || nameNodeId >= SealedGridMapWriter.NODE_COUNT) {
						throw new IOException("invalid sealed node id in name: " + file);
					}
					if (paths[nameNodeId] != null) {
						throw new IOException("duplicate sealed node " + nameNodeId + " for shard " + shard);
					}
					final NodeHeaderPeek peek = peekNodeHeader(file);
					if (peek.version() != SealedGridMapWriter.VERSION_NODE
							|| peek.shard() != shard || !domainType.equals(peek.domainType())
							|| peek.nodeId() != nameNodeId) {
						throw new IOException("sealed node metadata mismatch: " + file);
					}
					watermarkHint = Math.max(watermarkHint, peek.watermark());
					paths[nameNodeId] = file;
					found = true;
				}
			}
		}
		if (found) {
			return new SealedGridMapReader(sealedRoot, domainType, shard, paths, watermarkHint);
		}
		return null;
	}

	private SealedGridMapReader nodeReader(int node) {
		if (nodeReaders != null) {
			return nodeReaders[node];
		}
		if (nodePaths == null || lazyNodes == null || closed) {
			return null;
		}
		SealedGridMapReader live = lazyNodes.get(node);
		if (live != null) {
			return live;
		}
		final Path nodePath = nodePaths[node];
		if (nodePath == null) {
			return null;
		}
		try {
			final SealedGridMapReader opened = openPhysicalFile(nodePath);
			if (closed) {
				opened.close();
				return null;
			}
			if (!lazyNodes.compareAndSet(node, null, opened)) {
				opened.close();
				return lazyNodes.get(node);
			}
			// Lost race with close(): close may already have swept and closed this reader.
			if (closed) {
				if (lazyNodes.compareAndSet(node, opened, null)) {
					opened.close();
				}
				return null;
			}
			return opened;
		} catch (IOException failure) {
			throw new IllegalStateException("failed to open sealed node " + nodePath, failure);
		}
	}

	public byte[] get(byte[] key) {
		Objects.requireNonNull(key, "key");
		if (nodeReaders != null || nodePaths != null) {
			ensureOpen();
			final int node = Math.abs(ArrayUtil.fastHash(key) % SealedGridMapWriter.NODE_COUNT);
			final SealedGridMapReader reader = nodeReader(node);
			return reader == null ? null : reader.get(key);
		}
		lifecycle.lockRead();
		try {
			ensureOpen();
			final int hash = ArrayUtil.fastHash(key);
			final int node = Math.abs(hash % SealedGridMapWriter.NODE_COUNT);
			if (version == SealedGridMapWriter.VERSION_NODE && node != nodeId) {
				return null;
			}
			final int localNode = version == SealedGridMapWriter.VERSION_NODE ? 0 : node;
			final int bucket = Math.abs(hash % capacity);
			final int bucketPosition = bucketTableOffset + (localNode * capacity + bucket) * BUCKET_BYTES;
			final int entryStart = directory.getInt(bucketPosition);
			final int count = directory.getInt(bucketPosition + 4);
			for (int index = entryStart; index < entryStart + count; index++) {
				final int entryPosition = entryTableOffset + index * ENTRY_BYTES;
				final long payloadOffset = directory.getLong(entryPosition);
				final int keyLength = directory.getInt(entryPosition + 8);
				final int valueLength = directory.getInt(entryPosition + 12);
				if (!payloadEquals(payloadOffset, keyLength, key)) {
					continue;
				}
				final byte[] value = readPayload(payloadOffset + keyLength, valueLength);
				if (METRICS_ENABLED) {
					SealedMetrics.SEALED_MISS.incrementAndGet();
				}
				return value;
			}
			return null;
		} finally {
			lifecycle.unlockRead();
		}
	}

	public void forEachLive(BiConsumer<byte[], byte[]> consumer) {
		Objects.requireNonNull(consumer, "consumer");
		if (nodeReaders != null || nodePaths != null) {
			ensureOpen();
			for (int node = 0; node < SealedGridMapWriter.NODE_COUNT; node++) {
				final SealedGridMapReader reader = nodeReader(node);
				if (reader != null) {
					reader.forEachLive(consumer);
				}
			}
			return;
		}
		lifecycle.lockRead();
		try {
			ensureOpen();
			for (int index = 0; index < entryCount; index++) {
				final int entryPosition = entryTableOffset + index * ENTRY_BYTES;
				final long payloadOffset = directory.getLong(entryPosition);
				final int keyLength = directory.getInt(entryPosition + 8);
				final int valueLength = directory.getInt(entryPosition + 12);
				if (valueLength == 0) {
					continue;
				}
				consumer.accept(readPayload(payloadOffset, keyLength),
						readPayload(payloadOffset + keyLength, valueLength));
			}
		} finally {
			lifecycle.unlockRead();
		}
	}

	public int shard() {
		return shard;
	}

	public long watermark() {
		return watermark;
	}

	/**
	 * Directory entry slots in this sealed file (hint for cardinality; node-pack sums children).
	 */
	public int entryCount() {
		if (nodeReaders != null || nodePaths != null) {
			int total = 0;
			for (int node = 0; node < SealedGridMapWriter.NODE_COUNT; node++) {
				final SealedGridMapReader reader = nodeReader(node);
				if (reader != null) {
					total += reader.entryCount;
				}
			}
			return total;
		}
		return entryCount;
	}

	public String domainType() {
		return domainType;
	}

	/** Test hook: whether sticky full-payload is currently installed. */
	public boolean stickyPayloadInstalled() {
		return stickyPayload.get() != null;
	}

	/** Test hook: sticky full-payload byte cap (one window). */
	public static long stickyMaxPayloadBytes() {
		return stickyMaxBytes();
	}

	/**
	 * Try sticky full-payload mmap when payload fits one {@link #WINDOW_BYTES} window.
	 * Caller must hold lifecycle read or write lock. Single-flight via CAS — loser unmaps.
	 */
	private void tryMapStickyPayload() throws IOException {
		if (stickyPayload.get() != null || channel == null) {
			return;
		}
		final long payloadBytes = payloadEnd - payloadStart;
		final long stickyMax = stickyMaxBytes();
		if (payloadBytes <= 0L || payloadBytes > stickyMax) {
			if (METRICS_ENABLED && payloadBytes > stickyMax
					&& stickyRejectLatched.compareAndSet(0, 1)) {
				SealedMetrics.SEALED_STICKY_REJECTED.incrementAndGet();
			}
			return;
		}
		final MappedByteBuffer mapped = GridFs.mapReadOnly(channel, payloadStart, payloadBytes);
		mapped.order(ByteOrder.nativeOrder());
		final long address = UnsafeMemory.getUnsafe().getLong(mapped, ADDRESS_OFFSET);
		final StickyPayload installed = new StickyPayload(mapped, address);
		if (!stickyPayload.compareAndSet(null, installed)) {
			GridFs.unmap(mapped);
		}
	}

	private boolean payloadEquals(long offset, int length, byte[] expected) {
		if (length != expected.length) {
			return false;
		}
		ensureStickyPayload();
		final StickyPayload sticky = stickyPayload.get();
		if (sticky != null) {
			long addr = sticky.address + (offset - payloadStart);
			for (int index = 0; index < length; index++) {
				if (UnsafeMemory.readByte(addr + index) != expected[index]) {
					return false;
				}
			}
			return true;
		}
		final PayloadWindow window = payloadWindows.get();
		for (int index = 0; index < length; index++) {
			ensureWindow(offset + index, window);
			if (window.buffer.get(Math.toIntExact(offset + index - window.start)) != expected[index]) {
				return false;
			}
		}
		return true;
	}

	private byte[] readPayload(long offset, int length) {
		if (length == 0) {
			return new byte[0];
		}
		ensureStickyPayload();
		final byte[] result = new byte[length];
		final StickyPayload sticky = stickyPayload.get();
		if (sticky != null) {
			UnsafeMemory.getUnsafe().copyMemory(null, sticky.address + (offset - payloadStart),
					result, UnsafeMemory.ARRAY_BASE_OFFSET, length);
			return result;
		}
		final PayloadWindow window = payloadWindows.get();
		int copied = 0;
		while (copied < length) {
			ensureWindow(offset + copied, window);
			final int chunk = (int) Math.min(length - copied, window.end - (offset + copied));
			final long base = UnsafeMemory.getUnsafe().getLong(window.buffer, ADDRESS_OFFSET);
			UnsafeMemory.getUnsafe().copyMemory(null,
					base + Math.toIntExact(offset + copied - window.start),
					result, UnsafeMemory.ARRAY_BASE_OFFSET + copied, chunk);
			copied += chunk;
		}
		return result;
	}

	/** Lazy sticky install under caller's lifecycle read lock; falls back to windowed. */
	private void ensureStickyPayload() {
		if (stickyPayload.get() != null || channel == null) {
			return;
		}
		try {
			tryMapStickyPayload();
		} catch (IOException failure) {
			throw new IllegalStateException("failed to map sealed sticky payload for " + path, failure);
		}
	}

	private void ensureWindow(long offset, PayloadWindow window) {
		if (offset < payloadStart || offset >= payloadEnd) {
			throw new IllegalStateException("sealed payload offset out of range: " + offset + " in " + path);
		}
		final int currentGeneration = generation.get();
		if (window.generation != currentGeneration) {
			window.clear();
			window.generation = currentGeneration;
		}
		if (window.buffer != null && offset >= window.start && offset < window.end) {
			return;
		}
		window.clear();
		final long relative = offset - payloadStart;
		window.start = payloadStart + relative / WINDOW_BYTES * WINDOW_BYTES;
		window.end = Math.min(payloadEnd, window.start + WINDOW_BYTES);
		try {
			window.buffer = GridFs.mapReadOnly(channel, window.start, window.end - window.start);
			window.buffer.order(ByteOrder.nativeOrder());
			if (METRICS_ENABLED) {
				SealedMetrics.SEALED_WINDOW_REMAP.incrementAndGet();
			}
		} catch (IOException failure) {
			window.start = -1L;
			window.end = -1L;
			throw new IllegalStateException("failed to map sealed payload window for " + path, failure);
		}
	}

	private void verifyCrc(int expected) throws IOException {
		final CRC32 crc = new CRC32();
		lifecycle.lockRead();
		try {
			tryMapStickyPayload();
			final StickyPayload sticky = stickyPayload.get();
			if (sticky != null) {
				final ByteBuffer view = sticky.map.duplicate();
				view.clear();
				crc.update(view);
			} else {
				final PayloadWindow window = payloadWindows.get();
				long offset = payloadStart;
				while (offset < payloadEnd) {
					ensureWindow(offset, window);
					final int chunk = Math.toIntExact(Math.min(window.end - offset, payloadEnd - offset));
					final ByteBuffer source = window.buffer.duplicate();
					source.position(Math.toIntExact(offset - window.start));
					source.limit(source.position() + chunk);
					crc.update(source);
					offset += chunk;
				}
			}
		} finally {
			lifecycle.unlockRead();
		}
		if ((int) crc.getValue() != expected) {
			throw new IOException("sealed payload CRC mismatch: " + path);
		}
	}

	/** Unmap sticky/window payload mappings (directory stays). Used after open CRC. */
	private void releasePayloadMaps() {
		lifecycle.lockWrite();
		try {
			generation.incrementAndGet();
			for (PayloadWindow window : payloadWindowRegistry) {
				window.clear();
			}
			final StickyPayload sticky = stickyPayload.getAndSet(null);
			if (sticky != null) {
				GridFs.unmap(sticky.map);
			}
		} finally {
			lifecycle.unlockWrite();
		}
	}

	private void ensureOpen() {
		if (closed) {
			throw new IllegalStateException("sealed reader is closed: " + path);
		}
	}

	@Override
	public void close() throws IOException {
		lifecycle.lockWrite();
		try {
			if (closed) {
				return;
			}
			closed = true;
			generation.incrementAndGet();
			for (PayloadWindow window : payloadWindowRegistry) {
				window.clear();
			}
			payloadWindowRegistry.clear();
			if (nodeReaders != null) {
				closeNodes(nodeReaders);
				return;
			}
			if (lazyNodes != null) {
				final SealedGridMapReader[] opened = new SealedGridMapReader[lazyNodes.length()];
				for (int node = 0; node < lazyNodes.length(); node++) {
					opened[node] = lazyNodes.getAndSet(node, null);
				}
				closeNodes(opened);
				return;
			}
			final StickyPayload sticky = stickyPayload.getAndSet(null);
			if (sticky != null) {
				GridFs.unmap(sticky.map);
			}
			GridFs.unmap(directory);
			channel.close();
		} finally {
			lifecycle.unlockWrite();
		}
	}

	private static void validateEntries(ByteBuffer directory, int entryTableOffset, int entryCount,
	                                    long payloadStart, long payloadEnd) throws IOException {
		for (int index = 0; index < entryCount; index++) {
			final int position = entryTableOffset + index * ENTRY_BYTES;
			final long offset = directory.getLong(position);
			final int keyLength = directory.getInt(position + 8);
			final int valueLength = directory.getInt(position + 12);
			if (keyLength < 0 || valueLength < 0 || offset < payloadStart) {
				throw new IOException("invalid GMAP entry directory at index " + index);
			}
			final long end = Math.addExact(offset, Math.addExact((long) keyLength, valueLength));
			if (end > payloadEnd) {
				throw new IOException("GMAP entry exceeds payload at index " + index);
			}
		}
	}

	private static ByteBuffer readAt(FileChannel channel, long offset, int length) throws IOException {
		final ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.nativeOrder());
		long position = offset;
		while (buffer.hasRemaining()) {
			final int read = channel.read(buffer, position);
			if (read < 0) {
				throw new IOException("unexpected EOF in sealed file");
			}
			position += read;
		}
		buffer.flip();
		return buffer;
	}

	private static int readIntAt(FileChannel channel, long offset) throws IOException {
		return readAt(channel, offset, 4).getInt();
	}

	private static long maxWatermark(SealedGridMapReader[] readers) {
		long maximum = 0L;
		for (SealedGridMapReader reader : readers) {
			if (reader != null) {
				maximum = Math.max(maximum, reader.watermark);
			}
		}
		return maximum;
	}

	private static void closeNodes(SealedGridMapReader[] readers) throws IOException {
		IOException failure = null;
		for (SealedGridMapReader reader : readers) {
			if (reader == null) {
				continue;
			}
			try {
				reader.close();
			} catch (IOException closeFailure) {
				if (failure == null) {
					failure = closeFailure;
				} else {
					failure.addSuppressed(closeFailure);
				}
			}
		}
		if (failure != null) {
			throw failure;
		}
	}

	private static long addressOffset() {
		try {
			final Field field = Buffer.class.getDeclaredField("address");
			return UnsafeMemory.getUnsafe().objectFieldOffset(field);
		} catch (NoSuchFieldException failure) {
			throw new ExceptionInInitializerError(failure);
		}
	}

	private static final class StickyPayload {
		private final MappedByteBuffer map;
		private final long address;

		private StickyPayload(MappedByteBuffer map, long address) {
			this.map = map;
			this.address = address;
		}
	}

	private static final class PayloadWindow {
		private MappedByteBuffer buffer;
		private long start = -1L;
		private long end = -1L;
		private int generation = -1;

		private void clear() {
			GridFs.unmap(buffer);
			buffer = null;
			start = -1L;
			end = -1L;
		}
	}
}
