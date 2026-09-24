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
package org.genfork.grid.query.filters.impl;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import com.google.common.collect.Sets;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.mem.index.AbstractIndexOperation;
import org.genfork.grid.mem.index.IndexPointerRef;
import org.genfork.grid.mem.index.bitmap.Bitmap;
import org.genfork.grid.mem.index.btree.CompositeTreeKey;
import org.genfork.grid.mem.index.btree.IndexOperationResult;
import org.genfork.grid.mem.index.btree.SingleTreeKey;
import org.genfork.grid.query.filters.FilterCondition;
import org.genfork.grid.query.plan.ExplainQuery;
import org.genfork.grid.serial.FieldMetaData;

/**
 * @author: GenCloud @date: 2025/09 @since: 1.0
 */
public class OrCondition implements FilterCondition {
	private final FilterCondition left;
	private final FilterCondition right;

	public OrCondition(FilterCondition left, FilterCondition right) {
		this.left = left;
		this.right = right;
	}

	@Override
	public boolean validate(TableSchema schema) {
		return left.validate(schema) && right.validate(schema);
	}

	@Override
	public IndexOperationResult execute(Map<String, AbstractIndexOperation<byte[], SingleTreeKey>> property2Index, Map<List<String>, AbstractIndexOperation<byte[][], CompositeTreeKey>> compositeIndexes, FieldMetaData primaryKeyField, ExplainQuery.QueryPlan queryPlan) {
		ExplainQuery.QueryPlanNode queryPlanNode = null;
		if (queryPlan != null) {
			queryPlanNode = ExplainQuery.startNode(queryPlan, "CONJUNCTION FILTER", "none");
		}
		long rowsProcessed = 0;
		long rowsReturned = 0;
		try {
			final IndexOperationResult leftResult = left.execute(property2Index, compositeIndexes, primaryKeyField, queryPlan);
			final IndexOperationResult rightResult = right.execute(property2Index, compositeIndexes, primaryKeyField, queryPlan);
			if (leftResult == null || rightResult == null) {
				return IndexOperationResult.EMPTY;
			}
			rowsProcessed += leftResult.getRowsProcessed() + rightResult.getRowsProcessed();
			if (leftResult.hasBitmap() && rightResult.hasBitmap() && leftResult.getBitmapPositions() == rightResult.getBitmapPositions()) {
				final Bitmap combined = leftResult.getBitmap().or(rightResult.getBitmap());
				final IndexOperationResult operationResult = new IndexOperationResult();
				operationResult.setBitmap(combined);
				operationResult.setBitmapPositions(leftResult.getBitmapPositions());
				operationResult.expandBitmapPointers();
				rowsReturned = operationResult.getSize();
				operationResult.addProcessed(rowsProcessed);
				return operationResult;
			}
			final Set<IndexPointerRef> left = leftResult.pointersOrExpand();
			final Set<IndexPointerRef> right = rightResult.pointersOrExpand();
			final Set<IndexPointerRef> union = Sets.union(left, right);
			rowsReturned = union.size();
			final IndexOperationResult operationResult = new IndexOperationResult();
			operationResult.setPointers(union);
			operationResult.addProcessed(rowsProcessed);
			operationResult.setSize(rowsReturned);
			return operationResult;
		} finally {
			if (queryPlan != null) {
				ExplainQuery.endNode(queryPlanNode, rowsProcessed, rowsReturned);
				queryPlan.completeCurrentNode();
			}
		}
	}

	@Override
	public boolean matches(byte[] valueBytes, TableSchema schema) {
		return left.matches(valueBytes, schema) || right.matches(valueBytes, schema);
	}

	@Override
	public boolean matchesColumns(Function<String, Object> columnValue) {
		return left.matchesColumns(columnValue) || right.matchesColumns(columnValue);
	}

	@Override
	public String toPlanPartString() {
		return "(" + left.toPlanPartString() + " OR " + right.toPlanPartString() + ")";
	}

	public FilterCondition getLeft() {
		return this.left;
	}

	public FilterCondition getRight() {
		return this.right;
	}
}
