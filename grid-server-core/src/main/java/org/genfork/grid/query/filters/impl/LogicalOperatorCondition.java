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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.genfork.grid.catalog.ColumnDef;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.exceptions.ConditionValidationException;
import org.genfork.grid.mem.index.AbstractIndexOperation;
import org.genfork.grid.mem.index.IndexPointerRef;
import org.genfork.grid.mem.index.bitmap.GridBitmapIndex;
import org.genfork.grid.mem.index.btree.CompositeTreeKey;
import org.genfork.grid.mem.index.btree.IndexOperationResult;
import org.genfork.grid.mem.index.btree.SingleTreeKey;
import org.genfork.grid.query.filters.FilterCondition;
import org.genfork.grid.query.plan.ExplainQuery;
import org.genfork.grid.query.plan.QueryPagingContext;
import org.genfork.grid.replication.snapshot.sealed.SealedFallbackBitmap;
import org.genfork.grid.serial.FieldMetaData;
import org.genfork.grid.serial.LogicalFieldCursor;
import org.genfork.grid.serial.RowEncoder;
import org.genfork.grid.serial.SqlWireUtil;
import org.genfork.grid.serial.WireFieldCompare;

/**
 * Indexed + residual predicates on wire field bytes ({@link LogicalFieldCursor#indexKeyBytes}).
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
public class LogicalOperatorCondition implements FilterCondition {

	public enum Operator {
		EQ, NE, GT, GE, LT, LE, LIKE, BETWEEN, IN;
	}

	private final String field;
	private final Operator operator;
	private final Object[] values;

	public LogicalOperatorCondition(String field, Operator operator, Object... values) {
		this.field = field;
		this.operator = operator;
		this.values = values;
	}

	@Override
	public boolean validate(TableSchema schema) {
		final ColumnDef col = schema.column(field);
		if (col == null) {
			throw new ConditionValidationException("criteria.mismatch-property", new String[] {field});
		}
		// Coerce literals to column SqlType so wire EQ/RANGE matches indexKeyBytes (INT vs DOUBLE etc.).
		for (int i = 0; i < values.length; i++) {
			if (values[i] != null) {
				values[i] = RowEncoder.coerce(col, values[i]);
			}
		}
		final Class<?> type = col.javaType();
		if (values[0] != null) {
			if (!match(type, values[0].getClass())) {
				throw new ConditionValidationException("criteria.mismatch-type", new String[] {field, type.getSimpleName()});
			}
		}
		return true;
	}

	@Override
	public IndexOperationResult execute(Map<String, AbstractIndexOperation<byte[], SingleTreeKey>> property2Index, Map<List<String>, AbstractIndexOperation<byte[][], CompositeTreeKey>> compositeIndexes, FieldMetaData primaryKeyField, ExplainQuery.QueryPlan queryPlan) {
		final AbstractIndexOperation<byte[], SingleTreeKey> index = property2Index.get(field);
		if (index == null) {
			// Unindexed column: candidate set = all PK rows; residual matches() applied by caller.
			if (primaryKeyField != null) {
				final AbstractIndexOperation<byte[], SingleTreeKey> pk = property2Index.get(primaryKeyField.getName());
				if (pk != null) {
					return pk.searchAll();
				}
			}
			return IndexOperationResult.EMPTY;
		}
		ExplainQuery.QueryPlanNode queryPlanNode = null;
		if (queryPlan != null) {
			final String scanKind = SealedFallbackBitmap.isBitmapIndex(index) && (operator == Operator.EQ || operator == Operator.IN) ? "Bitmap Index Scan" : "Index Scan";
			queryPlanNode = ExplainQuery.startNode(queryPlan, scanKind, "Using " + index.getIndexName());
		}
		long rowsProcessed = 0;
		long rowsReturned = 0;
		try {
			final IndexOperationResult operationResult = switch (operator) {
				case EQ -> {
					final SingleTreeKey eqKey = createKey(index, values[0]);
					final int max = QueryPagingContext.maxPointersHint();
					yield max > 0 ? index.searchEq(eqKey, max) : index.searchEq(eqKey);
				}
				case NE -> index.searchNotEq(createKey(index, values[0]));
				case GT -> index.searchGreaterThan(createKey(index, values[0]));
				case GE -> index.searchGreaterThanOrEqual(createKey(index, values[0]));
				case LT -> index.searchLessThan(createKey(index, values[0]));
				case LE -> index.searchLessThanOrEqual(createKey(index, values[0]));
				case LIKE -> {
					final IndexOperationResult like = index.searchLike(createKey(index, values[0]));
					yield like == null ? IndexOperationResult.EMPTY : like;
				}
				case BETWEEN -> index.searchRange(createKey(index, values[0]), createKey(index, values[1]));
				case IN -> {
					if (index instanceof SealedFallbackBitmap sealedBitmap) {
						final ArrayList<SingleTreeKey> inKeys = new ArrayList<>(values.length);
						for (Object value : values) {
							inKeys.add(createKey(sealedBitmap, value));
						}
						yield sealedBitmap.searchIn(inKeys);
					}
					if (index instanceof GridBitmapIndex bitmapIndex) {
						final ArrayList<SingleTreeKey> inKeys = new ArrayList<>(values.length);
						for (Object value : values) {
							inKeys.add(createKey(bitmapIndex, value));
						}
						yield bitmapIndex.searchIn(inKeys);
					}
					final Set<IndexPointerRef> union = new HashSet<>();
					long processed = 0;
					long size = 0;
					for (Object value : values) {
						final IndexOperationResult result = index.searchEq(createKey(index, value));
						if (result != null) {
							union.addAll(result.getPointers());
							processed += result.getRowsProcessed();
							size += result.getSize();
						}
					}
					final IndexOperationResult result = new IndexOperationResult();
					result.setPointers(union);
					result.addProcessed(processed);
					result.addSize(size);
					yield result;
				}
			};
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
	public boolean matches(byte[] valueBytes, TableSchema schema) {
		if (valueBytes == null || schema == null) {
			return false;
		}
		final ColumnDef col = schema.column(field);
		if (col == null) {
			return false;
		}
		try {
			final LogicalFieldCursor cursor = LogicalFieldCursor.open(schema, valueBytes);
			final byte[] actual = cursor.indexKeyBytes(col.ordinal());
			return matchWire(actual, operator, values);
		} catch (RuntimeException ex) {
			return false;
		}
	}

	@Override
	public boolean matchesColumns(Function<String, Object> columnValue) {
		if (columnValue == null) {
			return false;
		}
		try {
			final Object actual = columnValue.apply(field);
			final byte[] actualWire = SqlWireUtil.toGenericArray(actual);
			return matchWire(actualWire, operator, values);
		} catch (RuntimeException ex) {
			return false;
		}
	}

	private static boolean matchWire(byte[] actual, Operator op, Object[] values) {
		if (WireFieldCompare.isNull(actual) && op != Operator.NE && op != Operator.IN) {
			return false;
		}
		return switch (op) {
			case EQ -> WireFieldCompare.equals(actual, SqlWireUtil.toGenericArray(values[0]));
			case NE -> !WireFieldCompare.equals(actual, SqlWireUtil.toGenericArray(values[0]));
			case GT -> WireFieldCompare.compare(actual, SqlWireUtil.toGenericArray(values[0])) > 0;
			case GE -> WireFieldCompare.compare(actual, SqlWireUtil.toGenericArray(values[0])) >= 0;
			case LT -> WireFieldCompare.compare(actual, SqlWireUtil.toGenericArray(values[0])) < 0;
			case LE -> WireFieldCompare.compare(actual, SqlWireUtil.toGenericArray(values[0])) <= 0;
			case LIKE -> WireFieldCompare.likeContains(actual, String.valueOf(values[0]));
			case BETWEEN -> {
				final int lo = WireFieldCompare.compare(actual, SqlWireUtil.toGenericArray(values[0]));
				final int hi = WireFieldCompare.compare(actual, SqlWireUtil.toGenericArray(values[1]));
				yield lo >= 0 && hi <= 0;
			}
			case IN -> {
				for (Object value : values) {
					if (WireFieldCompare.equals(actual, SqlWireUtil.toGenericArray(value))) {
						yield true;
					}
				}
				yield false;
			}
		};
	}

	private SingleTreeKey createKey(AbstractIndexOperation<byte[], SingleTreeKey> index, Object value) {
		return index.createKey(field, SqlWireUtil.toGenericArray(value));
	}

	@Override
	public String toPlanPartString() {
		final String operatorStr = switch (operator) {
			case EQ -> "=";
			case NE -> "!=";
			case GT -> ">";
			case GE -> ">=";
			case LT -> "<";
			case LE -> "<=";
			case LIKE -> "LIKE";
			case BETWEEN -> "BETWEEN";
			case IN -> "IN";
		};
		if (operator == Operator.BETWEEN && values.length == 2) {
			return "(" + field + " " + operatorStr + " " + formatValue(values[0]) + " AND " + formatValue(values[1]) + ")";
		}
		if (operator == Operator.IN) {
			final String valuesStr = Arrays.stream(values).map(this::formatValue).collect(Collectors.joining(", "));
			return "(" + field + " " + operatorStr + " (" + valuesStr + "))";
		}
		if (values.length > 0) {
			return "(" + field + " " + operatorStr + " " + formatValue(values[0]) + ")";
		}
		return "(" + field + " " + operatorStr + ")";
	}

	private String formatValue(Object value) {
		return switch (value) {
			case null -> "NULL";
			case String _ -> "\'" + value.toString().replace("\'", "\'\'") + "\'";
			default -> value.toString();
		};
	}

	public String getField() {
		return this.field;
	}

	public Operator getOperator() {
		return this.operator;
	}

	public Object[] getValues() {
		return this.values;
	}
}
