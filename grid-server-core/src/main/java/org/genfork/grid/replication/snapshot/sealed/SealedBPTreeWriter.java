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

import org.genfork.grid.fs.GridFs;
import org.genfork.grid.mem.index.btree.comparator.ArraysComparator;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32;

/**
 * Immutable fixed-page B+ tree writer for sealed indexes ({@code .sbpt}).
 * <p>
 * Single-column keys are raw wire bytes; multi-column BPTree keys are encoded via
 * {@link SealedCompositeIndexKey} into the same {@link Entry#indexKey()} slot.
 *
 * @author: GenCloud
 * @date: 2026/05
 * @since: 1.0
 */
public final class SealedBPTreeWriter {
	public static final int MAGIC = 0x53425054;
	public static final int VERSION = 1;
	public static final int PAGE_SIZE = 8192;
	public static final int INTERNAL = 1;
	public static final int LEAF = 2;
	public static final long MAX_FILE_BYTES = 1L << 30;

	private static final int LEAF_HEADER_BYTES = 1 + Short.BYTES + Integer.BYTES * 2;
	private static final ArraysComparator COMPARATOR = new ArraysComparator();

	private SealedBPTreeWriter() {
	}

	public record Entry(byte[] indexKey, byte[] rowKey) {
		public Entry {
			Objects.requireNonNull(indexKey, "indexKey");
			Objects.requireNonNull(rowKey, "rowKey");
			if (indexKey.length > 0xffff || rowKey.length > 0xffff) {
				throw new IllegalArgumentException("sealed BPTree key exceeds unsigned-short limit");
			}
		}
	}

