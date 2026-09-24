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

import com.google.common.annotations.VisibleForTesting;

import org.genfork.grid.catalog.TableAnalyzeStats;
import org.genfork.grid.query.filters.FilterCondition;
import org.genfork.grid.query.filters.impl.AlwaysFalseCondition;
import org.genfork.grid.query.filters.impl.AlwaysTrueCondition;
import org.genfork.grid.query.filters.impl.AndCondition;
import org.genfork.grid.query.filters.impl.IsNotNullCondition;
import org.genfork.grid.query.filters.impl.IsNullCondition;
import org.genfork.grid.query.filters.impl.LogicalOperatorCondition;
import org.genfork.grid.query.filters.impl.NotCondition;
import org.genfork.grid.query.filters.impl.OrCondition;
import org.genfork.grid.query.plan.QueryCardinality;
import org.genfork.grid.query.plan.QueryOptimizer;
import org.genfork.grid.store.TableStore;

/**
 * O(plan) heaviness from ANALYZE sidecar + filter cardinality (no EXPLAIN / dry-run / key scan).
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
public final class QueryHeavinessEstimator {
	/** Conservative residual fraction when no ANALYZE / unknown selectivity. */
	private static final long UNKNOWN_SELECTIVITY_DENOM = 1L;
	/** Cap predicted candidates to int range used by {@link QueryHeaviness}. */
	private static final long MAX_PREDICTED = Integer.MAX_VALUE;
	/** Indexed EQ floor when histogram/fan-out unavailable. */
	private static final int INDEXED_EQ_FLOOR = 1;
	/** IS NULL / IS NOT NULL crude fraction of table (1/10). */
	private static final long NULLISH_SELECTIVITY_DENOM = 10L;
	/** Range / LIKE / NE crude fraction of table (1/3). */
	private static final long RANGE_SELECTIVITY_DENOM = 3L;

	private QueryHeavinessEstimator() {
	}

	/**
	 * Predict candidates from cached stats + filter tree shape.
	 *
	 * @param store     table store (approx rows + optional ANALYZE overlay)
	 * @param filter    residual / WHERE tree (may be null → full table)
	 * @param indexedEq {@code true} when snapshot path is fully index-covered EQ
	 */
	public static QueryHeaviness fromFilter(TableStore store, FilterCondition filter, boolean indexedEq) {
		if (store == null) {
			return QueryHeaviness.estimate(0, indexedEq);
		}
		final long tableRows = Math.max(0L, store.approxRowStats().estimatedRows());
		if (indexedEq) {
			final long eqCard = estimateFilter(store.analyzeStatsOrNull(), filter, tableRows);
			final int predicted = clampPredicted(eqCard > 0L ? eqCard : INDEXED_EQ_FLOOR);
			return QueryHeaviness.estimate(predicted, true);
		}
		final TableAnalyzeStats stats = store.analyzeStatsOrNull();
		if (stats == null && tableRows <= 0L) {
			// No ANALYZE and no row hint → conservative local (never silent distribute).
			return QueryHeaviness.estimate(0, false);
		}
		final long candidates = estimateFilter(stats, filter, Math.max(1L, tableRows));
		return QueryHeaviness.estimate(clampPredicted(candidates), false);
	}

	/**
	 * JOIN / multi-table style estimate: product of sides capped by build×probe cost floors.
	 */
	public static QueryHeaviness fromJoinSides(
			long leftCandidates,
			long rightCandidates,
			boolean fullyIndexed
	) {
		final long left = Math.max(0L, leftCandidates);
		final long right = Math.max(0L, rightCandidates);
		final long joinEst = Math.min(MAX_PREDICTED, Math.max(left, 1L) * Math.max(right, 1L));
		return QueryHeaviness.estimate(clampPredicted(joinEst), fullyIndexed);
	}

	/**
	 * Subquery / IN-list estimate from outer table rows and nested candidate estimate.
	 */
	public static QueryHeaviness fromSubquery(
			long outerRows,
			long nestedCandidates,
			boolean fullyIndexed
	) {
		final long outer = Math.max(1L, outerRows);
		final long nested = Math.max(0L, nestedCandidates);
		final long est = QueryCardinality.estimateIn(
				QueryCardinality.estimateEq(Math.max(1L, nested)),
				1,
				outer);
		return QueryHeaviness.estimate(clampPredicted(est), fullyIndexed);
	}

	@VisibleForTesting
	public static long estimateFilter(TableAnalyzeStats stats, FilterCondition filter, long tableRows) {
		final long rows = Math.max(1L, tableRows);
		if (filter == null || filter instanceof AlwaysTrueCondition) {
			return rows;
		}
		if (filter instanceof AlwaysFalseCondition) {
			return 0L;
		}
		if (filter instanceof AndCondition and) {
			final long left = estimateFilter(stats, and.getLeft(), rows);
			final long right = estimateFilter(stats, and.getRight(), rows);
			return QueryCardinality.estimateAnd(left, right, rows);
		}
		if (filter instanceof OrCondition or) {
			final long left = estimateFilter(stats, or.getLeft(), rows);
			final long right = estimateFilter(stats, or.getRight(), rows);
			return Math.min(rows, left + right);
		}
		if (filter instanceof NotCondition not) {
			final long child = estimateFilter(stats, not.getChild(), rows);
			return Math.max(0L, rows - child);
		}
		if (filter instanceof IsNullCondition || filter instanceof IsNotNullCondition) {
			return Math.max(1L, rows / NULLISH_SELECTIVITY_DENOM);
		}
		if (filter instanceof LogicalOperatorCondition loc) {
			return estimateLogical(stats, loc, rows);
		}
		return rows / UNKNOWN_SELECTIVITY_DENOM;
	}

	private static long estimateLogical(TableAnalyzeStats stats, LogicalOperatorCondition loc, long rows) {
		final LogicalOperatorCondition.Operator op = loc.getOperator();
		if (op == LogicalOperatorCondition.Operator.EQ) {
			final long hist = QueryOptimizer.histogramEqualityEstimate(stats, loc);
			if (hist > 0L) {
				return Math.min(rows, hist);
			}
			final long fanOut = fanOutOrRows(stats, loc.getField(), rows);
			return Math.min(rows, QueryCardinality.estimateEq(fanOut));
		}
		if (op == LogicalOperatorCondition.Operator.IN) {
			final Object[] values = loc.getValues();
			final int n = values == null ? 0 : values.length;
			final long fanOut = fanOutOrRows(stats, loc.getField(), rows);
			return QueryCardinality.estimateIn(fanOut, Math.max(1, n), rows);
		}
		if (op == LogicalOperatorCondition.Operator.BETWEEN) {
			return Math.max(1L, rows / RANGE_SELECTIVITY_DENOM);
		}
		// NE / LIKE / ranges — crude fraction, never claim full table without stats evidence.
		return Math.max(1L, rows / RANGE_SELECTIVITY_DENOM);
	}

	private static long fanOutOrRows(TableAnalyzeStats stats, String field, long rows) {
		if (stats == null) {
			return rows;
		}
		final long fanOut = stats.fanOut(field);
		if (fanOut <= 0L) {
			return rows;
		}
		return fanOut;
	}

	private static int clampPredicted(long candidates) {
		if (candidates <= 0L) {
			return 0;
		}
		if (candidates >= MAX_PREDICTED) {
			return (int) MAX_PREDICTED;
		}
		return (int) candidates;
	}
}
