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
package org.genfork.grid.sql.exec;

import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.query.filters.FilterCondition;
import org.genfork.grid.query.filters.impl.AlwaysTrueCondition;
import org.genfork.grid.query.filters.impl.AndCondition;
import org.genfork.grid.query.filters.impl.LogicalOperatorCondition;
import org.genfork.grid.query.filters.impl.NotCondition;
import org.genfork.grid.query.filters.impl.OrCondition;

/**
 * Push JOIN-side WHERE predicates onto the left join column when {@code ON left = right}.
 * <p>
 * Load SLO {@code COUNT(*) JOIN ON id = a_id WHERE a_id = ?} otherwise fails left-schema
 * validation and falls through to a full right snapshot + left PK probe.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlJoinFilterPush {

	private SqlJoinFilterPush() {
	}

	/**
	 * Remap logical predicates on {@code rightJoinCol} to {@code leftJoinCol}, then validate
	 * against {@code leftSchema}. Returns {@link AlwaysTrueCondition} when the filter cannot be
	 * expressed on the left side alone.
	 */
	public static FilterCondition remapJoinColToLeft(
			FilterCondition filter,
			String leftJoinCol,
			String rightJoinCol,
			TableSchema leftSchema
	) {
		if (filter == null || filter instanceof AlwaysTrueCondition) {
			return AlwaysTrueCondition.getInstance();
		}
		if (leftJoinCol == null || rightJoinCol == null || leftJoinCol.isEmpty() || rightJoinCol.isEmpty()) {
			return AlwaysTrueCondition.getInstance();
		}
		final FilterCondition candidate;
		if (leftJoinCol.equalsIgnoreCase(rightJoinCol)) {
			candidate = filter;
		} else {
			candidate = remapField(filter, leftJoinCol, rightJoinCol);
		}
		try {
			candidate.validate(leftSchema);
			return candidate;
		} catch (RuntimeException ignored) {
			return AlwaysTrueCondition.getInstance();
		}
	}

	private static FilterCondition remapField(FilterCondition filter, String leftJoinCol, String rightJoinCol) {
		if (filter instanceof LogicalOperatorCondition loc) {
			if (rightJoinCol.equalsIgnoreCase(loc.getField())) {
				final Object[] values = loc.getValues();
				final Object[] copied = values == null ? null : values.clone();
				return new LogicalOperatorCondition(leftJoinCol, loc.getOperator(), copied);
			}
			return filter;
		}
		if (filter instanceof AndCondition and) {
			return new AndCondition(
					remapField(and.getLeft(), leftJoinCol, rightJoinCol),
					remapField(and.getRight(), leftJoinCol, rightJoinCol));
		}
		if (filter instanceof OrCondition or) {
			return new OrCondition(
					remapField(or.getLeft(), leftJoinCol, rightJoinCol),
					remapField(or.getRight(), leftJoinCol, rightJoinCol));
		}
		if (filter instanceof NotCondition not) {
			return new NotCondition(remapField(not.getChild(), leftJoinCol, rightJoinCol));
		}
		return filter;
	}
}