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
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32;

import com.google.common.annotations.VisibleForTesting;

import org.genfork.grid.fs.GridFs;
import org.genfork.grid.mem.LifecycleRwGate;
import org.genfork.grid.utils.UnsafeMemory;

/**
 * Memory-mapped reader for immutable sealed B+ tree pages.
 * Hot path maps the pages region once (≤1 GiB) and compares keys in-place via Unsafe.
 * <p>
 * EQ / inclusive range use ordered seeks. Composite left-prefix and LIKE cannot use a
 * contiguous range under length-first key order — those paths leaf-walk via
 * {@link #searchMatching(SealedKeyMatcher)}. Full cold scans use {@link #searchAll()} /
 * {@link #forEachLeafEntry(SealedLeafEntryConsumer)} without hydrating a RAM BPTree.
 *
 * @author: GenCloud
 * @date: 2026/05
 * @since: 1.0
 */
public final class SealedBPTreeReader implements AutoCloseable {
	private static final long ADDRESS_OFFSET = addressOffset();
	private static final long SOFT_MAX_PAGES_BYTES = 1L << 30;
	private static final boolean METRICS_ENABLED = !"false".equalsIgnoreCase(System.getProperty("sealed.metrics", "true"));
	private static final int LEAF_HEADER_SKIP = 1 + Short.BYTES + Integer.BYTES * 2;
	private static final int NEXT_LEAF_OFFSET = 1 + Short.BYTES + Integer.BYTES;

	private final Path path;
	private final FileChannel channel;
	private final long pagesOffset;
	private final int height;
	private final int rootPageId;
	private final int firstLeafPageId;
	private final int shard;
	private final int pageCount;

	private final LifecycleRwGate lifecycle = new LifecycleRwGate();

	/** Full pages region mapping when file fits soft max; otherwise sticky single-page window. */
	private MappedByteBuffer pagesMap;
	private ByteBuffer[] pageCache;
	private MappedByteBuffer mappedPage;
	private int mappedPageId = -1;
	private volatile boolean closed;

	private SealedBPTreeReader(Path path, FileChannel channel, Header header, MappedByteBuffer pagesMap)
			throws IOException {
		this.path = path;
		this.channel = channel;
		this.pagesOffset = header.pagesOffset;
		this.height = header.height;
		this.rootPageId = header.rootPageId;
		this.firstLeafPageId = header.firstLeafPageId;
		this.shard = header.shard;
		this.pageCount = header.pageCount;
		this.pagesMap = pagesMap;
		if (pagesMap != null) {
			this.pageCache = new ByteBuffer[pageCount];
			for (int i = 0; i < pageCount; i++) {
				final ByteBuffer slice = pagesMap.duplicate();
				final int start = i * SealedBPTreeWriter.PAGE_SIZE;
				slice.position(start).limit(start + SealedBPTreeWriter.PAGE_SIZE);
				pageCache[i] = slice.slice().order(ByteOrder.BIG_ENDIAN);
			}
			SealedMetrics.SEALED_INDEX_PAGE_FAULT.incrementAndGet();
		} else {
			this.pageCache = null;
		}
	}

	public static SealedBPTreeReader open(Path path) throws IOException {
		Objects.requireNonNull(path, "path");
		final FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
		try {
			final long size = channel.size();
			if (size < 48) {
				throw new IOException("sealed BPTree file is too small: " + path);
			}
			final int initialSize = (int) Math.min(size, 1L << 20);
			final MappedByteBuffer headerMap = GridFs.mapReadOnly(channel, 0, initialSize);
			headerMap.order(ByteOrder.BIG_ENDIAN);
			final Header header;
			try {
				header = parseHeader(headerMap, size, path);
				verifyCrc(channel, header, path);
			} finally {
				GridFs.unmap(headerMap);
			}
			final long pageBytes = (long) header.pageCount * SealedBPTreeWriter.PAGE_SIZE;
			MappedByteBuffer pages = null;
			if (pageBytes > 0 && pageBytes <= SOFT_MAX_PAGES_BYTES) {
				pages = GridFs.mapReadOnly(channel, header.pagesOffset, pageBytes);
				pages.order(ByteOrder.BIG_ENDIAN);
			}
			return new SealedBPTreeReader(path, channel, header, pages);
		} catch (IOException | RuntimeException failure) {
			channel.close();
			throw failure;
		}
	}

