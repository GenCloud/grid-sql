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
package org.genfork.grid.serial;

import java.util.Objects;

/**
 * Catalog column metadata for encode, index, and {@link LogicalFieldCursor}.
 * <p>
 * SQL-first only — constructed via {@link #ofCatalog(String, Class)}.
 *
 * @author: GenCloud
 * @date: 2025/06
 * @since: 1.0
 */
public final class FieldMetaData {
	private final String name;
	private final int hashCode;
	private final Class<?> type;

	/**
	 * Catalog-backed column metadata (no reflective {@link java.lang.reflect.Field}).
	 */
	public static FieldMetaData ofCatalog(String name, Class<?> javaType) {
		return new FieldMetaData(name, javaType);
	}

	private FieldMetaData(String name, Class<?> javaType) {
		this.name = Objects.requireNonNull(name, "name");
		this.hashCode = name.hashCode();
		this.type = Objects.requireNonNull(javaType, "javaType");
	}

	public String getName() {
		return name;
	}

	public int getHashCode() {
		return hashCode;
	}

	public Class<?> getType() {
		return type;
	}

	@Override
	public boolean equals(Object object) {
		if (this == object) {
			return true;
		}
		if (!(object instanceof FieldMetaData that)) {
			return false;
		}
		return hashCode == that.hashCode
				&& Objects.equals(name, that.name)
				&& Objects.equals(type, that.type);
	}

	@Override
	public int hashCode() {
		return hashCode;
	}
}