	public static void write(Path file, String domain, int shard, String indexName,
	                         List<Entry> sortedEntries) throws IOException {
		Objects.requireNonNull(file, "file");
		Objects.requireNonNull(domain, "domain");
		Objects.requireNonNull(indexName, "indexName");
		final List<Entry> source = sortedEntries == null ? List.of() : sortedEntries;
		final List<Group> groups = groupAndValidate(source);
		final Build build = buildPages(groups);
		final byte[] domainBytes = domain.getBytes(StandardCharsets.UTF_8);
		final byte[] indexBytes = indexName.getBytes(StandardCharsets.UTF_8);
		if (domainBytes.length > 0xffff || indexBytes.length > 0xffff) {
			throw new IllegalArgumentException("sealed BPTree metadata exceeds unsigned-short limit");
		}
		final int headerBytes = Integer.BYTES * 8 + Long.BYTES + Short.BYTES * 2
				+ domainBytes.length + indexBytes.length;
		final long fileBytes = headerBytes + (long) build.pages.size() * PAGE_SIZE + Integer.BYTES;
		if (fileBytes > MAX_FILE_BYTES) {
			failSize("sealed BPTree exceeds 1GiB: " + fileBytes);
		}

		GridFs.writeAtomic(file, temporary -> {
			try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
					StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
				final ByteBuffer header = ByteBuffer.allocate(headerBytes).order(ByteOrder.BIG_ENDIAN);
				header.putInt(MAGIC).putInt(VERSION).putInt(PAGE_SIZE);
				header.putInt(build.height).putInt(build.rootPageId).putInt(build.firstLeafPageId).putInt(shard);
				header.putShort((short) domainBytes.length).put(domainBytes);
				header.putShort((short) indexBytes.length).put(indexBytes);
				header.putLong(source.size()).putInt(build.pages.size()).flip();
				writeFully(channel, header);

				final CRC32 crc = new CRC32();
				for (byte[] page : build.pages) {
					crc.update(page);
					writeFully(channel, ByteBuffer.wrap(page));
				}
				final ByteBuffer footer = ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.BIG_ENDIAN);
				footer.putInt((int) crc.getValue()).flip();
				writeFully(channel, footer);
				channel.force(true);
			}
		});
	}

	private static List<Group> groupAndValidate(List<Entry> entries) {
		final List<Group> groups = new ArrayList<>();
		Group current = null;
		byte[] previous = null;
		for (Entry entry : entries) {
			Objects.requireNonNull(entry, "entry");
			if (previous != null && COMPARATOR.compare(previous, entry.indexKey()) > 0) {
				throw new IllegalArgumentException("sealed BPTree entries are not sorted");
			}
			if (current == null || COMPARATOR.compare(current.key, entry.indexKey()) != 0) {
				current = new Group(entry.indexKey());
				groups.add(current);
			}
			current.rows.add(entry.rowKey());
			previous = entry.indexKey();
		}
		return groups;
	}

	private static Build buildPages(List<Group> groups) throws IOException {
		final List<Page> pages = new ArrayList<>();
		final List<PageRef> leaves = new ArrayList<>();
		List<Group> pageGroups = new ArrayList<>();
		int used = LEAF_HEADER_BYTES;
		for (Group group : groups) {
			final int bytes = group.encodedBytes();
			if (bytes + LEAF_HEADER_BYTES > PAGE_SIZE) {
				failSize("sealed BPTree posting list exceeds page size");
			}
			if (!pageGroups.isEmpty() && used + bytes > PAGE_SIZE) {
				addLeaf(pages, leaves, pageGroups);
				pageGroups = new ArrayList<>();
				used = LEAF_HEADER_BYTES;
			}
			pageGroups.add(group);
			used += bytes;
		}
		if (!pageGroups.isEmpty() || leaves.isEmpty()) {
			addLeaf(pages, leaves, pageGroups);
		}
		for (int i = 0; i < leaves.size(); i++) {
			final Page leaf = pages.get(leaves.get(i).pageId);
			leaf.prev = i == 0 ? -1 : leaves.get(i - 1).pageId;
			leaf.next = i + 1 == leaves.size() ? -1 : leaves.get(i + 1).pageId;
		}

		List<PageRef> level = leaves;
		int height = 1;
		while (level.size() > 1) {
			final List<PageRef> parents = new ArrayList<>();
			int start = 0;
			while (start < level.size()) {
				int end = start + 1;
				int bytes = 1 + Short.BYTES + Integer.BYTES;
				while (end < level.size()) {
					final int added = Short.BYTES + level.get(end).firstKey.length + Integer.BYTES;
					if (bytes + added > PAGE_SIZE) {
						break;
					}
					bytes += added;
					end++;
				}
				if (end == start + 1 && end < level.size()) {
					failSize("sealed BPTree internal separator exceeds page size");
				}
				final List<PageRef> children = new ArrayList<>(level.subList(start, end));
				final int pageId = pages.size();
				pages.add(Page.internal(children));
				parents.add(new PageRef(pageId, children.getFirst().firstKey));
				start = end;
			}
			level = parents;
			height++;
		}

		final List<byte[]> encoded = new ArrayList<>(pages.size());
		for (Page page : pages) {
			encoded.add(page.encode());
		}
		return new Build(encoded, height, level.getFirst().pageId, leaves.getFirst().pageId);
	}

	private static void addLeaf(List<Page> pages, List<PageRef> leaves, List<Group> groups) {
		final int pageId = pages.size();
		pages.add(Page.leaf(new ArrayList<>(groups)));
		final byte[] firstKey = groups.isEmpty() ? new byte[0] : groups.getFirst().key;
		leaves.add(new PageRef(pageId, firstKey));
	}

	private static void writeFully(FileChannel channel, ByteBuffer buffer) throws IOException {
		while (buffer.hasRemaining()) {
			channel.write(buffer);
		}
	}

	private static void failSize(String message) throws IOException {
		SealedMetrics.SEAL_FAIL_SIZE.incrementAndGet();
		throw new IOException(message);
	}

	private static final class Group {
		private final byte[] key;
		private final List<byte[]> rows = new ArrayList<>();

		private Group(byte[] key) {
			this.key = Arrays.copyOf(key, key.length);
		}

		private int encodedBytes() {
			long bytes = Short.BYTES + key.length + Short.BYTES;
			if (rows.size() > 0xffff) {
				return Integer.MAX_VALUE;
			}
			for (byte[] row : rows) {
				bytes += Short.BYTES + row.length;
			}
			return bytes > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) bytes;
		}
	}

	private static final class Page {
		private final int type;
		private final List<Group> groups;
		private final List<PageRef> children;
		private int prev = -1;
		private int next = -1;

		private Page(int type, List<Group> groups, List<PageRef> children) {
			this.type = type;
			this.groups = groups;
			this.children = children;
		}

		private static Page leaf(List<Group> groups) {
			return new Page(LEAF, groups, null);
		}

		private static Page internal(List<PageRef> children) {
			return new Page(INTERNAL, null, children);
		}

		private byte[] encode() {
			final ByteBuffer buffer = ByteBuffer.allocate(PAGE_SIZE).order(ByteOrder.BIG_ENDIAN);
			buffer.put((byte) type);
			if (type == LEAF) {
				buffer.putShort((short) groups.size()).putInt(prev).putInt(next);
				for (Group group : groups) {
					putBytes(buffer, group.key);
					buffer.putShort((short) group.rows.size());
					for (byte[] row : group.rows) {
						putBytes(buffer, row);
					}
				}
			} else {
				buffer.putShort((short) (children.size() - 1));
				for (int i = 1; i < children.size(); i++) {
					putBytes(buffer, children.get(i).firstKey);
				}
				for (PageRef child : children) {
					buffer.putInt(child.pageId);
				}
			}
			return buffer.array();
		}

		private static void putBytes(ByteBuffer buffer, byte[] bytes) {
			buffer.putShort((short) bytes.length).put(bytes);
		}
	}

	private record PageRef(int pageId, byte[] firstKey) {
	}

	private record Build(List<byte[]> pages, int height, int rootPageId, int firstLeafPageId) {
	}
}