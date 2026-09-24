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

import org.genfork.grid.mem.index.AbstractIndexOperation;
import org.genfork.grid.mem.index.btree.CompositeTreeKey;
import org.genfork.grid.mem.index.btree.IndexOperationResult;
import org.genfork.grid.mem.index.btree.SingleTreeKey;
import org.genfork.grid.query.filters.FilterCondition;
import org.genfork.grid.query.plan.ExplainQuery;
import org.genfork.grid.serial.FieldMetaData;

/**
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
public class AlwaysTrueCondition implements FilterCondition {
	private static final AlwaysTrueCondition INSTANCE = new AlwaysTrueCondition();

	public static AlwaysTrueCondition getInstance() {
		return INSTANCE;
	}

	@Override
	public IndexOperationResult execute(Map<String, AbstractIndexOperation<byte[], SingleTreeKey>> property2Index,
	                                    Map<List<String>, AbstractIndexOperation<byte[][], CompositeTreeKey>> compositeIndexes,
	                                    FieldMetaData primaryKeyField,
	                                    ExplainQuery.QueryPlan queryPlan) {
		if (primaryKeyField == null || property2Index == null) {
			return IndexOperationResult.EMPTY;
		}
		final AbstractIndexOperation<byte[], SingleTreeKey> index = property2Index.get(primaryKeyField.getName());
		if (index == null) {
			return IndexOperationResult.EMPTY;
		}
		return index.searchAll();
	}

	@Override
	public String toPlanPartString() {
		return Boolean.TRUE.toString();
	}
}
