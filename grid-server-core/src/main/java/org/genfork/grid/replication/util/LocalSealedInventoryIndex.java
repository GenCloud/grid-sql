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

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable streamKey → sealed identity FP index built from HELLO inventory entries.
 * <p>
 * After a successful rebuild, missing key means local FP {@code 0} (absent), not unknown.
 * Admit / differential-observe must not directory-walk; use this index instead.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
public final class LocalSealedInventoryIndex {

	private static final LocalSealedInventoryIndex EMPTY =
			new LocalSealedInventoryIndex(Map.of());

	private final Map<String, Long> fpByStreamKey;

	private LocalSealedInventoryIndex(Map<String, Long> fpByStreamKey) {
		this.fpByStreamKey = fpByStreamKey;
	}

	/**
	 * Empty index (no sealed shards advertised locally).
	 */
	public static LocalSealedInventoryIndex empty() {
		return EMPTY;
	}

	/**
	 * Build index from inventory list (skips null / zero-FP entries).
	 */
	public static LocalSealedInventoryIndex fromEntries(List<SealedShardInventoryEntry> entries) {
		if (entries == null || entries.isEmpty()) {
			return EMPTY;
		}
		final Map<String, Long> map = new HashMap<>(Math.max(16, entries.size() * 2));
		for (int i = 0; i < entries.size(); i++) {
			final SealedShardInventoryEntry entry = entries.get(i);
			if (entry == null || entry.domain() == null || entry.identityFingerprint() == 0L) {
				continue;
			}
			map.put(
					OpLogStreamKeyUtil.format(entry.domain(), entry.shard()),
                    entry.identityFingerprint());
		}
		if (map.isEmpty()) {
			return EMPTY;
		}
		return new LocalSealedInventoryIndex(Collections.unmodifiableMap(map));
	}

	/**
	 * Local sealed identity FP for {@code domain#shard}; {@code 0} when absent from index.
	 */
	public long fingerprint(String domain, int shard) {
		if (domain == null) {
			return 0L;
		}
		final Long fp = fpByStreamKey.get(OpLogStreamKeyUtil.format(domain, shard));
		return fp == null ? 0L : fp;
	}

	/**
	 * Entry count with non-zero FP.
	 */
	public int size() {
		return fpByStreamKey.size();
	}
}
