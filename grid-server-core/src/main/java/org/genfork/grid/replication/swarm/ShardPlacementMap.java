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
package org.genfork.grid.replication.swarm;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shard ownership placement: primary write affinity, drain lifecycle, and optional peer pins.
 *
 * @author: GenCloud
 * @date: 2026/06
 * @since: 1.0
 */
public class ShardPlacementMap {
	public enum DrainState {
		NONE,
		QUIESCE,
		CATCH_UP,
		CUTOVER_DONE
	}

	private final Map<String, String> ownerByStream = new ConcurrentHashMap<>();
	private final Map<String, DrainState> drainByStream = new ConcurrentHashMap<>();
	private final Map<String, String> affinityByStream = new ConcurrentHashMap<>();

	private static String key(String domainType, int shard) {
		return domainType + "#" + shard;
	}

	public void setOwner(String domainType, int shard, String peerId) {
		if (domainType == null || peerId == null) {
			return;
		}
		ownerByStream.put(key(domainType, shard), peerId);
	}

	public String owner(String domainType, int shard) {
		return ownerByStream.get(key(domainType, shard));
	}

	public Map<String, String> snapshot() {
		return Map.copyOf(ownerByStream);
	}

	public void setDrainState(String domainType, int shard, DrainState state) {
		if (domainType == null || state == null) {
			return;
		}
		final String k = key(domainType, shard);
		if (state == DrainState.NONE) {
			drainByStream.remove(k);
		} else {
			drainByStream.put(k, state);
		}
	}

	public DrainState drainState(String domainType, int shard) {
		return drainByStream.getOrDefault(key(domainType, shard), DrainState.NONE);
	}

	public boolean isDraining(String domainType, int shard) {
		final DrainState s = drainState(domainType, shard);
		return s == DrainState.QUIESCE || s == DrainState.CATCH_UP;
	}

	/** Pin domain/shard primary affinity to a peer (honored by swarm migrate). */
	public void setAffinity(String domainType, int shard, String peerId) {
		if (domainType == null) {
			return;
		}
		final String k = key(domainType, shard);
		if (peerId == null) {
			affinityByStream.remove(k);
		} else {
			affinityByStream.put(k, peerId);
		}
	}

	public String affinity(String domainType, int shard) {
		return affinityByStream.get(key(domainType, shard));
	}

	public Map<String, String> affinitySnapshot() {
		return Map.copyOf(affinityByStream);
	}

	public Map<String, String> drainSnapshot() {
		final Map<String, String> out = new ConcurrentHashMap<>();
		drainByStream.forEach((k, v) -> out.put(k, v.name()));
		return Map.copyOf(out);
	}

	/**
	 * Drain cutover: QUIESCE → CATCH_UP → ownership transfer → CUTOVER_DONE.
	 */
	public void cutover(String domainType, int shard, String newOwnerPeerId) {
		setOwner(domainType, shard, newOwnerPeerId);
		setDrainState(domainType, shard, DrainState.CUTOVER_DONE);
	}

	/** Begin drain toward target peer (affinity preferred when set). */
	public void beginDrain(String domainType, int shard, String targetPeerId) {
		if (targetPeerId != null) {
			setAffinity(domainType, shard, targetPeerId);
		}
		setDrainState(domainType, shard, DrainState.QUIESCE);
	}

	public void advanceToCatchUp(String domainType, int shard) {
		if (drainState(domainType, shard) == DrainState.QUIESCE) {
			setDrainState(domainType, shard, DrainState.CATCH_UP);
		}
	}
}
