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

import org.genfork.grid.mem.index.AbstractIndexOperation;
import org.genfork.grid.mem.index.btree.CompositeTreeKey;
import org.genfork.grid.mem.index.btree.IndexOperationResult;
import org.genfork.grid.mem.index.btree.SingleTreeKey;
import org.genfork.grid.serial.FieldMetaData;

/**
 * PRIMARY KEY full-scan via scalar or composite BPTree ({@code searchAll}).
 * <p>
 * Composite PRIMARY KEY tables have no single-column PK tree; callers must not
 * treat a missing scalar entry as an empty table (e.g. {@code SELECT *} / no WHERE).
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class PkIndexScanUtil {
	private PkIndexScanUtil() {
	}

	/**
	 * All PK postings: single-column PK tree if present, else composite PK whose
	 * leading column matches {@code primaryKeyField} (prefer multi-column).
	 */
	public static IndexOperationResult searchAllPrimaryKeys(
			Map<String, AbstractIndexOperation<byte[], SingleTreeKey>> property2Index,
			Map<List<String>, AbstractIndexOperation<byte[][], CompositeTreeKey>> compositeIndexes,
			FieldMetaData primaryKeyField
	) {
		if (primaryKeyField == null) {
			return IndexOperationResult.EMPTY;
		}
		if (property2Index != null) {
			final AbstractIndexOperation<byte[], SingleTreeKey> scalar =
					property2Index.get(primaryKeyField.getName());
			if (scalar != null) {
				return scalar.searchAll();
			}
		}
		if (compositeIndexes == null || compositeIndexes.isEmpty()) {
			return IndexOperationResult.EMPTY;
		}
		final String pkName = primaryKeyField.getName();
		AbstractIndexOperation<byte[][], CompositeTreeKey> pkComposite = null;
		for (Map.Entry<List<String>, AbstractIndexOperation<byte[][], CompositeTreeKey>> entry
				: compositeIndexes.entrySet()) {
			final List<String> cols = entry.getKey();
			if (cols == null || cols.isEmpty() || !cols.getFirst().equalsIgnoreCase(pkName)) {
				continue;
			}
			pkComposite = entry.getValue();
			if (cols.size() > 1) {
				break;
			}
		}
		if (pkComposite == null) {
			return IndexOperationResult.EMPTY;
		}
		return pkComposite.searchAll();
	}
}