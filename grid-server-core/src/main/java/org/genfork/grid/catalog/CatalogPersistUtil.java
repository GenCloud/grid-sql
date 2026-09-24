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

import java.util.Locale;

/**
 * Schema-qualified name helpers for durable catalog persist / DROP SCHEMA RESTRICT.
 * <p>
 * Static helpers only — do not grow {@link TableCatalog} with path/string dumps.
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
public final class CatalogPersistUtil {
	/** Default SQL schema when a name has no {@code schema.object} qualifier. */
	public static final String SCHEMA_PUBLIC = "public";

	/** On-disk table schema sidecar suffix. */
	public static final String META_SUFFIX = ".meta";

	private static final char QUALIFIER_SEP = '.';

	private CatalogPersistUtil() {
	}

	/**
	 * Lower-case schema part of a catalog key ({@code public} when unqualified).
	 */
	public static String schemaPartOf(String qualifiedName) {
		if (qualifiedName == null || qualifiedName.isBlank()) {
			return SCHEMA_PUBLIC;
		}
		final String key = qualifiedName.toLowerCase(Locale.ROOT);
		final int dot = key.indexOf(QUALIFIER_SEP);
		if (dot <= 0) {
			return SCHEMA_PUBLIC;
		}
		return key.substring(0, dot);
	}

	/**
	 * Whether {@code objectKey} (table / view / sequence / function) belongs to {@code schemaKey}.
	 */
	public static boolean belongsToSchema(String objectKey, String schemaKey) {
		if (objectKey == null || schemaKey == null) {
			return false;
		}
		final String schema = schemaKey.toLowerCase(Locale.ROOT);
		return schema.equals(schemaPartOf(objectKey));
	}

	/**
	 * Meta file name for a table key ({@code schema.table.meta} or {@code table.meta}).
	 */
	public static String metaFileName(String tableKey) {
		return tableKey.toLowerCase(Locale.ROOT) + META_SUFFIX;
	}
}
