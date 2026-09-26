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

import org.genfork.grid.catalog.TableCatalog.FunctionDef;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.mem.index.AbstractIndexOperation;
import org.genfork.grid.mem.index.btree.CompositeTreeKey;
import org.genfork.grid.mem.index.btree.IndexOperationResult;
import org.genfork.grid.mem.index.btree.SingleTreeKey;
import org.genfork.grid.query.filters.FilterCondition;
import org.genfork.grid.query.filters.PkIndexScanUtil;
import org.genfork.grid.query.plan.ExplainQuery;
import org.genfork.grid.serial.FieldMetaData;
import org.genfork.grid.serial.LogicalFieldCursor;
import org.genfork.grid.serial.SqlWireUtil;
import org.genfork.grid.serial.WireFieldCompare;
import org.genfork.grid.sql.udf.SqlUdfLookup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Residual WHERE {@code udf(args) op literal} — never index-pushed.
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
public final class UdfComparisonCondition implements FilterCondition {
	private final String functionName;
	/** Column name or {@code null} when the arg is a literal. */
	private final List<String> argColumnsOrNull;
	private final List<Object> argLiteralsOrNull;
	private final LogicalOperatorCondition.Operator operator;
	private final Object rightValue;

	public UdfComparisonCondition(
			String functionName,
			List<String> argColumnsOrNull,
			List<Object> argLiteralsOrNull,
			LogicalOperatorCondition.Operator operator,
			Object rightValue
	) {
		this.functionName = Objects.requireNonNull(functionName, "functionName");
		this.argColumnsOrNull = Collections.unmodifiableList(
				new ArrayList<>(Objects.requireNonNull(argColumnsOrNull, "argColumnsOrNull")));
		this.argLiteralsOrNull = Collections.unmodifiableList(
				new ArrayList<>(Objects.requireNonNull(argLiteralsOrNull, "argLiteralsOrNull")));
		if (this.argColumnsOrNull.size() != this.argLiteralsOrNull.size()) {
			throw new IllegalArgumentException("UDF arg column/literal lists size mismatch");
		}
		this.operator = Objects.requireNonNull(operator, "operator");
		this.rightValue = rightValue;
	}

	@Override
	public IndexOperationResult execute(
			Map<String, AbstractIndexOperation<byte[], SingleTreeKey>> property2Index,
			Map<List<String>, AbstractIndexOperation<byte[][], CompositeTreeKey>> compositeIndexes,
			FieldMetaData primaryKeyField,
			ExplainQuery.QueryPlan queryPlan
	) {
		return PkIndexScanUtil.searchAllPrimaryKeys(property2Index, compositeIndexes, primaryKeyField);
	}

	@Override
	public boolean matches(byte[] valueBytes, TableSchema schema) {
		if (valueBytes == null || schema == null) {
			return false;
		}
		final LogicalFieldCursor cursor = LogicalFieldCursor.open(schema, valueBytes);
		final Object[] args = new Object[argColumnsOrNull.size()];
		for (int i = 0; i < args.length; i++) {
			final String col = argColumnsOrNull.get(i);
			if (col == null) {
				args[i] = argLiteralsOrNull.get(i);
			} else {
				args[i] = cursor.read(schema.requireColumn(col).ordinal());
			}
		}
		return compare(invoke(args), rightValue, operator);
	}

	@Override
	public boolean matchesColumns(Function<String, Object> columnValue) {
		if (columnValue == null) {
			return false;
		}
		final Object[] args = new Object[argColumnsOrNull.size()];
		for (int i = 0; i < args.length; i++) {
			final String col = argColumnsOrNull.get(i);
			if (col == null) {
				args[i] = argLiteralsOrNull.get(i);
			} else {
				args[i] = columnValue.apply(col);
			}
		}
		return compare(invoke(args), rightValue, operator);
	}

	private Object invoke(Object[] args) {
		final FunctionDef def = SqlUdfLookup.require(functionName);
		return def.udf().apply(args);
	}

	private static boolean compare(Object left, Object right, LogicalOperatorCondition.Operator op) {
		final byte[] leftWire = SqlWireUtil.toGenericArray(left);
		final byte[] rightWire = SqlWireUtil.toGenericArray(right);
		if (WireFieldCompare.isNull(leftWire) && op != LogicalOperatorCondition.Operator.NE) {
			return false;
		}
		return switch (op) {
			case EQ -> WireFieldCompare.equals(leftWire, rightWire);
			case NE -> !WireFieldCompare.equals(leftWire, rightWire);
			case GT -> WireFieldCompare.compare(leftWire, rightWire) > 0;
			case GE -> WireFieldCompare.compare(leftWire, rightWire) >= 0;
			case LT -> WireFieldCompare.compare(leftWire, rightWire) < 0;
			case LE -> WireFieldCompare.compare(leftWire, rightWire) <= 0;
			default -> throw new IllegalArgumentException("unsupported UDF compare op: " + op);
		};
	}

	@Override
	public String toPlanPartString() {
		return "udf:" + functionName + " " + operator;
	}
}