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

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.genfork.grid.catalog.ColumnDef;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.exceptions.ConditionValidationException;
import org.genfork.grid.mem.index.AbstractIndexOperation;
import org.genfork.grid.mem.index.btree.CompositeTreeKey;
import org.genfork.grid.mem.index.btree.IndexOperationResult;
import org.genfork.grid.mem.index.btree.SingleTreeKey;
import org.genfork.grid.query.filters.FilterCondition;
import org.genfork.grid.query.plan.ExplainQuery;
import org.genfork.grid.serial.FieldMetaData;
import org.genfork.grid.serial.LogicalFieldCursor;
import org.genfork.grid.serial.SqlWireUtil;

/**
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
public class IsNullCondition implements FilterCondition {
	private final String field;

	public IsNullCondition(String field) {
		this.field = field;
	}

	@Override
	public boolean validate(TableSchema schema) {
		if (schema.column(field) == null) {
			throw new ConditionValidationException(
					"criteria.mismatch-property",
					new String[]{field}
			);
		}

		return true;
	}

	@Override
	public IndexOperationResult execute(Map<String, AbstractIndexOperation<byte[], SingleTreeKey>> property2Index,
	                                    Map<List<String>, AbstractIndexOperation<byte[][], CompositeTreeKey>> compositeIndexes,
	                                    FieldMetaData primaryKeyField, ExplainQuery.QueryPlan queryPlan) {
		final AbstractIndexOperation<byte[], SingleTreeKey> index = property2Index.get(field);
		if (index == null) {
			return IndexOperationResult.EMPTY;
		}

		final SingleTreeKey key = index.createKey(
				field,
				SqlWireUtil.getNullPtr()
		);

		return index.searchEq(key);
	}

	@Override
	public boolean matches(byte[] valueBytes, TableSchema schema) {
		if (valueBytes == null || schema == null) {
			return true;
		}
		final ColumnDef col = schema.column(field);
		if (col == null) {
			return true;
		}
		try {
			final LogicalFieldCursor cursor = LogicalFieldCursor.open(schema, valueBytes);
			return Arrays.equals(cursor.indexKeyBytes(col.ordinal()), SqlWireUtil.getNullPtr());
		} catch (RuntimeException ex) {
			return true;
		}
	}

	@Override
	public boolean matchesColumns(Function<String, Object> columnValue) {
		return columnValue == null || columnValue.apply(field) == null;
	}

	@Override
	public String toPlanPartString() {
		return "(" + field + " IS NULL)";
	}
}
