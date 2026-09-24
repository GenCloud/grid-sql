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
package org.genfork.grid.query.adaptive;

import java.util.ArrayList;
import java.util.List;

import com.google.common.annotations.VisibleForTesting;

/**
 * Mid-flight chunk split / coalesce helpers for AQE v2 (logic-VT map stages).
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
public final class AdaptiveChunkScheduler {
	private static final int MIN_PARTS = 2;
	private static final int SINGLE = 1;
	/** Re-split when a finished chunk leaves this many remaining keys and idle capacity. */
	private static final int RESPLIT_REMAINING_FLOOR = 2_000;
	/** Coalesce when unfinished chunk count exceeds this multiple of target parts. */
	private static final int COALESCE_PARTS_FACTOR = 2;

	private AdaptiveChunkScheduler() {
	}

	/** Named re-split floor (tests / metrics). */
	public static int resplitRemainingFloor() {
		return RESPLIT_REMAINING_FLOOR;
	}

	/**
	 * Whether remaining work should be re-split into more parts.
	 */
	public static boolean shouldResplit(int remainingKeys, int unfinishedChunks, int maxWorkers) {
		if (remainingKeys < RESPLIT_REMAINING_FLOOR) {
			return false;
		}
		if (unfinishedChunks >= maxWorkers) {
			return false;
		}
		return maxWorkers >= MIN_PARTS;
	}

	/**
	 * Whether many tiny unfinished chunks should be coalesced.
	 */
	public static boolean shouldCoalesce(int unfinishedChunks, int targetParts) {
		final int target = Math.max(MIN_PARTS, targetParts);
		return unfinishedChunks > target * COALESCE_PARTS_FACTOR;
	}

	/**
	 * Re-split a flat remaining key list into {@code parts} buckets (hash).
	 */
	@VisibleForTesting
	public static List<List<byte[]>> resplit(List<byte[]> remaining, int parts) {
		return AdaptiveParallelScan.splitByShardRange(
				remaining == null ? List.of() : remaining,
				Math.max(SINGLE, parts));
	}

	/**
	 * Coalesce unfinished chunks down toward {@code targetParts} by concatenation order.
	 */
	@VisibleForTesting
	public static List<List<byte[]>> coalesce(List<List<byte[]>> unfinished, int targetParts) {
		if (unfinished == null || unfinished.isEmpty()) {
			return List.of();
		}
		final int target = Math.max(SINGLE, targetParts);
		if (unfinished.size() <= target) {
			return unfinished;
		}
		final List<List<byte[]>> out = new ArrayList<>(target);
		for (int i = 0; i < target; i++) {
			out.add(new ArrayList<>());
		}
		int i = 0;
		for (List<byte[]> chunk : unfinished) {
			if (chunk == null || chunk.isEmpty()) {
				continue;
			}
			out.get(i % target).addAll(chunk);
			i++;
		}
		final List<List<byte[]>> nonEmpty = new ArrayList<>(target);
		for (List<byte[]> bucket : out) {
			if (!bucket.isEmpty()) {
				nonEmpty.add(bucket);
			}
		}
		return nonEmpty.isEmpty() ? unfinished : nonEmpty;
	}
}