	public List<byte[]> searchEq(byte[] indexKey) {
		Objects.requireNonNull(indexKey, "indexKey");
		lifecycle.lockRead();
		try {
			ensureOpen();
			final ByteBuffer leaf = page(findLeaf(indexKey));
			final int count = unsignedShort(leaf, 1);
			final int entryStart = LEAF_HEADER_SKIP;
			final int found = findLeafKeyLinear(leaf, count, entryStart, indexKey);
			if (found < 0) {
				if (METRICS_ENABLED) {
					SealedMetrics.SEALED_INDEX_MISS.incrementAndGet();
				}
				return List.of();
			}
			if (METRICS_ENABLED) {
				SealedMetrics.SEALED_INDEX_HIT.incrementAndGet();
			}
			return readRows(leaf, found);
		} finally {
			lifecycle.unlockRead();
		}
	}

	public List<byte[]> searchRange(byte[] low, byte[] highInclusive) {
		Objects.requireNonNull(low, "low");
		Objects.requireNonNull(highInclusive, "highInclusive");
		lifecycle.lockRead();
		try {
			ensureOpen();
			if (compareBytes(low, highInclusive) > 0) {
				return List.of();
			}
			final List<byte[]> result = new ArrayList<>();
			int leafId = findLeaf(low);
			boolean first = true;
			while (leafId >= 0) {
				final ByteBuffer leaf = page(leafId);
				ensureType(leaf, SealedBPTreeWriter.LEAF);
				final int count = unsignedShort(leaf, 1);
				int offset = LEAF_HEADER_SKIP;
				for (int i = 0; i < count; i++) {
					final int keyLength = unsignedShort(leaf, offset);
					final int keyOffset = offset + Short.BYTES;
					final int lowCmp = compareMapped(leaf, keyOffset, keyLength, low);
					final int highCmp = compareMapped(leaf, keyOffset, keyLength, highInclusive);
					offset = skipKeyRows(leaf, keyOffset + keyLength);
					if (first && lowCmp < 0) {
						continue;
					}
					if (highCmp > 0) {
						return rangeResult(result);
					}
					result.addAll(readRows(leaf, keyOffset + keyLength));
				}
				first = false;
				leafId = leaf.getInt(NEXT_LEAF_OFFSET);
			}
			return rangeResult(result);
		} finally {
			lifecycle.unlockRead();
		}
	}

	/**
	 * Full leaf-chain scan retaining row keys whose index key matches {@code matcher}.
	 * Used for composite left-prefix / mid-column partial EQ and LIKE (length-first order
	 * is not contiguous for those predicates).
	 */
	public List<byte[]> searchMatching(SealedKeyMatcher matcher) {
		Objects.requireNonNull(matcher, "matcher");
		lifecycle.lockRead();
		try {
			ensureOpen();
			final List<byte[]> result = new ArrayList<>();
			forEachLeafIndexEntry((indexKey, leaf, posting) -> {
				if (matcher.test(indexKey)) {
					result.addAll(readRows(leaf, posting));
				}
			});
			return rangeResult(result);
		} finally {
			lifecycle.unlockRead();
		}
	}

	/**
	 * All row keys in leaf-chain order (wire bytes only).
	 * <p>
	 * LAZY cold scan without hydrating a RAM BPTree from {@code .sbpt}.
	 */
	public List<byte[]> searchAll() {
		lifecycle.lockRead();
		try {
			ensureOpen();
			final List<byte[]> result = new ArrayList<>();
			forEachLeafIndexEntry((_, leaf, posting) -> result.addAll(readRows(leaf, posting)));
			return rangeResult(result);
		} finally {
			lifecycle.unlockRead();
		}
	}

	/**
	 * Visit every {@code (indexKey, rowKey)} pair in leaf-chain order (wire bytes only).
	 * Reuses the same leaf-chain walk as {@link #searchMatching(SealedKeyMatcher)}.
	 */
	public void forEachLeafEntry(SealedLeafEntryConsumer consumer) {
		Objects.requireNonNull(consumer, "consumer");
		lifecycle.lockRead();
		try {
			ensureOpen();
			forEachLeafIndexEntry((indexKey, leaf, posting) -> {
				int offset = posting;
				final int rowCount = unsignedShort(leaf, offset);
				offset += Short.BYTES;
				for (int row = 0; row < rowCount; row++) {
					final int length = unsignedShort(leaf, offset);
					offset += Short.BYTES;
					consumer.accept(indexKey, copyMapped(leaf, offset, length));
					offset += length;
				}
			});
		} finally {
			lifecycle.unlockRead();
		}
	}

