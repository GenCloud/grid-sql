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

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.ToLongBiFunction;

/**
 * O(1) sticky missing-stream incompleteness for READ_REPLICA admit.
 * <p>
 * While inventory index is not ready (post-invalidate, pre-eager-rebuild), admit is
 * fail-closed ({@code incomplete=true}). After rebuild, {@link #recompute} applies
 * {@link ProposerMissingStreamUtil#hasMissingStream} against the memory index — no disk.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
public final class MissingStreamAdmitState {

	private final AtomicBoolean inventoryReady = new AtomicBoolean(false);
	private final AtomicBoolean incomplete = new AtomicBoolean(true);
	private final AtomicReference<LocalSealedInventoryIndex> indexRef =
			new AtomicReference<>(LocalSealedInventoryIndex.empty());

	/**
	 * Fail-closed until first successful rebuild: mark not ready and incomplete.
	 */
	public void markNotReady() {
		inventoryReady.set(false);
		incomplete.set(true);
	}

	/**
	 * Publish rebuilt inventory index and recompute sticky incomplete bit.
	 *
	 * @return new incomplete value
	 */
	public boolean publishAndRecompute(
			LocalSealedInventoryIndex index,
			boolean follower,
			Map<String, Long> proposerShardFpByStream,
			Predicate<String> domainKnownLocally,
			ToLongBiFunction<String, Integer> lastSeq,
			ToLongBiFunction<String, Integer> appliedWatermark
	) {
		final LocalSealedInventoryIndex safe =
				index == null ? LocalSealedInventoryIndex.empty() : index;
		indexRef.set(safe);
		inventoryReady.set(true);
		return recompute(
				follower,
				proposerShardFpByStream,
				domainKnownLocally,
				lastSeq,
				appliedWatermark);
	}

	/**
	 * Recompute sticky bit from current index + proposer map (no disk).
	 * When inventory is not ready, forces incomplete {@code true}.
	 *
	 * @return incomplete after recompute
	 */
	public boolean recompute(
			boolean follower,
			Map<String, Long> proposerShardFpByStream,
			Predicate<String> domainKnownLocally,
			ToLongBiFunction<String, Integer> lastSeq,
			ToLongBiFunction<String, Integer> appliedWatermark
	) {
		if (!inventoryReady.get()) {
			incomplete.set(true);
			return true;
		}
		if (!follower) {
			incomplete.set(false);
			return false;
		}
		final LocalSealedInventoryIndex index = indexRef.get();
		final boolean missing = ProposerMissingStreamUtil.hasMissingStream(
				true,
				proposerShardFpByStream,
				domainKnownLocally,
				lastSeq,
				appliedWatermark,
				index::fingerprint);
		incomplete.set(missing);
		return missing;
	}

	/**
	 * O(1) admit read: incomplete when not ready or sticky missing-stream.
	 */
	public boolean isIncomplete() {
		return !inventoryReady.get() || incomplete.get();
	}

	/**
	 * Whether eager rebuild has published an index.
	 */
	public boolean isInventoryReady() {
		return inventoryReady.get();
	}

	/**
	 * Current memory index (never null).
	 */
	public LocalSealedInventoryIndex index() {
		final LocalSealedInventoryIndex index = indexRef.get();
		return index == null ? LocalSealedInventoryIndex.empty() : index;
	}
}
