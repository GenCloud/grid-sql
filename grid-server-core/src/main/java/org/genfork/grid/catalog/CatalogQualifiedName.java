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
import java.util.Objects;

/**
 * Explicit SQL schema namespace identity: schema + local object name (never baked as one string field).
 * <p>
 * Catalog maps may still use {@link #catalogKey()} during migration; object-local derived names
 * always use {@link #objectName()}.
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
public record CatalogQualifiedName(String schemaName, String objectName) {
	private static final char QUALIFIER_SEP = '.';

	public CatalogQualifiedName {
		Objects.requireNonNull(schemaName, "schemaName");
		Objects.requireNonNull(objectName, "objectName");
		schemaName = schemaName.toLowerCase(Locale.ROOT);
		objectName = objectName.toLowerCase(Locale.ROOT);
		if (schemaName.isBlank()) {
			throw new IllegalArgumentException("schemaName required");
		}
		if (objectName.isBlank() || objectName.indexOf(QUALIFIER_SEP) >= 0) {
			throw new IllegalArgumentException("objectName must be local (no '.'): " + objectName);
		}
	}

	public static CatalogQualifiedName of(String schemaName, String objectName) {
		return new CatalogQualifiedName(schemaName, objectName);
	}

	/**
	 * Parse a catalog key or SQL table ref with optional session schema for unqualified names.
	 */
	public static CatalogQualifiedName parse(String ref, String currentSchemaOrNull) {
		if (ref == null || ref.isBlank()) {
			throw new IllegalArgumentException("table required");
		}
		final String t = ref.trim().toLowerCase(Locale.ROOT);
		final int dot = t.indexOf(QUALIFIER_SEP);
		if (dot > 0) {
			final String schema = t.substring(0, dot);
			final String name = t.substring(dot + 1);
			if (name.isBlank() || name.indexOf(QUALIFIER_SEP) >= 0) {
				throw new IllegalArgumentException("table required");
			}
			return new CatalogQualifiedName(schema, name);
		}
		final String sch = currentSchemaOrNull == null || currentSchemaOrNull.isBlank()
				? CatalogPersistUtil.SCHEMA_PUBLIC
				: currentSchemaOrNull.toLowerCase(Locale.ROOT);
		return new CatalogQualifiedName(sch, t);
	}

	/**
	 * Map / persist key: {@code public} objects stay bare {@code t} for volume compat;
	 * other schemas use {@code schema.object}.
	 */
	public String catalogKey() {
		if (CatalogPersistUtil.SCHEMA_PUBLIC.equals(schemaName)) {
			return objectName;
		}
		return schemaName + QUALIFIER_SEP + objectName;
	}

	/** SQL edge display: always {@code schema.object}. */
	public String sqlQualified() {
		return schemaName + QUALIFIER_SEP + objectName;
	}
}