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

import org.genfork.grid.mem.index.IndexType;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Secondary or primary index definition on a table.
 * <p>
 * {@link IndexType#BITMAP} is single-column only — multi-column BITMAP is rejected here
 * (and at DDL) rather than silently dropped when wiring trees.
 *
 * @author: GenCloud
 * @date: 2025/07
 * @since: 1.0
 */
public record IndexDef(
		String name,
		List<String> columns,
		IndexType kind
) {
	/** Clear reject when {@code CREATE BITMAP INDEX} lists more than one column. */
	public static final String BITMAP_SINGLE_COLUMN_ONLY =
			"BITMAP index supports exactly one column";

	public IndexDef {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("index name required");
		}
		Objects.requireNonNull(columns, "columns");
		if (columns.isEmpty()) {
			throw new IllegalArgumentException("index columns empty");
		}
		if (kind == null) {
			kind = IndexType.STRICT;
		}
		if (kind == IndexType.BITMAP && columns.size() > 1) {
			throw new IllegalArgumentException(BITMAP_SINGLE_COLUMN_ONLY);
		}
		columns = List.copyOf(columns);
	}

	public static IndexDef of(String name, IndexType kind, String... columns) {
		return new IndexDef(name, Arrays.asList(columns), kind);
	}
}
