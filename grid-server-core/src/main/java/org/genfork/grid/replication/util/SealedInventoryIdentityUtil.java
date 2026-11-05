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
package org.genfork.grid.replication.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Stable identity hash for HELLO sealed inventory trailers (skip unchanged observe).
 * <p>
 * Sort by domain then shard so peer encode order does not defeat the skip.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
public final class SealedInventoryIdentityUtil {

	private static final long FNV_OFFSET = 0xcbf29ce484222325L;
	private static final long FNV_PRIME = 0x100000001b3L;
	private static final int MIX_SHARD_SHIFT = 32;

	private SealedInventoryIdentityUtil() {
	}

	/**
	 * Stable FNV-1a over sorted {@code domain|shard|fp} entries; {@code 0} when empty/null.
	 */
	public static long identityHash(List<SealedShardInventoryEntry> inventory) {
		if (inventory == null || inventory.isEmpty()) {
			return 0L;
		}
		final List<SealedShardInventoryEntry> sorted = new ArrayList<>(inventory.size());
		for (SealedShardInventoryEntry entry : inventory) {
			if (entry != null && entry.domain() != null && entry.identityFingerprint() != 0L) {
				sorted.add(entry);
			}
		}
		if (sorted.isEmpty()) {
			return 0L;
		}
		sorted.sort(Comparator
				.comparing(SealedShardInventoryEntry::domain)
				.thenComparingInt(SealedShardInventoryEntry::shard));
		long hash = FNV_OFFSET;
		hash = mixLong(hash, sorted.size());
		for (SealedShardInventoryEntry entry : sorted) {
			hash = mixString(hash, entry.domain());
			hash = mixLong(hash, ((long) entry.shard() << MIX_SHARD_SHIFT) ^ entry.identityFingerprint());
		}
		return hash == 0L ? 1L : hash;
	}

	private static long mixString(long hash, String value) {
		final int len = value.length();
		long h = hash;
		for (int i = 0; i < len; i++) {
			h ^= value.charAt(i);
			h *= FNV_PRIME;
		}
		h ^= '/';
		h *= FNV_PRIME;
		return h;
	}

	private static long mixLong(long hash, long value) {
		long h = hash;
		h ^= value;
		h *= FNV_PRIME;
		return h;
	}
}
