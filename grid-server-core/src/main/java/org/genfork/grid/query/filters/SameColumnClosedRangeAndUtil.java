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

import java.util.Map;

import org.genfork.grid.mem.index.AbstractIndexOperation;
import org.genfork.grid.mem.index.btree.IndexOperationResult;
import org.genfork.grid.mem.index.btree.SingleTreeKey;
import org.genfork.grid.query.filters.impl.LogicalOperatorCondition;
import org.genfork.grid.query.filters.impl.LogicalOperatorCondition.Operator;
import org.genfork.grid.serial.SqlWireUtil;

/**
 * Coalesce same-column indexed {@code GE ∧ LE} into one inclusive {@code searchRange}.
 * <p>
 * Exclusive {@code GT}/{@code LT} pairs stay on pointer intersect (searchRange is inclusive).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
public final class SameColumnClosedRangeAndUtil {
	private SameColumnClosedRangeAndUtil() {
	}

	/**
	 * @return range result when both legs are same-column indexed GE+LE; otherwise {@code null}
	 */
	public static IndexOperationResult tryClosedRange(
			FilterCondition left,
			FilterCondition right,
			Map<String, AbstractIndexOperation<byte[], SingleTreeKey>> property2Index
	) {
		if (!(left instanceof LogicalOperatorCondition leftLoc)
				|| !(right instanceof LogicalOperatorCondition rightLoc)) {
			return null;
		}
		if (property2Index == null) {
			return null;
		}
		if (!leftLoc.getField().equals(rightLoc.getField())) {
			return null;
		}
		final AbstractIndexOperation<byte[], SingleTreeKey> index = property2Index.get(leftLoc.getField());
		if (index == null) {
			return null;
		}
		final LogicalOperatorCondition lower;
		final LogicalOperatorCondition upper;
		if (leftLoc.getOperator() == Operator.GE && rightLoc.getOperator() == Operator.LE) {
			lower = leftLoc;
			upper = rightLoc;
		} else if (leftLoc.getOperator() == Operator.LE && rightLoc.getOperator() == Operator.GE) {
			lower = rightLoc;
			upper = leftLoc;
		} else {
			return null;
		}
		final Object[] lowerValues = lower.getValues();
		final Object[] upperValues = upper.getValues();
		if (lowerValues == null || lowerValues.length < 1 || upperValues == null || upperValues.length < 1) {
			return null;
		}
		final SingleTreeKey lo = index.createKey(lower.getField(), SqlWireUtil.toGenericArray(lowerValues[0]));
		final SingleTreeKey hi = index.createKey(upper.getField(), SqlWireUtil.toGenericArray(upperValues[0]));
		return index.searchRange(lo, hi);
	}
}
