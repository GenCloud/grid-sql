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
import java.util.Objects;

import org.genfork.grid.mem.index.AbstractIndexOperation;
import org.genfork.grid.mem.index.btree.CompositeTreeKey;
import org.genfork.grid.mem.index.btree.IndexOperationResult;
import org.genfork.grid.mem.index.btree.SingleTreeKey;
import org.genfork.grid.query.filters.FilterCondition;
import org.genfork.grid.query.plan.ExplainQuery;
import org.genfork.grid.query.plan.QueryPagingContext;
import org.genfork.grid.serial.FieldMetaData;
import org.genfork.grid.utils.SerialUtil;
/**
 * Full or prefix EQ against a composite BPTree. Prefix keys use partial match so leaf walk
 * order follows the remaining index columns (usable as ORDER BY without re-sort).
 * With {@link QueryPagingContext} LIMIT, {@link #executeLimited} stops after N postings
 * (TD-PERF-002 EQ+ORDER+LIMIT early cut when leaf order matches ORDER BY).
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
public class CompositeIndexCondition implements FilterCondition {
	private final byte[][] compositeKey;
	private final String prefixField;
	private final byte[] prefixValue;
	private final boolean prefixMulti;
	private final AbstractIndexOperation<byte[][], CompositeTreeKey> index;
	private volatile CompositeTreeKey cachedKey;

	public CompositeIndexCondition(byte[][] compositeKey, AbstractIndexOperation<byte[][], CompositeTreeKey> index) {
		this(compositeKey, index, false);
	}

	/** Multi-column EQ prefix (partial seek on first {@code compositeKey.length} columns). */
	public CompositeIndexCondition(byte[][] compositeKey,
	                               AbstractIndexOperation<byte[][], CompositeTreeKey> index,
	                               boolean prefixMulti) {
		this.compositeKey = compositeKey;
		this.prefixField = null;
		this.prefixValue = null;
		this.prefixMulti = prefixMulti;
		this.index = index;
	}

	/** Single-column EQ prefix via {@link AbstractIndexOperation#createKey(String, byte[])}. */
	public CompositeIndexCondition(byte[] prefixValue,
	                               String prefixField,
	                               AbstractIndexOperation<byte[][], CompositeTreeKey> index) {
		this.compositeKey = null;
		this.prefixField = prefixField;
		this.prefixValue = prefixValue;
		this.prefixMulti = false;
		this.index = index;
	}

	/** Index tree referenced by this composite EQ / prefix condition (EXPLAIN plan probe). */
	public AbstractIndexOperation<byte[][], CompositeTreeKey> indexOperation() {
		return index;
	}

	private CompositeTreeKey resolveKey() {
		CompositeTreeKey key = cachedKey;
		if (key != null) {
			return key;
		}
		if (prefixField != null) {
			key = index.createKey(prefixField, prefixValue);
		} else if (prefixMulti && compositeKey != null) {
			key = new CompositeTreeKey(compositeKey, 0);
		} else {
			key = index.createKey(compositeKey);
		}
		cachedKey = key;
		return key;
	}

	@Override
	public IndexOperationResult execute(Map<String, AbstractIndexOperation<byte[], SingleTreeKey>> property2Index,
	                                    Map<List<String>, AbstractIndexOperation<byte[][], CompositeTreeKey>> compositeIndexes,
	                                    List<String> primaryKeyColumns, ExplainQuery.QueryPlan queryPlan) {
		return executeLimited(queryPlan,
				QueryPagingContext.maxPointersHint());
	}

	/** Direct LIMIT push-down (avoids ScopedValue on the hottest EQ+LIMIT path). */
	public IndexOperationResult executeLimited(ExplainQuery.QueryPlan queryPlan,
                                               int maxPointers) {

		ExplainQuery.QueryPlanNode queryPlanNode = null;
		if (queryPlan != null) {
			queryPlanNode = ExplainQuery.startNode(queryPlan, "Optimize Index Scan", "Using " + index.getIndexName());
		}

		long rowsProcessed = 0;
		long rowsReturned = 0;

		try {
			final CompositeTreeKey key = resolveKey();
			final IndexOperationResult operationResult = maxPointers > 0
					? index.searchEq(key, maxPointers)
					: index.searchEq(key);

			rowsProcessed += operationResult.getRowsProcessed();
			rowsReturned += operationResult.getSize();

			return operationResult;
		} finally {
			if (queryPlan != null) {
				ExplainQuery.endNode(queryPlanNode, rowsProcessed, rowsReturned);
				queryPlan.completeCurrentNode();
			}
		}
	}

	@Override
	public String toPlanPartString() {
		if (prefixField != null) {
			return "(" + prefixField + " = " + SerialUtil.readUtf8(prefixValue, 0) + " /* composite prefix */)";
		}
		final StringBuilder sb = new StringBuilder("(");
		for (int i = 0; i < Objects.requireNonNull(compositeKey).length; i++) {
			if (i > 0) {
				sb.append(" AND ");
			}
			sb.append("field").append(i).append(" = ").append(SerialUtil.readUtf8(compositeKey[i], 0));
		}
		sb.append(")");
		return sb.toString();
	}
}
