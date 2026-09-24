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
import java.util.Locale;
import java.util.Objects;

/**
 * Foreign-key constraint owned by the child table.
 * <p>
 * Keys are compared as wire {@code byte[]} on DML / commit (not Object equality).
 *
 * @author: GenCloud
 * @date: 2025/07
 * @since: 1.0
 */
public record FkDef(
		String name,
		String childTable,
		List<String> childColumns,
		String parentTable,
		List<String> parentColumns,
		FkAction onDelete,
		FkAction onUpdate
) {
	public static final String DEFAULT_NAME_PREFIX = "fk_";

	public FkDef {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("FK name required");
		}
		if (childTable == null || childTable.isBlank()) {
			throw new IllegalArgumentException("childTable required");
		}
		if (parentTable == null || parentTable.isBlank()) {
			throw new IllegalArgumentException("parentTable required");
		}
		Objects.requireNonNull(childColumns, "childColumns");
		Objects.requireNonNull(parentColumns, "parentColumns");
		if (childColumns.isEmpty() || parentColumns.isEmpty()) {
			throw new IllegalArgumentException("FK columns empty");
		}
		if (childColumns.size() != parentColumns.size()) {
			throw new IllegalArgumentException("FK child/parent column arity mismatch");
		}
		if (onDelete == null) {
			onDelete = FkAction.RESTRICT;
		}
		if (onUpdate == null) {
			onUpdate = FkAction.RESTRICT;
		}
		childColumns = List.copyOf(childColumns);
		parentColumns = List.copyOf(parentColumns);
	}

	public static String defaultName(String childTable, List<String> childColumns) {
		final StringBuilder sb = new StringBuilder(DEFAULT_NAME_PREFIX);
		sb.append(childTable.toLowerCase(Locale.ROOT));
		for (String col : childColumns) {
			sb.append('_').append(col.toLowerCase(Locale.ROOT));
		}
		return sb.toString();
	}
}
