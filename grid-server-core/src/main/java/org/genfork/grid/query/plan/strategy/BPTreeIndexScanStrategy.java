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
package org.genfork.grid.query.plan.strategy;

import org.genfork.grid.mem.index.AbstractIndexOperation;
import org.genfork.grid.mem.index.IndexPointerRef;
import org.genfork.grid.mem.index.btree.AbstractBPTree;
import org.genfork.grid.mem.index.btree.IndexOperationResult;
import org.genfork.grid.mem.index.btree.SingleTreeKey;
import org.genfork.grid.query.plan.ExplainQuery;
import org.genfork.grid.query.plan.PagingData;
import org.genfork.grid.query.plan.SortOrderData;
import org.genfork.grid.query.plan.SortOrderData.OrderDirection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Ordered index walk with LIMIT early-stop ({@link AbstractBPTree#traversePagingOrder}).
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
public class BPTreeIndexScanStrategy implements QueryScanStrategy {
	private final boolean useSameOrderAscendingIndex;
	private final AbstractBPTree<byte[], SingleTreeKey> index;

	public BPTreeIndexScanStrategy(boolean useSameOrderAscendingIndex,
	                               AbstractIndexOperation<byte[], SingleTreeKey> index) {
		this.useSameOrderAscendingIndex = useSameOrderAscendingIndex;
		this.index = (AbstractBPTree<byte[], SingleTreeKey>) index;
	}

	@Override
	public List<byte[]> execute(ExplainQuery.QueryPlan queryPlan,
	                            IndexOperationResult operationResult,
	                            SortOrderData[] sortOrderData,
	                            PagingData pagingData) {
		final OrderDirection direction = sortOrderData[0].direction();
		final int limit = pagingData.limit();
		final int offset = pagingData.offset();

		final List<byte[]> result = new ArrayList<>(IndexPointerRef.listCapacity(limit));

		ExplainQuery.QueryPlanNode indexNode = null;
		if (queryPlan != null) {
			indexNode = ExplainQuery.startNode(queryPlan, "Index Sort", "Using " + index.getIndexName() + " for " + Arrays.toString(sortOrderData));
		}

		final Set<IndexPointerRef> filteredPointers = operationResult.getPointers();

		try {
			final boolean ascending = direction == OrderDirection.ASC;
			// Stops after offset+limit matches — does not walk the rest of the order index.
			index.traversePagingOrder(indexNode, filteredPointers, result::add, useSameOrderAscendingIndex, offset, limit, ascending);
			return result;
		} finally {
			if (queryPlan != null) {
				ExplainQuery.endNode(indexNode, -1, -1);

				queryPlan.completeCurrentNode();
			}
		}
	}
}
