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
 * Catalog metadata for {@code CREATE SEQUENCE} / IDENTITY backing sequences.
 *
 * @author: GenCloud
 * @date: 2025/07
 * @since: 1.0
 */
public record SequenceDef(
		String name,
		long startValue,
		long increment,
		boolean reclaim,
		String ownedByTableOrNull,
		String ownedByColumnOrNull
) {
	public static final long DEFAULT_START = 1L;
	public static final long DEFAULT_INCREMENT = 1L;

	public SequenceDef {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("sequence name required");
		}
		if (increment == 0L) {
			throw new IllegalArgumentException("sequence increment must be non-zero");
		}
	}

	public static SequenceDef of(String name, long start, long increment, boolean reclaim) {
		return new SequenceDef(name, start, increment, reclaim, null, null);
	}

	public SequenceDef withOwner(String table, String column) {
		return new SequenceDef(name, startValue, increment, reclaim, table, column);
	}

	/** Default IDENTITY / SERIAL backing sequence name: {@code table_column_seq}. */
	public static String identityName(String table, String column) {
		return table + "_" + column + "_seq";
	}
}
