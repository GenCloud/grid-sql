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

import org.genfork.grid.mem.index.btree.comparator.ArraysComparator;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lifecycle and path service for sealed per-shard B+ tree indexes.
 *
 * @author: GenCloud
 * @date: 2026/05
 * @since: 1.0
 */
public final class SealedBPTreeService implements AutoCloseable {
	private static final ArraysComparator ARRAYS = new ArraysComparator();
	private static final Comparator<SealedBPTreeWriter.Entry> ENTRY_ORDER =
			(left, right) -> ARRAYS.compare(left.indexKey(), right.indexKey());

	private final Path sealedRoot;
	private final ConcurrentHashMap<ReaderKey, SealedBPTreeReader> readers = new ConcurrentHashMap<>();

	public SealedBPTreeService(Path sealedRoot) {
		this.sealedRoot = Objects.requireNonNull(sealedRoot, "sealedRoot");
	}

	public Path indexFile(String domain, int shard, String indexName) {
		final String hex = SealedShardPack.domainHex(domain);
		return sealedRoot.resolve(hex + "_" + shard + "_idx_" + safeName(indexName) + ".sbpt");
	}

	public void dumpIndex(String domain, int shard, String indexName,
	                      List<SealedBPTreeWriter.Entry> entries) throws IOException {
		final List<SealedBPTreeWriter.Entry> ordered = new ArrayList<>(entries == null ? List.of() : entries);
		ordered.sort(ENTRY_ORDER);
		final Path file = indexFile(domain, shard, indexName);
		final ReaderKey key = new ReaderKey(domain, shard, indexName);
		// Unmap live reader before REPLACE — Windows AccessDenied on mmap'd *.sbpt (G-00001 class).
		final SealedBPTreeReader previous = readers.remove(key);
		closeQuietly(previous);
		SealedBPTreeWriter.write(file, domain, shard, indexName, ordered);
		final SealedBPTreeReader replacement = SealedBPTreeReader.open(file);
		readers.put(key, replacement);
	}

	/**
	 * Drop and unmap all open {@code .sbpt} readers for {@code domain#shard} before rewrite.
	 */
	public void releaseShard(String domain, int shard) {
		final List<ReaderKey> keys = new ArrayList<>();
		for (ReaderKey key : readers.keySet()) {
			if (key.domain().equals(domain) && key.shard() == shard) {
				keys.add(key);
			}
		}
		for (ReaderKey key : keys) {
			closeQuietly(readers.remove(key));
		}
	}

	public SealedBPTreeReader open(String domain, int shard, String indexName) throws IOException {
		final ReaderKey key = new ReaderKey(domain, shard, indexName);
		final SealedBPTreeReader existing = readers.get(key);
		if (existing != null) {
			return existing;
		}
		final SealedBPTreeReader created = SealedBPTreeReader.open(indexFile(domain, shard, indexName));
		final SealedBPTreeReader raced = readers.putIfAbsent(key, created);
		if (raced != null) {
			closeQuietly(created);
			return raced;
		}
		return created;
	}

	public void closeAll() {
		for (SealedBPTreeReader reader : readers.values()) {
			closeQuietly(reader);
		}
		readers.clear();
	}

	@Override
	public void close() {
		closeAll();
	}

	private static String safeName(String indexName) {
		Objects.requireNonNull(indexName, "indexName");
		// Allow '+' so composite sealed names (col1+col2) stay distinct from col1_col2.
		final String safe = indexName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._+-]", "_");
		if (safe.isBlank()) {
			throw new IllegalArgumentException("indexName has no safe filename characters");
		}
		return safe;
	}

	private static void closeQuietly(SealedBPTreeReader reader) {
		if (reader == null) {
			return;
		}
		try {
			reader.close();
		} catch (IOException ignored) {
			// Replacement remains authoritative.
		}
	}

	private record ReaderKey(String domain, int shard, String indexName) {
	}
}