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
package org.genfork.grid.catalog;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.zip.CRC32;

/**
 * Bounded LRU cache of table key → {@link TableSchema} (+ CRC fingerprint).
 * <p>
 * Invalidate on CREATE / ALTER / DROP. Misses are filled by {@link TableCatalog#getSchema}.
 * Lock is held only around map ops — never across SQL / Netty wait or GridFs I/O.
 *
 * @author: GenCloud
 * @date: 2025/07
 * @since: 1.0
 */
public final class CatalogMetaCache {
	/** Default LRU capacity ({@code grid.sql.catalog-meta-cache-size}). */
	public static final int DEFAULT_SIZE = 256;
	private static final int UNBOUNDED_CAPACITY = Integer.MAX_VALUE;
	private static final float LOAD_FACTOR = 0.75f;
	private static final int INITIAL_MAP_CAPACITY = 16;

	private final int capacity;
	private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
	private final LinkedHashMap<String, CachedMeta> map;

	/**
	 * @param capacity max entries; {@code <= 0} means unbounded
	 */
	public CatalogMetaCache(int capacity) {
		this.capacity = capacity <= 0 ? UNBOUNDED_CAPACITY : capacity;
		this.map = new LinkedHashMap<>(INITIAL_MAP_CAPACITY, LOAD_FACTOR, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, CachedMeta> eldest) {
				return size() > CatalogMetaCache.this.capacity;
			}
		};
	}

	/**
	 * Cached schema entry with CRC fingerprint of a stable schema summary.
	 */
	public record CachedMeta(TableSchema schema, long crc) {
		public CachedMeta {
			Objects.requireNonNull(schema, "schema");
		}
	}

	/**
	 * LRU get (write-lock: access-order {@link LinkedHashMap#get} mutates links).
	 */
	public CachedMeta get(String tableKey) {
		Objects.requireNonNull(tableKey, "tableKey");
		lock.writeLock().lock();
		try {
			return map.get(tableKey);
		} finally {
			lock.writeLock().unlock();
		}
	}

	public void put(String tableKey, TableSchema schema) {
		Objects.requireNonNull(tableKey, "tableKey");
		Objects.requireNonNull(schema, "schema");
		final long crc = fingerprint(schema);
		lock.writeLock().lock();
		try {
			map.put(tableKey, new CachedMeta(schema, crc));
		} finally {
			lock.writeLock().unlock();
		}
	}

	public void invalidate(String tableKey) {
		if (tableKey == null) {
			return;
		}
		lock.writeLock().lock();
		try {
			map.remove(tableKey);
		} finally {
			lock.writeLock().unlock();
		}
	}

	public void invalidateAll() {
		lock.writeLock().lock();
		try {
			map.clear();
		} finally {
			lock.writeLock().unlock();
		}
	}

	/** Alias for {@link #invalidateAll()}. */
	public void clear() {
		invalidateAll();
	}

	public boolean contains(String tableKey) {
		if (tableKey == null) {
			return false;
		}
		return get(tableKey) != null;
	}

	public int size() {
		lock.readLock().lock();
		try {
			return map.size();
		} finally {
			lock.readLock().unlock();
		}
	}

	public int capacity() {
		return capacity;
	}

	public static long fingerprint(TableSchema schema) {
		Objects.requireNonNull(schema, "schema");
		final CRC32 crc = new CRC32();
		final String header = schema.tableName()
				+ "|" + schema.schemaEpoch()
				+ "|" + schema.columns().size()
				+ "|" + schema.indexes().size()
				+ "|" + schema.foreignKeys().size();
		crc.update(header.getBytes(StandardCharsets.UTF_8));
		for (ColumnDef col : schema.columns()) {
			crc.update(col.name().getBytes(StandardCharsets.UTF_8));
			crc.update((byte) '|');
			crc.update(col.type().name().getBytes(StandardCharsets.UTF_8));
		}
		return crc.getValue();
	}
}
