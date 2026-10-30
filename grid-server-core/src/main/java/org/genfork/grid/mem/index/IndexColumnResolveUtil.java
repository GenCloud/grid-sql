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
package org.genfork.grid.mem.index;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.genfork.grid.mem.index.btree.CompositeTreeKey;
import org.genfork.grid.mem.index.btree.SingleTreeKey;

/**
 * Resolve single- and multi-column index operations by catalog column names.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
public final class IndexColumnResolveUtil {
	private IndexColumnResolveUtil() {
	}

	/**
	 * Resolve single-column index (exact name, else case-insensitive).
	 */
	public static AbstractIndexOperation<byte[], SingleTreeKey> resolveSingleColumnIndex(
			Map<String, AbstractIndexOperation<byte[], SingleTreeKey>> property2Index,
			String columnName
	) {
		if (columnName == null || columnName.isBlank() || property2Index == null) {
			return null;
		}
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

	/**
	 * Resolve composite index whose columns match {@code columns} order-insensitively by name.
	 */
	public static AbstractIndexOperation<byte[][], CompositeTreeKey> resolveCompositeIndex(
			Map<List<String>, AbstractIndexOperation<byte[][], CompositeTreeKey>> compositeIndexes,
			List<String> columns
	) {
		if (columns == null || columns.isEmpty() || compositeIndexes == null) {
			return null;
		}
		final AbstractIndexOperation<byte[][], CompositeTreeKey> exact = compositeIndexes.get(columns);
		if (exact != null) {
			return exact;
		}
		for (Entry<List<String>, AbstractIndexOperation<byte[][], CompositeTreeKey>> entry
				: compositeIndexes.entrySet()) {
			if (CompositeIndexProbeOps.columnsEqualIgnoreCaseOrdered(entry.getKey(), columns)) {
				return entry.getValue();
			}
		}
		return null;
	}
}
