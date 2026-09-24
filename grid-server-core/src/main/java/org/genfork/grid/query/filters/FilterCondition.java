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
package org.genfork.grid.query.filters;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.mem.index.AbstractIndexOperation;
import org.genfork.grid.mem.index.btree.CompositeTreeKey;
import org.genfork.grid.mem.index.btree.IndexOperationResult;
import org.genfork.grid.mem.index.btree.SingleTreeKey;
import org.genfork.grid.query.plan.ExplainQuery;
import org.genfork.grid.serial.FieldMetaData;


/**
 * Index / residual filter for SQL query plans (matches on stored row bytes, not domain POJO).
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
@FunctionalInterface
public interface FilterCondition {
	IndexOperationResult execute(Map<String, AbstractIndexOperation<byte[], SingleTreeKey>> property2Index,
	                             Map<List<String>, AbstractIndexOperation<byte[][], CompositeTreeKey>> compositeIndexes,
	                             FieldMetaData primaryKeyField,
	                             ExplainQuery.QueryPlan queryPlan);

	default boolean validate(TableSchema schema) {
		return true;
	}

	default String toPlanPartString() {
		return "#not-declared";
	}

	/**
	 * Residual match against committed / dirty row {@code valueBytes} using {@code schema}.
	 * Default accepts all rows (indexed-only conditions).
	 */
	default boolean matches(byte[] valueBytes, TableSchema schema) {
		return true;
	}

	/**
	 * Match projected / joined column values (JOIN WHERE) without allocating a row {@link Map}.
	 */
	default boolean matchesColumns(Function<String, Object> columnValue) {
		return true;
	}

	default boolean match(Class<?> expected, Class<?> passed) {
		if (expected == Byte.class || expected == byte.class) {
			return isIntegral(passed);
		} else if (expected == Short.class || expected == short.class) {
			return isIntegral(passed);
		} else if (expected == Integer.class || expected == int.class) {
			return isIntegral(passed);
		} else if (expected == Long.class || expected == long.class) {
			return isIntegral(passed);
		} else if (expected == Float.class || expected == float.class) {
			return isNumeric(passed);
		} else if (expected == Double.class || expected == double.class) {
			return isNumeric(passed);
		} else if (expected == Boolean.class || expected == boolean.class) {
			return passed == Boolean.class || passed == boolean.class;
		}

		return expected == passed;
	}

	private static boolean isIntegral(Class<?> type) {
		return type == Byte.class || type == byte.class
				|| type == Short.class || type == short.class
				|| type == Integer.class || type == int.class
				|| type == Long.class || type == long.class;
	}

	private static boolean isNumeric(Class<?> type) {
		return isIntegral(type)
				|| type == Float.class || type == float.class
				|| type == Double.class || type == double.class;
	}
}
