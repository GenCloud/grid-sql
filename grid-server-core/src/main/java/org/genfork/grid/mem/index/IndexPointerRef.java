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
package org.genfork.grid.mem.index;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.genfork.grid.mem.index.btree.BPValueHelper;
import org.genfork.grid.utils.UnsafeMemory;

/**
 * Native key block pointer with optional cached key bytes (avoids rematerializing on query).
 *
 * @author: GenCloud
 * @date: 2025/04
 * @since: 1.0
 */
public class IndexPointerRef {
	/** Prefer exact capacity when LIMIT is below this (avoids huge ArrayList alloc). */
	private static final int LIST_CAPACITY_LIMIT_CAP = 10_000;
	/** Default ArrayList capacity when LIMIT is absent or huge. */
	private static final int LIST_CAPACITY_DEFAULT = 16;

	private final long pointer;
	/** Immutable key bytes shared from insert; null → decode from native on demand. */
	private final byte[] keyBytes;

	private volatile boolean free;

	public IndexPointerRef(long pointer) {
		this(pointer, null);
	}

	public IndexPointerRef(long pointer, byte[] keyBytes) {
		this.pointer = pointer;
		this.keyBytes = keyBytes;
	}

	public long getPointer() {
		return pointer;
	}

	public byte[] getKeyBytes() {
		return keyBytes;
	}

	public boolean isFree() {
		return free;
	}

	public byte[] toArray() {
		if (free) {
			return null;
		}
		if (keyBytes != null) {
			return keyBytes;
		}
		return BPValueHelper.keyFromNativeRef(pointer);
	}

	/** Prefer cached key bytes; fall back to native decode. */
	public byte[] resolveKey() {
		return toArray();
	}

	/**
	 * Bounded initial capacity for result ArrayLists under large/unbounded LIMIT.
	 * Avoids {@code new ArrayList<>(Integer.MAX_VALUE)} OOM on full-table scans.
	 */
	public static int listCapacity(int limit) {
		return limit > 0 && limit < LIST_CAPACITY_LIMIT_CAP ? limit : LIST_CAPACITY_DEFAULT;
	}

	/**
	 * Page {@code offset}/{@code limit} over pointers into key byte arrays (shared utility for scan strategies).
	 */
	public static List<byte[]> pageKeys(Iterable<IndexPointerRef> pointers, int offset, int limit) {
		if (pointers == null || limit == 0) {
			return List.of();
		}
		final int off = Math.max(0, offset);
		final ArrayList<byte[]> out = new ArrayList<>(listCapacity(limit));
		int skipped = 0;
		for (IndexPointerRef ptr : pointers) {
			if (ptr == null) {
				continue;
			}
			if (skipped < off) {
				skipped++;
				continue;
			}
			if (limit > 0 && out.size() >= limit) {
				break;
			}
			final byte[] key = ptr.resolveKey();
			if (key != null) {
				out.add(key);
			}
		}
		return out;
	}

	public void free() {
		if (free) {
			return;
		}

		free = true;

		UnsafeMemory.writeInt(pointer, -1);
		UnsafeMemory.free(pointer);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof IndexPointerRef that)) return false;

		if (pointer == 0 && that.pointer == 0) {
			return Arrays.equals(keyBytes, that.keyBytes);
		}
		return pointer == that.pointer;
	}

	@Override
	public int hashCode() {
		return pointer == 0 ? Arrays.hashCode(keyBytes) : Long.hashCode(pointer);
	}
}
