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

/**
 * Predicted scan heaviness for Adaptive Query Execution (AQE) and distributed map admission.
 * <p>
 * Prefer {@link QueryHeavinessEstimator} (ANALYZE sidecar + plan arithmetic). Do not derive
 * heaviness from post-materialization {@code keys.size()} or physical EXPLAIN.
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
public record QueryHeaviness(int predictedCandidates, boolean indexed) {
	/** Floor for intra-node AQE parallel scan (non-indexed). */
	private static final int HEAVY_CANDIDATE_THRESHOLD = 10_000;
	/** Floor for distributed shard-owner map (stricter than AQE). */
	private static final int DISTRIBUTED_CANDIDATE_THRESHOLD = 50_000;
	/** Minimum table shards before distributed map is considered. */
	private static final int MIN_SHARDS_FOR_DISTRIBUTED = 2;

	/**
	 * @param predictedCandidates estimated candidate key count ({@code < 0} clamped to 0)
	 * @param fullyIndexed        {@code true} when residual scan is index-covered
	 */
	public static QueryHeaviness estimate(int predictedCandidates, boolean fullyIndexed) {
		final int candidates = Math.max(0, predictedCandidates);
		return new QueryHeaviness(candidates, fullyIndexed);
	}

	/**
	 * {@code true} when intra-node AQE may consider parallel scan (subject to admission).
	 */
	public boolean isHeavy() {
		if (indexed) {
			return false;
		}
		return predictedCandidates >= HEAVY_CANDIDATE_THRESHOLD;
	}

	/**
	 * {@code true} when partitioned distributed map may run (heavier floor + multi-shard).
	 */
	public boolean isDistributedHeavy(int shardCount) {
		if (indexed) {
			return false;
		}
		if (shardCount < MIN_SHARDS_FOR_DISTRIBUTED) {
			return false;
		}
		return predictedCandidates >= DISTRIBUTED_CANDIDATE_THRESHOLD;
	}

	/** Named AQE heavy threshold (tests / EXPLAIN text at SPI edge). */
	public static int heavyCandidateThreshold() {
		return HEAVY_CANDIDATE_THRESHOLD;
	}

	/** Named distributed heavy threshold. */
	public static int distributedCandidateThreshold() {
		return DISTRIBUTED_CANDIDATE_THRESHOLD;
	}

	/** Minimum shards for distributed map. */
	public static int minShardsForDistributed() {
		return MIN_SHARDS_FOR_DISTRIBUTED;
	}
}
