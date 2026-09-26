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
package org.genfork.grid.query.plan;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.genfork.grid.mem.index.AbstractIndexOperation;
import org.genfork.grid.mem.index.btree.SingleTreeKey;
import org.genfork.grid.query.plan.SortOrderData.OrderDirection;

/**
 * Access-path pick: secondary scalar/BITMAP vs composite left-prefix.
 * <p>
 * EQ only on the composite leading column with no ORDER BY on the next composite
 * column prefers a single-column (or BITMAP) index when present — avoids a wider
 * composite prefix scan. ORDER BY on the next column always keeps composite ordered-leaf.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class QueryIndexChoiceUtil {
	private QueryIndexChoiceUtil() {
	}

	/**
	 * {@code true} when the planner should keep the scalar/BITMAP EQ and skip composite prefix rewrite.
	 */
	public static boolean preferScalarOverCompositePrefix(
			Map<String, AbstractIndexOperation<byte[], SingleTreeKey>> property2Index,
			List<String> compositeFields,
			String leadingColumn,
			SortOrderData sortOrder
	) {
		if (property2Index == null || compositeFields == null || compositeFields.size() < 2) {
			return false;
		}
		if (leadingColumn == null || !compositeFields.getFirst().equalsIgnoreCase(leadingColumn)) {
			return false;
		}
		if (sortOrder != null
				&& compositeFields.get(1).equalsIgnoreCase(sortOrder.sortField())
				&& sortOrder.direction() == OrderDirection.ASC) {
			return false;
		}
		return resolveSingleColumnIndex(property2Index, leadingColumn) != null;
	}

	private static AbstractIndexOperation<byte[], SingleTreeKey> resolveSingleColumnIndex(
			Map<String, AbstractIndexOperation<byte[], SingleTreeKey>> property2Index,
			String columnName
	) {
		final AbstractIndexOperation<byte[], SingleTreeKey> exact = property2Index.get(columnName);
		if (exact != null) {
			return exact;
		}
		for (Entry<String, AbstractIndexOperation<byte[], SingleTreeKey>> entry : property2Index.entrySet()) {
			if (entry.getKey().equalsIgnoreCase(columnName)) {
				return entry.getValue();
			}
		}
		return null;
	}
}