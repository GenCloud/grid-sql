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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.genfork.grid.catalog.TableAnalyzeStats;
import org.genfork.grid.mem.index.AbstractIndexOperation;
import org.genfork.grid.mem.index.ArrayIndexType;
import org.genfork.grid.mem.index.bitmap.GridBitmapIndex;
import org.genfork.grid.mem.index.btree.AbstractBPTree;
import org.genfork.grid.mem.index.btree.CompositeTreeKey;
import org.genfork.grid.mem.index.btree.IndexOperationResult;
import org.genfork.grid.mem.index.btree.SingleTreeKey;
import org.genfork.grid.query.filters.FilterCondition;
import org.genfork.grid.query.filters.impl.AlwaysFalseCondition;
import org.genfork.grid.query.filters.impl.AlwaysTrueCondition;
import org.genfork.grid.query.filters.impl.AndCondition;
import org.genfork.grid.query.filters.impl.CompositeIndexCondition;
import org.genfork.grid.query.filters.impl.LogicalOperatorCondition;
import org.genfork.grid.query.filters.impl.OrCondition;
import org.genfork.grid.query.plan.SortOrderData.OrderDirection;
import org.genfork.grid.query.plan.strategy.BPTreeIndexScanStrategy;
import org.genfork.grid.query.plan.strategy.FilterThenSortStrategy;
import org.genfork.grid.query.plan.strategy.QueryScanStrategy;
import org.genfork.grid.query.plan.strategy.TableScanStrategy;
import org.genfork.grid.replication.snapshot.sealed.SealedFallbackBitmap;
import org.genfork.grid.serial.SqlWireUtil;
import org.genfork.grid.sql.exec.SqlExplainKinds;