	/**
	 * Shared leaf-chain walk: one callback per distinct index-key posting (caller holds read lock).
	 */
	private void forEachLeafIndexEntry(SealedLeafIndexEntryAction action) {
		int leafId = firstLeafPageId;
		while (leafId >= 0) {
			final ByteBuffer leaf = page(leafId);
			ensureType(leaf, SealedBPTreeWriter.LEAF);
			final int count = unsignedShort(leaf, 1);
			int offset = LEAF_HEADER_SKIP;
			for (int i = 0; i < count; i++) {
				final int keyLength = unsignedShort(leaf, offset);
				final int keyOffset = offset + Short.BYTES;
				final byte[] indexKey = copyMapped(leaf, keyOffset, keyLength);
				final int posting = keyOffset + keyLength;
				action.accept(indexKey, leaf, posting);
				offset = skipKeyRows(leaf, posting);
			}
			leafId = leaf.getInt(NEXT_LEAF_OFFSET);
		}
	}

	@VisibleForTesting
	int firstLeafPageId() {
		return firstLeafPageId;
	}

	/**
	 * Predicate over sealed index-key wire bytes (no Object decode).
	 */
	@FunctionalInterface
	public interface SealedKeyMatcher {
		boolean test(byte[] indexKey);
	}

	/**
	 * Callback for each sealed leaf {@code (indexKey, rowKey)} pair (wire bytes only).
	 */
	@FunctionalInterface
	public interface SealedLeafEntryConsumer {
		void accept(byte[] indexKey, byte[] rowKey);
	}

	@FunctionalInterface
	private interface SealedLeafIndexEntryAction {
		void accept(byte[] indexKey, ByteBuffer leaf, int postingOffset);
	}

	private List<byte[]> rangeResult(List<byte[]> result) {
		if (result.isEmpty()) {
			SealedMetrics.SEALED_INDEX_MISS.incrementAndGet();
			return List.of();
		}
		SealedMetrics.SEALED_INDEX_HIT.incrementAndGet();
		return result;
	}

	private int findLeaf(byte[] key) {
		int pageId = rootPageId;
		for (int level = height; level > 1; level--) {
			final ByteBuffer internal = page(pageId);
			ensureType(internal, SealedBPTreeWriter.INTERNAL);
			final int keyCount = unsignedShort(internal, 1);
			int offset = 1 + Short.BYTES;
			int low = 0;
			int high = keyCount - 1;
			int child = keyCount;
			// Linear scan of key offsets once, then binary search by re-walking — for small fanout OK;
			// build offset table on stack via two-pass: first collect lengths.
			final int[] keyOffsets = new int[keyCount];
			final int[] keyLengths = new int[keyCount];
			for (int i = 0; i < keyCount; i++) {
				keyLengths[i] = unsignedShort(internal, offset);
				keyOffsets[i] = offset + Short.BYTES;
				offset = keyOffsets[i] + keyLengths[i];
			}
			while (low <= high) {
				final int mid = (low + high) >>> 1;
				if (compareMapped(internal, keyOffsets[mid], keyLengths[mid], key) > 0) {
					child = mid;
					high = mid - 1;
				} else {
					low = mid + 1;
				}
			}
			pageId = internal.getInt(offset + child * Integer.BYTES);
		}
		return pageId;
	}

	/** Ordered leaf EQ: single forward pass, stop when key would be past. */
	private int findLeafKeyLinear(ByteBuffer leaf, int count, int start, byte[] key) {
		int offset = start;
		for (int i = 0; i < count; i++) {
			final int keyLength = unsignedShort(leaf, offset);
			final int keyOffset = offset + Short.BYTES;
			final int cmp = compareMapped(leaf, keyOffset, keyLength, key);
			final int posting = keyOffset + keyLength;
			if (cmp == 0) {
				return posting;
			}
			if (cmp > 0) {
				return -1;
			}
			offset = skipKeyRows(leaf, posting);
		}
		return -1;
	}

