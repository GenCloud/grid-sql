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
package org.genfork.grid.query.plan;

/**
 * Crude shared cardinality / cost helpers for {@link QueryOptimizer}.
 * <p>
 * Estimates come from map size, sealed entry hints, and index fan-out — one place
 * for all scan/join strategy choices (no copy-paste across strategies).
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
public final class QueryCardinality {
	/** Relative cost of visiting one ordered-index posting during ordered walk. */
	private static final double COST_INDEX_POSTING = 1.0;
	/** Relative cost of comparing/emitting one filtered candidate in Top-K / sort. */
	private static final double COST_CANDIDATE = 1.0;
	/** Relative cost of hashing one build-side row for JOIN_HASH. */
	private static final double COST_HASH_BUILD = 1.2;
	/** Relative cost of probing one row via PK / index lookup. */
	private static final double COST_PK_PROBE = 1.0;
	/** Residual per-candidate weight after ordered index walk. */
	private static final double COST_RESIDUAL_WEIGHT = 0.05;
	/** LIMIT threshold for Top-K heap vs full n-log-n sort. */
	private static final int TOP_K_LIMIT = 1024;

	private QueryCardinality() {
	}

	/**
	 * Table row estimate: prefer the larger of working-set map size and sealed hint.
	 */
	public static long estimateTableRows(long mapSize, long sealedEntryHint) {
		final long map = Math.max(0L, mapSize);
		final long sealed = Math.max(0L, sealedEntryHint);
		return Math.max(1L, Math.max(map, sealed));
	}

	/**
	 * Average postings per distinct index key ({@code tableRows / distinctKeys}).
	 */
	public static long estimateFanOut(long tableRows, long distinctKeys) {
		final long rows = Math.max(1L, tableRows);
		if (distinctKeys <= 0L) {
			return rows;
		}
		return Math.max(1L, rows / distinctKeys);
	}

	/** EQ selectivity ≈ one fan-out bucket. */
	public static long estimateEq(long fanOut) {
		return Math.max(1L, fanOut);
	}

	/** IN-list selectivity ≈ {@code fanOut * valueCount}, capped by table rows. */
	public static long estimateIn(long fanOut, int valueCount, long tableRows) {
		final long rows = Math.max(1L, tableRows);
		final int n = Math.max(1, valueCount);
		return Math.min(rows, Math.max(1L, estimateEq(fanOut) * (long) n));
	}

	/**
	 * Independent AND selectivity: {@code left * right / tableRows}.
	 */
	public static long estimateAnd(long leftCard, long rightCard, long tableRows) {
		final long rows = Math.max(1L, tableRows);
		final long left = Math.max(0L, leftCard);
		final long right = Math.max(0L, rightCard);
		if (left == 0L || right == 0L) {
			return 0L;
		}
		return Math.max(1L, Math.min(rows, (left * right) / rows));
	}

	/**
	 * Ordered BPTree walk cost: walk enough index postings to fill LIMIT under selectivity.
	 */
	public static double costOrderedIndexScan(long indexSpan, long candidates, int limit) {
		final long span = Math.max(1L, indexSpan);
		final long cand = Math.max(0L, candidates);
		if (cand == 0L) {
			return 0.0;
		}
		final double selectivity = Math.min(1.0, (double) cand / (double) span);
		final long need = limit > 0 ? Math.min(cand, (long) limit) : cand;
		final long walk;
		if (selectivity <= 0.0) {
			walk = span;
		} else {
			walk = Math.min(span, Math.max(need, (long) Math.ceil(need / selectivity)));
		}
		return walk * COST_INDEX_POSTING + cand * COST_RESIDUAL_WEIGHT;
	}

	/**
	 * Filter-then-sort cost (Top-K heap when LIMIT is small, else n log n).
	 */
	public static double costFilterThenSort(long candidates, int limit) {
		final long cand = Math.max(0L, candidates);
		if (cand <= 1L) {
			return cand * COST_CANDIDATE;
		}
		final boolean topK = limit > 0 && limit <= TOP_K_LIMIT && limit < cand;
		final double logN = Math.log(Math.max(2L, topK ? (long) limit : cand)) / Math.log(2.0);
		return cand * COST_CANDIDATE * Math.max(1.0, logN);
	}

	/** Full table / pointer materialization cost. */
	public static double costTableScan(long rows) {
		return Math.max(0L, rows) * COST_CANDIDATE;
	}

	/** Nested PK / index probe join (build side lookups). */
	public static double costJoinPk(long probeRows) {
		return Math.max(0L, probeRows) * COST_PK_PROBE;
	}

	/** Hash join: build hash on one side + probe the other. */
	public static double costJoinHash(long buildRows, long probeRows) {
		return Math.max(0L, buildRows) * COST_HASH_BUILD
				+ Math.max(0L, probeRows) * COST_CANDIDATE;
	}
}