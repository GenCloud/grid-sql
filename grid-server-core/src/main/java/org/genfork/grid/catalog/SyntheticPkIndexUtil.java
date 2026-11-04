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
import java.util.Objects;

import org.genfork.grid.mem.index.IndexType;

/**
 * Identify the synthetic STRICT PRIMARY KEY covering index ({@code table_pk}).
 * <p>
 * DROP of that index strips the sole RAM tree for PK columns while
 * {@link TableSchema#withoutIndex} would re-synthesize the IndexDef — fail-closed reject only.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
public final class SyntheticPkIndexUtil {
	/** Prefix shared by reject messages when DROP targets the PRIMARY KEY covering index. */
	public static final String MSG_CANNOT_DROP_PRIMARY_KEY_INDEX = "Cannot DROP PRIMARY KEY index: ";

	private SyntheticPkIndexUtil() {
	}

	/**
	 * {@code true} when {@code def} is the synthetic PK covering index for {@code schema}
	 * (name {@code localTable_pk} or STRICT with exact PK column list).
	 */
	public static boolean isSyntheticPrimaryKeyIndex(TableSchema schema, IndexDef def) {
		Objects.requireNonNull(schema, "schema");
		if (def == null) {
			return false;
		}
		if (isSyntheticPrimaryKeyIndexName(schema.catalogKey(), def.name())) {
			return true;
		}
		if (def.kind() != IndexType.STRICT) {
			return false;
		}
		final List<ColumnDef> pkColumns = schema.pkColumns();
		if (def.columns().size() != pkColumns.size()) {
			return false;
		}
		for (int i = 0; i < pkColumns.size(); i++) {
			if (!def.columns().get(i).equalsIgnoreCase(pkColumns.get(i).name())) {
				return false;
			}
		}
		return true;
	}

	/**
	 * {@code true} when {@code indexName} equals the synthetic {@code localTable_pk} for {@code catalogKey}.
	 */
	public static boolean isSyntheticPrimaryKeyIndexName(String catalogKey, String indexName) {
		if (indexName == null || indexName.isBlank() || catalogKey == null || catalogKey.isBlank()) {
			return false;
		}
		final String synthetic = CatalogPersistUtil.syntheticPkIndexName(catalogKey);
		return indexName.equalsIgnoreCase(synthetic);
	}

	/**
	 * Fail-closed message for DROP INDEX of the PRIMARY KEY covering index.
	 */
	public static String cannotDropMessage(String indexName) {
		final String name = indexName == null || indexName.isBlank() ? "" : indexName;
		return MSG_CANNOT_DROP_PRIMARY_KEY_INDEX + name;
	}
}
