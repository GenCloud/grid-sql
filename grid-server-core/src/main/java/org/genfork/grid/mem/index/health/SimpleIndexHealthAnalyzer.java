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
package org.genfork.grid.mem.index.health;

import org.genfork.grid.mem.index.btree.AbstractBPTree.IndexStat;

/**
 * Heuristic BPTree health signals from {@link IndexStat} (warn-only).
 *
 * @author: GenCloud
 * @date: 2025/04
 * @since: 1.0
 */
public class SimpleIndexHealthAnalyzer {
	private static final long SINGLE_LEAF_MIN_RECORDS = 100_000L;
	private static final double HOTSPOT_AVG_POINTERS_PER_KEY = 10_000.0;
	private static final double POOR_DISTRIBUTION_AVG_POINTERS_PER_KEY = 1_000.0;
	private static final long LARGE_INDEX_MIN_RECORDS = 1_000_000L;

	private static final double PRIORITY_SINGLE_LEAF = 0.4;
	private static final double PRIORITY_HOTSPOT = 0.3;
	private static final double PRIORITY_POOR_DISTRIBUTION = 0.2;
	private static final double PRIORITY_LARGE_INDEX = 0.1;
	private static final double PRIORITY_CAP = 1.0;

	public static IndexHealthMetrics calculateHealthMetrics(IndexStat indexStat) {
		final IndexHealthMetrics metrics = new IndexHealthMetrics();
		analyzeHealthQuick(metrics, indexStat);
		return metrics;
	}

	private static void analyzeHealthQuick(IndexHealthMetrics metrics, IndexStat indexStat) {
		final double avgPointersPerKey = indexStat.totalKeys > 0
				? (double) indexStat.totalRecords / indexStat.totalKeys
				: 0.0;
		final boolean singleLeaf = indexStat.leafNodeCount == 1 && indexStat.totalRecords > SINGLE_LEAF_MIN_RECORDS;
		final boolean hotspot = avgPointersPerKey > HOTSPOT_AVG_POINTERS_PER_KEY;
		final boolean poorDistribution = indexStat.leafNodeCount > 1
				&& avgPointersPerKey > POOR_DISTRIBUTION_AVG_POINTERS_PER_KEY;

		metrics.setSingleLeafScenario(singleLeaf);
		metrics.setHotspotScenario(hotspot);
		metrics.setPoorDistribution(poorDistribution);
		metrics.setRequiresOptimization(singleLeaf || hotspot || poorDistribution);

		double priority = 0.0;
		if (singleLeaf) {
			priority += PRIORITY_SINGLE_LEAF;
		}

		if (hotspot) {
			priority += PRIORITY_HOTSPOT;
		}

		if (poorDistribution) {
			priority += PRIORITY_POOR_DISTRIBUTION;
		}

		if (indexStat.totalRecords > LARGE_INDEX_MIN_RECORDS) {
			priority += PRIORITY_LARGE_INDEX;
		}

		metrics.setOptimizationPriority(Math.min(PRIORITY_CAP, priority));
	}

	/**
	 * Mutable health metrics DTO from a quick BPTree scan.
	 *
	 * @author: GenCloud
	 * @date: 2025/04
	 * @since: 1.0
	 */
	public static class IndexHealthMetrics {
		private boolean singleLeafScenario;
		private boolean hotspotScenario;
		private boolean poorDistribution;
		private boolean requiresOptimization;
		private double optimizationPriority;

		public boolean isSingleLeafScenario() {
			return singleLeafScenario;
		}

		public void setSingleLeafScenario(boolean singleLeafScenario) {
			this.singleLeafScenario = singleLeafScenario;
		}

		public boolean isHotspotScenario() {
			return hotspotScenario;
		}

		public void setHotspotScenario(boolean hotspotScenario) {
			this.hotspotScenario = hotspotScenario;
		}

		public boolean isPoorDistribution() {
			return poorDistribution;
		}

		public void setPoorDistribution(boolean poorDistribution) {
			this.poorDistribution = poorDistribution;
		}

		public boolean isRequiresOptimization() {
			return requiresOptimization;
		}

		public void setRequiresOptimization(boolean requiresOptimization) {
			this.requiresOptimization = requiresOptimization;
		}

		public double getOptimizationPriority() {
			return optimizationPriority;
		}

		public void setOptimizationPriority(double optimizationPriority) {
			this.optimizationPriority = optimizationPriority;
		}

		public String getHealthStatus() {
			if (singleLeafScenario) {
				return "CRITICAL - Single leaf scenario detected";
			} else if (hotspotScenario) {
				return "WARNING - Hotspot scenario detected";
			} else if (poorDistribution) {
				return "WARNING - Poor distribution detected";
			}

			return "HEALTHY";
		}
	}
}
