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
package org.genfork.grid.replication.repair;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-shard map of keyHash → VersionLocus.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public class VersionLocusMap {
	private final Map<String, Map<Long, VersionLocus>> byDomainShard = new ConcurrentHashMap<>();

	private static String key(String domainType, int shard) {
		return domainType + "#" + shard;
	}

	public void put(VersionLocus locus) {
		byDomainShard
				.computeIfAbsent(key(locus.domainType(), locus.shard()), k -> new ConcurrentHashMap<>())
				.put(locus.keyHash(), locus);
	}

	public VersionLocus get(String domainType, int shard, long keyHash) {
		final Map<Long, VersionLocus> map = byDomainShard.get(key(domainType, shard));
		return map == null ? null : map.get(keyHash);
	}

	public Map<Long, VersionLocus> snapshot(String domainType, int shard) {
		final Map<Long, VersionLocus> map = byDomainShard.get(key(domainType, shard));
		return map == null ? Map.of() : Map.copyOf(map);
	}

	/** Entry count for one domain#shard (0 if absent). */
	public int size(String domainType, int shard) {
		final Map<Long, VersionLocus> map = byDomainShard.get(key(domainType, shard));
		return map == null ? 0 : map.size();
	}

	public List<VersionLocus> all(String domainType, int shard) {
		return new ArrayList<>(snapshot(domainType, shard).values());
	}
}
