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

import org.genfork.grid.query.plan.SortOrderData.OrderDirection;

/**
 * Detect when {@code ORDER BY} is an ascending PRIMARY KEY prefix (leaf-ordered scan).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.1
 */
public final class PkOrderMatchUtil {
	private PkOrderMatchUtil() {
	}

	/**
	 * {@code true} when {@code sortOrders} is a non-empty ascending prefix of {@code primaryKeyColumns}.
	 */
	public static boolean matchesAscendingPrimaryKeyPrefix(
			SortOrderData[] sortOrders,
			List<String> primaryKeyColumns
	) {
		if (sortOrders == null || sortOrders.length == 0
				|| primaryKeyColumns == null || primaryKeyColumns.isEmpty()) {
			return false;
		}
		if (sortOrders.length > primaryKeyColumns.size()) {
			return false;
		}
		for (int i = 0; i < sortOrders.length; i++) {
			final SortOrderData order = sortOrders[i];
			if (order == null || order.sortField() == null) {
				return false;
			}
			if (order.direction() != OrderDirection.ASC) {
				return false;
			}
			if (!primaryKeyColumns.get(i).equalsIgnoreCase(order.sortField())) {
				return false;
			}
		}
		return true;
	}
}
