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
import java.util.function.Function;

import org.genfork.grid.catalog.ColumnDef;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.exceptions.ConditionValidationException;
import org.genfork.grid.mem.index.AbstractIndexOperation;
import org.genfork.grid.mem.index.btree.CompositeTreeKey;
import org.genfork.grid.mem.index.btree.IndexOperationResult;
import org.genfork.grid.mem.index.btree.SingleTreeKey;
import org.genfork.grid.query.filters.FilterCondition;
import org.genfork.grid.query.filters.PkIndexScanUtil;
import org.genfork.grid.query.plan.ExplainQuery;
import org.genfork.grid.serial.RowEncoder;
import org.genfork.grid.sql.SqlBuiltinEvalUtil;

/**
 * Residual {@code (col + literal) op literal} — CASE WHEN / filter match on decoded columns.
 * <p>
 * Not index-pushable; candidate set is all primary keys when used in a WHERE plan.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.1
 */
public final class NumericPlusComparisonCondition implements FilterCondition {
	private final String field;
	private final Object addend;
	private final LogicalOperatorCondition.Operator operator;
	private Object compareTo;

	public NumericPlusComparisonCondition(
			String field,
			Object addend,
			LogicalOperatorCondition.Operator operator,
			Object compareTo
	) {
		this.field = Objects.requireNonNull(field, "field");
		this.addend = Objects.requireNonNull(addend, "addend");
		this.operator = Objects.requireNonNull(operator, "operator");
		this.compareTo = compareTo;
	}

	@Override
	public boolean validate(TableSchema schema) {
		final ColumnDef col = schema.column(field);
		if (col == null) {
			throw new ConditionValidationException("criteria.mismatch-property", new String[]{field});
		}
		if (compareTo != null) {
			compareTo = RowEncoder.coerce(col, compareTo);
		}
		return true;
	}

	@Override
	public IndexOperationResult execute(
			Map<String, AbstractIndexOperation<byte[], SingleTreeKey>> property2Index,
			Map<List<String>, AbstractIndexOperation<byte[][], CompositeTreeKey>> compositeIndexes,
			List<String> primaryKeyColumns,
			ExplainQuery.QueryPlan queryPlan
	) {
		return PkIndexScanUtil.searchAllPrimaryKeys(property2Index, compositeIndexes, primaryKeyColumns);
	}

	@Override
	public boolean matchesColumns(Function<String, Object> columnValue) {
		if (columnValue == null) {
			return false;
		}
		try {
			final Object sum = SqlBuiltinEvalUtil.addNumeric(columnValue.apply(field), addend);
			if (!(sum instanceof Number leftNum) || !(compareTo instanceof Number rightNum)) {
				return false;
			}
			final double left = leftNum.doubleValue();
			final double right = rightNum.doubleValue();
			return switch (operator) {
				case EQ -> left == right;
				case NE -> left != right;
				case GT -> left > right;
				case GE -> left >= right;
				case LT -> left < right;
				case LE -> left <= right;
				default -> false;
			};
		} catch (RuntimeException ex) {
			return false;
		}
	}

	@Override
	public String toPlanPartString() {
		return "(" + field + "+" + addend + ")" + operator + compareTo;
	}
}