	private List<byte[]> readRows(ByteBuffer leaf, int postingOffset) {
		int offset = postingOffset;
		final int count = unsignedShort(leaf, offset);
		offset += Short.BYTES;
		final List<byte[]> rows = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			final int length = unsignedShort(leaf, offset);
			offset += Short.BYTES;
			rows.add(copyMapped(leaf, offset, length));
			offset += length;
		}
		return rows;
	}

	private int skipKeyRows(ByteBuffer leaf, int postingOffset) {
		int offset = postingOffset;
		final int count = unsignedShort(leaf, offset);
		offset += Short.BYTES;
		for (int i = 0; i < count; i++) {
			final int length = unsignedShort(leaf, offset);
			offset += Short.BYTES + length;
		}
		return offset;
	}

	private ByteBuffer page(int pageId) {
		if (pageId < 0 || pageId >= pageCount) {
			throw new IllegalStateException("invalid sealed BPTree page " + pageId + " in " + path);
		}
		if (pageCache != null) {
			return pageCache[pageId];
		}
		if (mappedPageId != pageId) {
			unmapStickyPage();
			try {
				mappedPage = GridFs.mapReadOnly(
						channel,
						pagesOffset + (long) pageId * SealedBPTreeWriter.PAGE_SIZE,
						SealedBPTreeWriter.PAGE_SIZE);
				mappedPage.order(ByteOrder.BIG_ENDIAN);
				mappedPageId = pageId;
				SealedMetrics.SEALED_INDEX_PAGE_FAULT.incrementAndGet();
			} catch (IOException failure) {
				throw new IllegalStateException("failed to map sealed BPTree page " + pageId, failure);
			}
		}
		return mappedPage;
	}

	/** Same semantics as ArraysComparator without heap key copy (signed INT/LONG). */
	private static int compareMapped(ByteBuffer buffer, int offset, int length, byte[] key) {
		if (key == null) {
			return -1;
		}
		final int len2 = key.length;
		if (length == 4 && len2 == 4) {
			final int a = buffer.getInt(offset);
			final int b = ((key[0] & 0xFF) << 24) | ((key[1] & 0xFF) << 16)
					| ((key[2] & 0xFF) << 8) | (key[3] & 0xFF);
			return Integer.compare(a, b);
		}
		if (length == 8 && len2 == 8) {
			final long a = buffer.getLong(offset);
			long b = 0L;
			for (int i = 0; i < 8; i++) {
				b = (b << 8) | (key[i] & 0xFF);
			}
			return Long.compare(a, b);
		}
		final int lenDiff = length - len2;
		if (lenDiff != 0) {
			return lenDiff;
		}
		for (int i = 0; i < length; i++) {
			final int diff = (buffer.get(offset + i) & 0xFF) - (key[i] & 0xFF);
			if (diff != 0) {
				return diff;
			}
		}
		return 0;
	}

	private static int compareBytes(byte[] left, byte[] right) {
		if (left == right) {
			return 0;
		}
		if (left == null) {
			return 1;
		}
		if (right == null) {
			return -1;
		}
		final int len1 = left.length;
		final int len2 = right.length;
		if (len1 == 4 && len2 == 4) {
			final int a = ((left[0] & 0xFF) << 24) | ((left[1] & 0xFF) << 16)
					| ((left[2] & 0xFF) << 8) | (left[3] & 0xFF);
			final int b = ((right[0] & 0xFF) << 24) | ((right[1] & 0xFF) << 16)
					| ((right[2] & 0xFF) << 8) | (right[3] & 0xFF);
			return Integer.compare(a, b);
		}
		if (len1 == 8 && len2 == 8) {
			long a = 0L;
			long b = 0L;
			for (int i = 0; i < 8; i++) {
				a = (a << 8) | (left[i] & 0xFF);
				b = (b << 8) | (right[i] & 0xFF);
			}
			return Long.compare(a, b);
		}
		final int lenDiff = len1 - len2;
		if (lenDiff != 0) {
			return lenDiff;
		}
		for (int i = 0; i < len1; i++) {
			final int diff = (left[i] & 0xFF) - (right[i] & 0xFF);
			if (diff != 0) {
				return diff;
			}
		}
		return 0;
	}

	private static byte[] copyMapped(ByteBuffer buffer, int offset, int length) {
		final byte[] result = new byte[length];
		final long address = UnsafeMemory.getUnsafe().getLong(buffer, ADDRESS_OFFSET) + offset;
		UnsafeMemory.getUnsafe().copyMemory(null, address, result, UnsafeMemory.ARRAY_BASE_OFFSET, length);
		return result;
	}

	private static int unsignedShort(ByteBuffer buffer, int offset) {
		return Short.toUnsignedInt(buffer.getShort(offset));
	}

	private static void ensureType(ByteBuffer page, int expected) {
		if (Byte.toUnsignedInt(page.get(0)) != expected) {
			throw new IllegalStateException("corrupt sealed BPTree page type");
		}
	}

	private static void verifyCrc(FileChannel channel, Header header, Path path) throws IOException {
		final long pageBytes = (long) header.pageCount * SealedBPTreeWriter.PAGE_SIZE;
		final CRC32 crc = new CRC32();
		final ByteBuffer chunk = ByteBuffer.allocate(64 * 1024);
		long position = header.pagesOffset;
		long remaining = pageBytes;
		while (remaining > 0) {
			chunk.clear();
			chunk.limit((int) Math.min(chunk.capacity(), remaining));
			final int read = channel.read(chunk, position);
			if (read <= 0) {
				throw new IOException("truncated sealed BPTree pages: " + path);
			}
			crc.update(chunk.array(), 0, read);
			position += read;
			remaining -= read;
		}
		final ByteBuffer footer = ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.BIG_ENDIAN);
		while (footer.hasRemaining()) {
			final int read = channel.read(footer, position + footer.position());
			if (read <= 0) {
				throw new IOException("missing sealed BPTree CRC: " + path);
			}
		}
		footer.flip();
		if (footer.getInt() != (int) crc.getValue()) {
			throw new IOException("sealed BPTree CRC mismatch: " + path);
		}
	}

	private static Header parseHeader(ByteBuffer buffer, long fileSize, Path path) throws IOException {
		if (buffer.getInt() != SealedBPTreeWriter.MAGIC) {
			throw new IOException("bad sealed BPTree magic: " + path);
		}
		final int version = buffer.getInt();
		if (version == SealedBPTreeWriter.VERSION_LEGACY_UNSIGNED) {
			throw new IOException(
					"sealed BPTree VERSION=1 unsupported (signed order requires reseal / dumpDomain): " + path);
		}
		if (version != SealedBPTreeWriter.VERSION) {
			throw new IOException("unsupported sealed BPTree version " + version + ": " + path);
		}
		if (buffer.getInt() != SealedBPTreeWriter.PAGE_SIZE) {
			throw new IOException("unsupported sealed BPTree page size: " + path);
		}
		final int height = buffer.getInt();
		final int root = buffer.getInt();
		final int firstLeaf = buffer.getInt();
		final int shard = buffer.getInt();
		final String domain = readString(buffer);
		final String index = readString(buffer);
		final long entries = buffer.getLong();
		final int pages = buffer.getInt();
		final long pagesOffset = buffer.position();
		if (height <= 0 || pages <= 0 || root < 0 || root >= pages || firstLeaf < 0 || firstLeaf >= pages) {
			throw new IOException("invalid sealed BPTree header: " + path);
		}
		final long expected = pagesOffset + (long) pages * SealedBPTreeWriter.PAGE_SIZE + Integer.BYTES;
		if (expected != fileSize) {
			throw new IOException("invalid sealed BPTree length: " + path);
		}
		return new Header(pagesOffset, height, root, firstLeaf, shard, domain, index, entries, pages);
	}

	private static String readString(ByteBuffer buffer) throws IOException {
		final int length = Short.toUnsignedInt(buffer.getShort());
		if (length > buffer.remaining()) {
			throw new IOException("truncated sealed BPTree metadata");
		}
		final byte[] bytes = new byte[length];
		buffer.get(bytes);
		return new String(bytes, StandardCharsets.UTF_8);
	}

	private static long addressOffset() {
		try {
			final Field field = Buffer.class.getDeclaredField("address");
			return UnsafeMemory.getUnsafe().objectFieldOffset(field);
		} catch (NoSuchFieldException failure) {
			throw new ExceptionInInitializerError(failure);
		}
	}

	private void ensureOpen() {
		if (closed) {
			throw new IllegalStateException("sealed BPTree reader is closed: " + path);
		}
	}

	private void unmapStickyPage() {
		if (mappedPage != null) {
			GridFs.unmap(mappedPage);
			mappedPage = null;
			mappedPageId = -1;
		}
	}

	private void unmapAll() {
		unmapStickyPage();
		if (pagesMap != null) {
			GridFs.unmap(pagesMap);
			pagesMap = null;
			pageCache = null;
		}
	}

	public int shard() {
		return shard;
	}

	@Override
	public void close() throws IOException {
		lifecycle.lockWrite();
		try {
			if (closed) {
				return;
			}
			closed = true;
			unmapAll();
			channel.close();
		} finally {
			lifecycle.unlockWrite();
		}
	}

	private record Header(long pagesOffset, int height, int rootPageId, int firstLeafPageId,
	                      int shard, String domain, String indexName, long entryCount, int pageCount) {
	}
}
