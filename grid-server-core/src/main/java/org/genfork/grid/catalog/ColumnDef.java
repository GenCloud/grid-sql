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

/**
 * Immutable column definition in a {@link TableSchema}.
 *
 * @param defaultExprOrNull SQL text of {@code DEFAULT} (literal / {@code NOW()} / clock keyword), or null
 *
 * @author: GenCloud
 * @date: 2025/07
 * @since: 1.0
 */
public record ColumnDef(
		String name,
		SqlType type,
		boolean nullable,
		int ordinal,
		boolean primaryKey,
		boolean externalOrder,
		boolean identity,
		String identitySequence,
		String defaultExprOrNull
) {
	public ColumnDef {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("column name required");
		}
		if (type == null) {
			throw new IllegalArgumentException("column type required");
		}
		if (ordinal < 0) {
			throw new IllegalArgumentException("ordinal must be >= 0");
		}
		if (identity && (identitySequence == null || identitySequence.isBlank())) {
			throw new IllegalArgumentException("identitySequence required when identity");
		}
		if (!identity) {
			identitySequence = null;
		}
		if (defaultExprOrNull != null && defaultExprOrNull.isBlank()) {
			defaultExprOrNull = null;
		}
	}

	public ColumnDef(
			String name,
			SqlType type,
			boolean nullable,
			int ordinal,
			boolean primaryKey,
			boolean externalOrder
	) {
		this(name, type, nullable, ordinal, primaryKey, externalOrder, false, null, null);
	}

	public ColumnDef(
			String name,
			SqlType type,
			boolean nullable,
			int ordinal,
			boolean primaryKey,
			boolean externalOrder,
			boolean identity,
			String identitySequence
	) {
		this(name, type, nullable, ordinal, primaryKey, externalOrder, identity, identitySequence, null);
	}

	public int nameHash() {
		return name.hashCode();
	}

	public Class<?> javaType() {
		return type.javaType();
	}
}
