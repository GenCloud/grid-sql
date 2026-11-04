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
package org.genfork.grid.catalog;

import java.util.List;

/**
 * Ordered case-insensitive column-name list equality (catalog / index resolve).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
public final class ColumnListEqualUtil {
	private ColumnListEqualUtil() {
	}

	/**
	 * {@code true} when both lists have the same size and each pair of names
	 * matches {@link String#equalsIgnoreCase(String)}.
	 */
	public static boolean columnsEqualIgnoreCaseOrdered(List<String> left, List<String> right) {
		if (left == null || right == null) {
			return left == right;
		}
		if (left.size() != right.size()) {
			return false;
		}
		for (int i = 0; i < left.size(); i++) {
			if (!left.get(i).equalsIgnoreCase(right.get(i))) {
				return false;
			}
		}
		return true;
	}
}
