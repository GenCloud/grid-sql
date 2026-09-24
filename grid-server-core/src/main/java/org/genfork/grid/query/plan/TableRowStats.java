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
package org.genfork.grid.query.plan;

/**
 * Crude per-table size hints for {@link QueryCardinality}.
 * <p>
 * Prefer {@link #analyzedRows} from SQL {@code ANALYZE} when present; otherwise
 * live map/sealed estimates.
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
public record TableRowStats(long mapSize, long sealedEntryHint, long analyzedRows, String source) {
	/** Stats source: no supplier / empty. */
	public static final String SOURCE_UNKNOWN = "unknown";
	/** Stats source: live map + sealed hints. */
	public static final String SOURCE_LIVE = "live";
	/** Stats source: SQL ANALYZE sidecar. */
	public static final String SOURCE_ANALYZE = "analyze";

	public static final TableRowStats UNKNOWN = new TableRowStats(0L, 0L, 0L, SOURCE_UNKNOWN);

	public TableRowStats {
		if (source == null || source.isBlank()) {
			source = SOURCE_UNKNOWN;
		}
	}

	/** Live working-set / sealed hints (no ANALYZE). */
	public static TableRowStats fromLive(long mapSize, long sealedEntryHint) {
		return new TableRowStats(mapSize, sealedEntryHint, 0L, SOURCE_LIVE);
	}

	/** ANALYZE overlay on top of live map/sealed sizes. */
	public static TableRowStats fromAnalyze(long analyzedRows, long mapSize, long sealedEntryHint) {
		return new TableRowStats(mapSize, sealedEntryHint, Math.max(0L, analyzedRows), SOURCE_ANALYZE);
	}

	public long estimatedRows() {
		if (analyzedRows > 0L) {
			return analyzedRows;
		}
		return QueryCardinality.estimateTableRows(mapSize, sealedEntryHint);
	}

	public boolean fromAnalyze() {
		return SOURCE_ANALYZE.equals(source) && analyzedRows > 0L;
	}
}
