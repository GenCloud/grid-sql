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
import java.util.function.Function;
import com.google.common.collect.Sets;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.mem.index.AbstractIndexOperation;
import org.genfork.grid.mem.index.IndexPointerRef;
import org.genfork.grid.mem.index.btree.CompositeTreeKey;
import org.genfork.grid.mem.index.btree.IndexOperationResult;
import org.genfork.grid.mem.index.btree.SingleTreeKey;
import org.genfork.grid.query.filters.FilterCondition;
import org.genfork.grid.query.plan.ExplainQuery;
import org.genfork.grid.serial.FieldMetaData;

/**
 * @author: GenCloud @date: 2025/09 @since: 1.0
 */
public class NotCondition implements FilterCondition {
	private final FilterCondition child;

	public NotCondition(FilterCondition child) {
		this.child = child;
	}

	@Override
	public boolean validate(TableSchema schema) {
		return child.validate(schema);
	}

	@Override
	public IndexOperationResult execute(Map<String, AbstractIndexOperation<byte[], SingleTreeKey>> property2Index, Map<List<String>, AbstractIndexOperation<byte[][], CompositeTreeKey>> compositeIndexes, FieldMetaData primaryKeyField, ExplainQuery.QueryPlan queryPlan) {
		final IndexOperationResult childResult = child.execute(property2Index, compositeIndexes, primaryKeyField, queryPlan);
		if (childResult == null) {
			return null;
		}
		final AbstractIndexOperation<byte[], SingleTreeKey> index = property2Index.get(primaryKeyField.getName());
		final IndexOperationResult allResult = index.searchAll();
		final Sets.SetView<IndexPointerRef> diff = Sets.difference(allResult.getPointers(), childResult.getPointers());
		final IndexOperationResult result = new IndexOperationResult();
		result.setPointers(diff);
		result.setRowsProcessed(allResult.getRowsProcessed() - childResult.getRowsProcessed());
		result.setSize(allResult.getSize() - childResult.getSize());
		return result;
	}

	@Override
	public boolean matches(byte[] valueBytes, TableSchema schema) {
		return !child.matches(valueBytes, schema);
	}

	@Override
	public boolean matchesColumns(Function<String, Object> columnValue) {
		return !child.matchesColumns(columnValue);
	}

	@Override
	public String toPlanPartString() {
		return "(NOT " + child.toPlanPartString() + ")";
	}

	public FilterCondition getChild() {
		return this.child;
	}
}
