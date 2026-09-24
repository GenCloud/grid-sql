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

import org.genfork.grid.mem.index.IndexPointerRef;
import org.genfork.grid.mem.index.btree.IndexOperationResult;
import org.genfork.grid.query.plan.ExplainQuery;
import org.genfork.grid.query.plan.PagingData;
import org.genfork.grid.query.plan.SortOrderData;

import java.util.List;
import java.util.Set;

/**
 * Page already-filtered pointers (including composite leaf order that matches ORDER BY).
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
public class TableScanStrategy implements QueryScanStrategy {
	public static final TableScanStrategy INSTANCE = new TableScanStrategy();

	/** Unbounded page when statement has no positive LIMIT. */
	private static final int UNBOUNDED_PAGE = Integer.MAX_VALUE;

	@Override
	public List<byte[]> execute(ExplainQuery.QueryPlan queryPlan,
	                            IndexOperationResult operationResult,
	                            SortOrderData[] sortOrderData,
	                            PagingData pagingData) {
		final int pageLimit = pagingData == null ? -1 : pagingData.limit();
		final int pageOffset = pagingData == null ? 0 : Math.max(0, pagingData.offset());

		if (pagingData != null && pageLimit == 0) {
			return List.of();
		}

		final Set<IndexPointerRef> filteredPointers = operationResult.getPointers();
		ExplainQuery.QueryPlanNode queryPlanNode = null;
		if (queryPlan != null) {
			queryPlanNode = ExplainQuery.startNode(queryPlan, "NO SCAN", "none");
		}

		try {
			// Early LIMIT cut: pageKeys stops after N keys (ordered leaf scans already truncated).
			return IndexPointerRef.pageKeys(
					filteredPointers,
					pageOffset,
					pageLimit > 0 ? pageLimit : UNBOUNDED_PAGE);
		} finally {
			if (queryPlan != null) {
				final int n = filteredPointers == null ? 0 : filteredPointers.size();
				ExplainQuery.endNode(queryPlanNode, n, n);
				queryPlan.completeCurrentNode();
			}
		}
	}
}
