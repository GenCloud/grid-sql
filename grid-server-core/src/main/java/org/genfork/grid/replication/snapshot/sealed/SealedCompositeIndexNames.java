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
package org.genfork.grid.replication.snapshot.sealed;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Stable sealed {@code .sbpt} index names for multi-column BPTree indexes.
 * <p>
 * Logical name is {@code col1+col2+...} (lowercase); {@link SealedBPTreeService#indexFile}
 * keeps {@code +} via {@code safeName}, producing
 * {@code {domainHex}_{shard}_idx_{col1+col2}.sbpt}.
 *
 * @author: GenCloud
 * @date: 2026/05
 * @since: 1.0
 */
public final class SealedCompositeIndexNames {
	/** Column join separator preserved in sealed filenames. */
	public static final String COLUMN_SEPARATOR = "+";

	private SealedCompositeIndexNames() {
	}

	/**
	 * Build sealed index name from composite column list (order preserved).
	 */
	public static String of(List<String> columns) {
		Objects.requireNonNull(columns, "columns");
		if (columns.size() < 2) {
			throw new IllegalArgumentException("composite sealed name requires at least two columns");
		}
		final StringBuilder sb = new StringBuilder();
		for (int i = 0; i < columns.size(); i++) {
			final String column = columns.get(i);
			Objects.requireNonNull(column, "column");
			if (column.isBlank()) {
				throw new IllegalArgumentException("composite sealed column name blank");
			}
			if (i > 0) {
				sb.append(COLUMN_SEPARATOR);
			}
			sb.append(column.toLowerCase(Locale.ROOT));
		}
		return sb.toString();
	}
}