/**
 * Filter and scan strategy selection for SQL SELECT (cost/stats aware).
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
public class QueryOptimizer {
	record DnfResult(FilterCondition condition, boolean useSameOrderAscendingIndex) {
	}

	/**
	 * Chosen scan strategy plus estimated relative cost for EXPLAIN ANALYZE.
	 */
	public record ScanChoice(QueryScanStrategy strategy, double estimatedCost, String kind) {
	}

	/**
	 * Chosen join plan kind plus estimated relative cost.
	 */
	public record JoinChoice(String kind, double estimatedCost) {
	}

	private static final DnfResult EMPTY_FILTER = new DnfResult((_, _, _, _) -> IndexOperationResult.EMPTY, false);

	/**
	 * Cost-based TABLE vs BPTree vs FilterThenSort (uses {@link QueryCardinality}).
	 * Callers must supply live or ANALYZE {@link TableRowStats} (no UNKNOWN default on product path).
	 */
	public static ScanChoice chooseBestScanStrategy(QueryData queryData,
	                                                Map<String, AbstractIndexOperation<byte[], SingleTreeKey>> indexes,
	                                                Map<String, ArrayIndexType> cachedOrderIndexFields,
	                                                IndexOperationResult operationResult,
	                                                TableRowStats tableStats) {
		final SortOrderData[] sortOrderData = queryData.sortOrder();
		final long candidates = operationResult == null ? 0L : Math.max(0L, operationResult.getSize());
		final int limit = queryData.paging() == null ? -1 : queryData.paging().limit();
		final TableRowStats stats = tableStats == null ? TableRowStats.UNKNOWN : tableStats;
		final long tableRows = stats.estimatedRows();

		if (sortOrderData == null) {
			final double cost = QueryCardinality.costTableScan(candidates > 0 ? candidates : tableRows);
			return new ScanChoice(TableScanStrategy.INSTANCE, cost, SqlExplainKinds.TABLE);
		}

		// Composite / prefix EQ already returned pointers in sort order.
		if (queryData.filter() != null && queryData.filter().useSameOrderAscendingIndex()) {
			final double cost = QueryCardinality.costTableScan(candidates);
			return new ScanChoice(TableScanStrategy.INSTANCE, cost, SqlExplainKinds.TABLE);
		}

		final double sortCost = QueryCardinality.costFilterThenSort(candidates, limit);
		final FilterThenSortStrategy filterThenSort = new FilterThenSortStrategy(cachedOrderIndexFields);

		if (sortOrderData.length == 1) {
			final String sortField = sortOrderData[0].sortField();
			final AbstractIndexOperation<byte[], SingleTreeKey> indexOperation = indexes.get(sortField);
			if (indexOperation instanceof AbstractBPTree<?, ?> && !(indexOperation instanceof GridBitmapIndex)) {
				final long indexSpan = estimateIndexSpan(indexOperation, tableRows);
				final double bpCost = QueryCardinality.costOrderedIndexScan(indexSpan, candidates, limit);
				if (bpCost <= sortCost) {
					return new ScanChoice(
							new BPTreeIndexScanStrategy(false, indexOperation),
							bpCost,
							SqlExplainKinds.INDEX
					);
				}
			}
		}

		// FilterThenSort needs external-order ArrayIndexType; otherwise fall back to table order.
		if (cachedOrderIndexFields == null || !hasExternalOrderSortKeys(sortOrderData, cachedOrderIndexFields)) {
			final double cost = QueryCardinality.costTableScan(candidates > 0 ? candidates : tableRows);
			return new ScanChoice(TableScanStrategy.INSTANCE, cost, SqlExplainKinds.TABLE);
		}

		return new ScanChoice(filterThenSort, sortCost, SqlExplainKinds.INDEX);
	}

	private static boolean hasExternalOrderSortKeys(
			SortOrderData[] sortOrderData,
			Map<String, ArrayIndexType> cachedOrderIndexFields
	) {
		for (SortOrderData data : sortOrderData) {
			if (data != null && data.sortField() != null
					&& cachedOrderIndexFields.containsKey(data.sortField())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Cost-based JOIN_PK vs JOIN_HASH using table row estimates.
	 */
	public static JoinChoice chooseJoinStrategy(boolean leftOnPk,
	                                            boolean rightOnPk,
	                                            long leftRows,
	                                            long rightRows) {
		final long left = Math.max(1L, leftRows);
		final long right = Math.max(1L, rightRows);
		final double hashCost = QueryCardinality.costJoinHash(right, left);

		if (rightOnPk) {
			final double pkCost = QueryCardinality.costJoinPk(left);
			if (pkCost <= hashCost) {
				return new JoinChoice(SqlExplainKinds.JOIN_PK, pkCost);
			}
			return new JoinChoice(SqlExplainKinds.JOIN_HASH, hashCost);
		}
		if (leftOnPk) {
			final double pkCost = QueryCardinality.costJoinPk(right);
			if (pkCost <= hashCost) {
				return new JoinChoice(SqlExplainKinds.JOIN_PK, pkCost);
			}
			return new JoinChoice(SqlExplainKinds.JOIN_HASH, hashCost);
		}
		return new JoinChoice(SqlExplainKinds.JOIN_HASH, hashCost);
	}

	/**
	 * Prefer single-column {@link GridBitmapIndex} for EQ / IN (and AND of those).
	 * Multi-EQ AND relies on {@link AndCondition} bitmap intersect / pointer intersect;
	 * residual predicates stay wire {@code byte[]} matches.
	 */
	public static FilterCondition preferBitmapEq(
			Map<String, AbstractIndexOperation<byte[], SingleTreeKey>> indexes,
			FilterCondition root
	) {
		return preferBitmapPredicates(indexes, root);
	}

	/**
	 * Bitmap plan polish: EQ, IN-list, and AND of bitmap predicates (SQL {@code CREATE BITMAP INDEX} only).
	 */
	public static FilterCondition preferBitmapPredicates(
			Map<String, AbstractIndexOperation<byte[], SingleTreeKey>> indexes,
			FilterCondition root
	) {
		if (root instanceof LogicalOperatorCondition loc
				&& isBitmapFriendly(loc)
				&& SealedFallbackBitmap.isBitmapIndex(indexes.get(loc.getField()))) {
			return loc;
		}
		if (root instanceof AndCondition and) {
			return new AndCondition(
					preferBitmapPredicates(indexes, and.getLeft()),
					preferBitmapPredicates(indexes, and.getRight())
			);
		}
		if (root instanceof OrCondition or) {
			return new OrCondition(
					preferBitmapPredicates(indexes, or.getLeft()),
					preferBitmapPredicates(indexes, or.getRight())
			);
		}
		return root;
	}

	private static boolean isBitmapFriendly(LogicalOperatorCondition loc) {
		final LogicalOperatorCondition.Operator op = loc.getOperator();
		return op == LogicalOperatorCondition.Operator.EQ
				|| op == LogicalOperatorCondition.Operator.IN;
	}

	/**
	 * Histogram-backed equality cardinality used by cost and EXPLAIN paths.
	 *
	 * @return estimated matching rows, or {@code 0} when no exact frequency bucket exists
	 */
	public static long histogramEqualityEstimate(TableAnalyzeStats stats, FilterCondition filter) {
		if (stats == null || !(filter instanceof LogicalOperatorCondition comparison)
				|| comparison.getOperator() != LogicalOperatorCondition.Operator.EQ
				|| comparison.getValues().length == 0) {
			return 0L;
		}
		final byte[] wireValue = SqlWireUtil.toGenericArray(comparison.getValues()[0]);
		return stats.equalityEstimate(comparison.getField(), wireValue);
	}

	private static long estimateIndexSpan(AbstractIndexOperation<byte[], SingleTreeKey> index, long tableRows) {
		if (index instanceof AbstractBPTree<?, ?> tree) {
			final long distinct = tree.cardinality();
			if (distinct > 0L) {
				return Math.max(tableRows, distinct);
			}
		}
		if (index instanceof GridBitmapIndex bitmap) {
			return Math.max(tableRows, bitmap.approxPostings());
		}
		if (index instanceof SealedFallbackBitmap sealedBitmap) {
			return Math.max(tableRows, sealedBitmap.delegate().approxPostings());
		}
		return Math.max(1L, tableRows);
	}

	public static FilterConditionData tryOptimizeForCompositeIndex(Map<List<String>, AbstractIndexOperation<byte[][], CompositeTreeKey>> compositeIndexes,
	                                                               FilterCondition root,
	                                                               SortOrderData sortOrder) {
		final FilterCondition normalizedRoot = normalizeFilterTree(root);

		// AND-over-OR distribute → DNF
		FilterCondition dnfRoot = distributeAndOverOr(normalizedRoot);

		// Try each composite index against the DNF tree
		for (Map.Entry<List<String>, AbstractIndexOperation<byte[][], CompositeTreeKey>> entry : compositeIndexes.entrySet()) {
			final List<String> indexFields = entry.getKey();
			final AbstractIndexOperation<byte[][], CompositeTreeKey> index = entry.getValue();

			final DnfResult dnfResult = optimizeDnfWithCompositeIndex(dnfRoot, indexFields, index, sortOrder);
			dnfRoot = dnfResult.condition();

			if (dnfResult.useSameOrderAscendingIndex()) {
				return new FilterConditionData(dnfRoot, true);
			}
		}

		return new FilterConditionData(dnfRoot, false);
	}

	private static FilterCondition normalizeFilterTree(FilterCondition node) {
		if (node instanceof AndCondition andCondition) {
			final FilterCondition left = normalizeFilterTree(andCondition.getLeft());
			final FilterCondition right = normalizeFilterTree(andCondition.getRight());
			return normalizeAndFilter(left, right);
		}

		if (node instanceof OrCondition orCondition) {
			final FilterCondition left = normalizeFilterTree(orCondition.getLeft());
			final FilterCondition right = normalizeFilterTree(orCondition.getRight());
			return normalizeOrFilter(left, right);
		}

		return node;
	}

	private static FilterCondition normalizeAndFilter(FilterCondition left, FilterCondition right) {
		if (left instanceof AlwaysFalseCondition || right instanceof AlwaysFalseCondition) {
			return AlwaysFalseCondition.getInstance();
		}

		if (left instanceof AlwaysTrueCondition) {
			return right;
		}

		if (right instanceof AlwaysTrueCondition) {
			return left;
		}

		final Set<FilterCondition> conditions = new HashSet<>();
		extractConditionsFromAnd(left, conditions);
		extractConditionsFromAnd(right, conditions);

		if (hasContradictoryConditions(conditions)) {
			return new AlwaysFalseCondition();
		}

		final Set<FilterCondition> uniqueConditions = removeDuplicateConditions(conditions);
		return buildAndTree(new ArrayList<>(uniqueConditions), 0, uniqueConditions.size() - 1);
	}

	private static FilterCondition normalizeOrFilter(FilterCondition left, FilterCondition right) {
		if (left instanceof AlwaysTrueCondition || right instanceof AlwaysTrueCondition) {
			return AlwaysTrueCondition.getInstance();
		}

		if (left instanceof AlwaysFalseCondition) {
			return right;
		}

		if (right instanceof AlwaysFalseCondition) {
			return left;
		}

		final Set<FilterCondition> conditions = new HashSet<>();
		extractConditionsFromOr(left, conditions);
		extractConditionsFromOr(right, conditions);

		final Set<FilterCondition> uniqueConditions = removeDuplicateConditions(conditions);
		return buildOrTree(new ArrayList<>(uniqueConditions), 0, uniqueConditions.size() - 1);
	}

	private static void extractConditionsFromAnd(FilterCondition node, Set<FilterCondition> conditions) {
		if (node instanceof AndCondition andFilter) {
			extractConditionsFromAnd(andFilter.getLeft(), conditions);
			extractConditionsFromAnd(andFilter.getRight(), conditions);
			return;
		}

		conditions.add(node);
	}

	private static void extractConditionsFromOr(FilterCondition node, Set<FilterCondition> conditions) {
		if (node instanceof OrCondition orFilter) {
			extractConditionsFromOr(orFilter.getLeft(), conditions);
			extractConditionsFromOr(orFilter.getRight(), conditions);
			return;
		}

		conditions.add(node);
	}

	private static Set<FilterCondition> removeDuplicateConditions(Set<FilterCondition> conditions) {
		final Set<FilterCondition> uniqueConditions = new HashSet<>();
		final Map<String, Set<Object>> fieldValues = new HashMap<>();

		for (FilterCondition condition : conditions) {
			if (condition instanceof LogicalOperatorCondition comparisonFilter) {
				final String field = comparisonFilter.getField();
				final Object value = comparisonFilter.getValues()[0];

				if (comparisonFilter.getOperator() == LogicalOperatorCondition.Operator.EQ) {
					if (!fieldValues.containsKey(field)) {
						fieldValues.put(field, new HashSet<>());
					}

					final Set<Object> values = fieldValues.get(field);
					if (!values.contains(value)) {
						values.add(value);
						uniqueConditions.add(condition);
					}
				} else {
					uniqueConditions.add(condition);
				}
			} else {
				uniqueConditions.add(condition);
			}
		}

		return uniqueConditions;
	}

	private static boolean hasContradictoryConditions(Set<FilterCondition> conditions) {
		final Map<String, Set<Object>> fieldValues = new HashMap<>();

		for (FilterCondition condition : conditions) {
			if (condition instanceof LogicalOperatorCondition comparisonFilter) {
				final String field = comparisonFilter.getField();
				final Object value = comparisonFilter.getValues()[0];

				if (comparisonFilter.getOperator() == LogicalOperatorCondition.Operator.EQ) {
					if (!fieldValues.containsKey(field)) {
						fieldValues.put(field, new HashSet<>());
					}

					final Set<Object> values = fieldValues.get(field);
					if (values.isEmpty()) {
						values.add(value);
					} else if (!values.contains(value)) {
						return true; // contradictory EQ values on same field
					}
				}
			}
		}

		return false;
	}

	private static FilterCondition distributeAndOverOr(FilterCondition node) {
		if (node instanceof AndCondition andOperator) {
			// A AND (B OR C) -> (A AND B) OR (A AND C)
			if (andOperator.getRight() instanceof OrCondition orOperator) {
				return new OrCondition(
						distributeAndOverOr(new AndCondition(andOperator.getLeft(), orOperator.getLeft())),
						distributeAndOverOr(new AndCondition(andOperator.getLeft(), orOperator.getRight()))
				);
			}

			// (A OR B) AND C -> (A AND C) OR (B AND C)
			if (andOperator.getLeft() instanceof OrCondition orOperator) {
				return new OrCondition(
						distributeAndOverOr(new AndCondition(orOperator.getLeft(), andOperator.getRight())),
						distributeAndOverOr(new AndCondition(orOperator.getRight(), andOperator.getRight()))
				);
			}

			// Recurse both sides
			return new AndCondition(
					distributeAndOverOr(andOperator.getLeft()),
					distributeAndOverOr(andOperator.getRight())
			);
		}

		if (node instanceof OrCondition orOperator) {
			return new OrCondition(
					distributeAndOverOr(orOperator.getLeft()),
					distributeAndOverOr(orOperator.getRight())
			);
		}

		return node;
	}

	private static DnfResult optimizeDnfWithCompositeIndex(FilterCondition dnfNode,
	                                                       List<String> indexFields,
	                                                       AbstractIndexOperation<byte[][], CompositeTreeKey> index,
	                                                       SortOrderData sortOrder) {
		if (dnfNode instanceof OrCondition orOperator) {
			final DnfResult left = optimizeDnfWithCompositeIndex(orOperator.getLeft(), indexFields, index, sortOrder);
			final DnfResult right = optimizeDnfWithCompositeIndex(orOperator.getRight(), indexFields, index, sortOrder);
			return new DnfResult(
					new OrCondition(left.condition(), right.condition()),
					false
			);
		}

		if (dnfNode instanceof AndCondition andOperator) {
			return tryConvertToCompositeFilter(andOperator, indexFields, index, sortOrder);
		}

		// Single EQ on composite: only when ORDER BY matches next column (ASC).
		// Without ORDER BY prefer single-column index (posting-list + LIMIT), not prefix walk.
		if (dnfNode instanceof LogicalOperatorCondition loc
				&& loc.getOperator() == LogicalOperatorCondition.Operator.EQ
				&& indexFields.size() >= 2
				&& indexFields.getFirst().equals(loc.getField())
				&& sortOrder != null
				&& indexFields.get(1).equals(sortOrder.sortField())
				&& sortOrder.direction() == OrderDirection.ASC) {
			final byte[] prefixVal = SqlWireUtil.toGenericArray(loc.getValues()[0]);
			return new DnfResult(
					new CompositeIndexCondition(prefixVal, loc.getField(), index),
					true
			);
		}

		final boolean matchesSort = checkSortMatch(dnfNode, indexFields, sortOrder);
		return new DnfResult(dnfNode, matchesSort);
	}

	private static DnfResult tryConvertToCompositeFilter(AndCondition andOperator,
	                                                     List<String> indexFields,
	                                                     AbstractIndexOperation<byte[][], CompositeTreeKey> index,
	                                                     SortOrderData sortOrder) {
		if (hasContradictoryConditions(andOperator)) {
			return EMPTY_FILTER;
		}

		final Map<String, LogicalOperatorCondition> fieldToFilterMap = new HashMap<>();
		final List<FilterCondition> otherFilters = new ArrayList<>();

		extractFilters(andOperator, fieldToFilterMap, otherFilters);

		boolean hasAllIndexFields = true;
		for (String field : indexFields) {
			if (!fieldToFilterMap.containsKey(field)) {
				hasAllIndexFields = false;
				break;
			}
		}

		if (!hasAllIndexFields) {
			// EQ-prefix only when ORDER BY is on the next index column after the EQ prefix.
			if (sortOrder != null && indexFields.size() >= 2
					&& fieldToFilterMap.containsKey(indexFields.getFirst())
					&& fieldToFilterMap.get(indexFields.getFirst()).getOperator() == LogicalOperatorCondition.Operator.EQ
					&& otherFilters.isEmpty()) {
				int prefixLen = 0;
				for (String field : indexFields) {
					final LogicalOperatorCondition f = fieldToFilterMap.get(field);
					if (f == null || f.getOperator() != LogicalOperatorCondition.Operator.EQ) {
						break;
					}
					prefixLen++;
				}
				if (prefixLen >= 1 && prefixLen < indexFields.size()
						&& indexFields.get(prefixLen).equals(sortOrder.sortField())) {
					final String prefixField = indexFields.getFirst();
					final boolean orderMatched = sortOrder.direction() == OrderDirection.ASC;
					if (prefixLen == 1) {
						final byte[] prefixVal = SqlWireUtil.toGenericArray(fieldToFilterMap.get(prefixField).getValues()[0]);
						return new DnfResult(
								new CompositeIndexCondition(prefixVal, prefixField, index),
								orderMatched
						);
					}
					final byte[][] prefixKey = new byte[prefixLen][];
					for (int i = 0; i < prefixLen; i++) {
						prefixKey[i] = SqlWireUtil.toGenericArray(fieldToFilterMap.get(indexFields.get(i)).getValues()[0]);
					}
					return new DnfResult(
							new CompositeIndexCondition(prefixKey, index, true),
							orderMatched
					);
				}
			}
			return new DnfResult(andOperator, false);
		}

		for (String field : indexFields) {
			final LogicalOperatorCondition filter = fieldToFilterMap.get(field);
			if (filter.getOperator() != LogicalOperatorCondition.Operator.EQ) {
				return new DnfResult(andOperator, false);
			}
		}

		final byte[][] compositeKey = new byte[indexFields.size()][];
		for (int i = 0; i < indexFields.size(); i++) {
			final String field = indexFields.get(i);
			final LogicalOperatorCondition filter = fieldToFilterMap.get(field);
			compositeKey[i] = SqlWireUtil.toGenericArray(filter.getValues()[0]);
		}

		final CompositeIndexCondition compositeFilter = new CompositeIndexCondition(compositeKey, index);

		if (otherFilters.isEmpty()) {
			final boolean matchesSort = checkSortMatch(compositeFilter, indexFields, sortOrder);
			return new DnfResult(compositeFilter, matchesSort);
		}

		otherFilters.add(compositeFilter);
		return new DnfResult(
				buildAndTree(otherFilters, 0, otherFilters.size() - 1),
				false
		);
	}

	private static boolean checkSortMatch(FilterCondition condition,
	                                      List<String> indexFields,
	                                      SortOrderData sortOrder) {
		if (sortOrder == null) {
			return false;
		}

		if (!indexFields.getFirst().equals(sortOrder.sortField()) || sortOrder.direction() != OrderDirection.ASC) {
			return false;
		}

		if (condition instanceof CompositeIndexCondition) {
			return true;
		}

		if (condition instanceof AndCondition) {
			final Map<String, LogicalOperatorCondition> fieldToFilterMap = new HashMap<>();
			final List<FilterCondition> otherFilters = new ArrayList<>();
			extractFilters(condition, fieldToFilterMap, otherFilters);

			for (String field : indexFields) {
				if (!fieldToFilterMap.containsKey(field)) {
					return false;
				}

				final LogicalOperatorCondition filter = fieldToFilterMap.get(field);
				if (filter.getOperator() != LogicalOperatorCondition.Operator.EQ) {
					return false;
				}
			}

			return true;
		}

		return false;
	}

	private static boolean hasContradictoryConditions(FilterCondition node) {
		final Map<String, Set<Object>> fieldValues = new HashMap<>();
		return checkForContradictions(node, fieldValues);
	}

	private static boolean checkForContradictions(FilterCondition node, Map<String, Set<Object>> fieldValues) {
		if (node instanceof LogicalOperatorCondition logicalOperatorCondition) {
			final String field = logicalOperatorCondition.getField();
			final Object value = logicalOperatorCondition.getValues()[0];

			if (logicalOperatorCondition.getOperator() == LogicalOperatorCondition.Operator.EQ) {
				if (!fieldValues.containsKey(field)) {
					fieldValues.put(field, new HashSet<>());
				}

				final Set<Object> values = fieldValues.get(field);
				if (values.isEmpty()) {
					values.add(value);
					} else if (!values.contains(value)) {
						return true; // contradictory EQ values on same field
					}
			}
		}

		if (node instanceof AndCondition andOperator) {
			return checkForContradictions(andOperator.getLeft(), fieldValues) ||
					checkForContradictions(andOperator.getRight(), fieldValues);
		}

		if (node instanceof OrCondition orOperator) {
			final Map<String, Set<Object>> leftValues = new HashMap<>();
			final Map<String, Set<Object>> rightValues = new HashMap<>();

			final boolean leftContradiction = checkForContradictions(orOperator.getLeft(), leftValues);
			final boolean rightContradiction = checkForContradictions(orOperator.getRight(), rightValues);

			return leftContradiction || rightContradiction;
		}

		return false;
	}

	private static void extractFilters(FilterCondition node, Map<String, LogicalOperatorCondition> fieldToFilterMap, List<FilterCondition> otherFilters) {
		if (node instanceof LogicalOperatorCondition logicalOperatorCondition) {
			fieldToFilterMap.put(logicalOperatorCondition.getField(), logicalOperatorCondition);
			return;
		}

		if (node instanceof AndCondition andOperator) {
			extractFilters(andOperator.getLeft(), fieldToFilterMap, otherFilters);
			extractFilters(andOperator.getRight(), fieldToFilterMap, otherFilters);
			return;
		}

		otherFilters.add(node);
	}

	private static FilterCondition buildAndTree(List<FilterCondition> filters, int start, int end) {
		if (start > end) {
			return null;
		}

		if (start == end) {
			return filters.get(start);
		}

		final int mid = (start + end) / 2;
		final FilterCondition left = buildAndTree(filters, start, mid);
		final FilterCondition right = buildAndTree(filters, mid + 1, end);
		return new AndCondition(left, right);
	}

	private static FilterCondition buildOrTree(List<FilterCondition> filters, int start, int end) {
		if (start > end) {
			return null;
		}

		if (start == end) {
			return filters.get(start);
		}

		final int mid = (start + end) / 2;
		final FilterCondition left = buildOrTree(filters, start, mid);
		final FilterCondition right = buildOrTree(filters, mid + 1, end);
		return new OrCondition(left, right);
	}
}
