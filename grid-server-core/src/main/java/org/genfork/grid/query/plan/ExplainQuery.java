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
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import reactor.util.context.ContextView;

/**
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
public final class ExplainQuery {
	public static QueryPlanNode startNode(ContextView contextView, String nodeType, String description) {
		final QueryPlan queryPlan = contextView.get(QueryPlan.class);
		return startNode(queryPlan, nodeType, description);
	}

	public static QueryPlanNode startNode(QueryPlan queryPlan, String nodeType, String description) {
		final QueryPlanNode node = new QueryPlanNode(nodeType, description);
		queryPlan.addNode(node);
		return node;
	}

	public static void endNode(QueryPlanNode node, long rowsProcessed, long rowsReturned) {
		node.endTime = System.nanoTime();
		if (node.rowsProcessed == 0) {
			node.rowsProcessed = rowsProcessed;
		}
		if (node.rowsReturned == 0) {
			node.rowsReturned = rowsReturned;
		}
	}

	public static void recordIndexUsage(QueryPlanNode node, String indexName, boolean hit) {
		IndexStats indexStats = node.indexStats.computeIfAbsent(indexName, _ -> new IndexStats());
		indexStats.recordUsage(hit);
	}

	public static void recordMemoryUsage(QueryPlanNode node, long bytes) {
		node.memoryUsed += bytes;
	}

	/**
	 * Attach planner estimated cost (relative units from {@link QueryCardinality}).
	 */
	public static void recordEstimatedCost(QueryPlanNode node, double estimatedCost) {
		if (node != null && estimatedCost >= 0.0) {
			node.estimatedCost = estimatedCost;
		}
	}

	/**
	 * Attach estimated cost on the plan root (surfaced in EXPLAIN ANALYZE SUMMARY).
	 */
	public static void recordEstimatedCost(QueryPlan plan, double estimatedCost) {
		if (plan == null) {
			return;
		}
		plan.estimatedCost = estimatedCost;
		if (plan.rootNode != null) {
			recordEstimatedCost(plan.rootNode, estimatedCost);
		}
	}

	/**
	 * Optionally refine a plan node description (e.g. Bitmap And).
	 */
	public static void annotateNode(QueryPlanNode node, String description) {
		if (node != null && description != null && !description.isBlank()) {
			node.description = description;
		}
	}

	/**
	 * Mark analyze plan finished (SQL {@code EXPLAIN ANALYZE}).
	 */
	public static void finishAnalyzePlan(QueryPlan plan, long rowsReturned) {
		if (plan.rootNode != null) {
			plan.rootNode.rowsReturned = rowsReturned;
		}
		plan.executionTimeNanos = System.nanoTime() - plan.startTimeNanos;
		plan.success = plan.error == null;
	}

	public static void markAnalyzeFailed(QueryPlan plan, String message) {
		plan.success = false;
		plan.error = message;
		plan.executionTimeNanos = System.nanoTime() - plan.startTimeNanos;
	}

	/**
	 * Flatten plan tree to SQL result rows: kind, strategy, detail.
	 */
	public static List<Object[]> toResultRows(QueryPlan plan, String strategy) {
		final List<Object[]> rows = new ArrayList<>(8);
		collectResultRows(plan.rootNode, strategy, rows);
		rows.add(new Object[] {"SUMMARY", strategy, String.format(Locale.ROOT, "timeMs=%.3f success=%s rows=%d%s%s", plan.executionTimeNanos / 1000000.0, plan.success, plan.rootNode == null ? 0 : plan.rootNode.rowsReturned, plan.estimatedCost >= 0.0 ? String.format(Locale.ROOT, " estCost=%.2f", plan.estimatedCost) : "", plan.error == null ? "" : " error=" + plan.error)});
		return rows;
	}

	private static void collectResultRows(QueryPlanNode node, String strategy, List<Object[]> rows) {
		if (node == null) {
			return;
		}
		if (node.nodeType != null && !"QUERY".equals(node.nodeType)) {
			final StringBuilder detail = new StringBuilder();
			if (node.description != null) {
				detail.append(node.description);
			}
			detail.append(String.format(Locale.ROOT, " timeMs=%.3f rows=%d", node.getExecutionTimeMs(), node.rowsReturned));
			if (node.estimatedCost >= 0.0) {
				detail.append(String.format(Locale.ROOT, " estCost=%.2f", node.estimatedCost));
			}
			rows.add(new Object[] {node.nodeType, strategy, detail.toString().trim()});
		}
		for (QueryPlanNode child : node.children) {
			collectResultRows(child, strategy, rows);
		}
	}


	public static class QueryPlan {
		final long startTimeNanos = System.nanoTime();
		String name;
		QueryPlanNode rootNode;
		long executionTimeNanos;
		boolean success;
		String error;
		/**
		 * Planner estimated cost (−1 = unset).
		 */
		double estimatedCost = -1.0;
		final Deque<QueryPlanNode> nodeStack = new ConcurrentLinkedDeque<>();

		public QueryPlan(String name) {
			this.name = name;
			rootNode = new QueryPlanNode("QUERY", "");
			nodeStack.push(rootNode);
		}

		public void addNode(QueryPlanNode node) {
			final QueryPlanNode parent = nodeStack.peek();
			assert parent != null;
			parent.children.add(node);
			nodeStack.push(node);
		}

		public void completeCurrentNode() {
			if (nodeStack.size() > 1) {
				nodeStack.pop();
			}
		}

		public QueryPlanNode getRootNode() {
			return this.rootNode;
		}

		/**
		 * Planner estimated cost (−1 = unset).
		 */
		public double getEstimatedCost() {
			return this.estimatedCost;
		}
	}


	public static class QueryPlanNode {
		String nodeType;
		String description;
		long startTime;
		long endTime;
		public long rowsProcessed;
		long rowsReturned;
		public int loops = 1;
		public long memoryUsed;
		/**
		 * Planner estimated cost (−1 = unset).
		 */
		public double estimatedCost = -1.0;
		Map<String, IndexStats> indexStats = new HashMap<>();
		List<QueryPlanNode> children = new ArrayList<>();

		public QueryPlanNode(String nodeType, String description) {
			this.nodeType = nodeType;
			this.description = description;
			this.startTime = System.nanoTime();
		}

		public double getExecutionTimeMs() {
			return (endTime - startTime) / 1000000.0;
		}
	}


	private static class IndexStats {
		long scanCount;
		long hitCount;
		long missCount;

		void recordUsage(boolean hit) {
			scanCount++;
			if (hit) {
				hitCount++;
			} else {
				missCount++;
			}
		}
	}

	private ExplainQuery() {
		throw new java.lang.UnsupportedOperationException("This is a utility class and cannot be instantiated");
	}
}
