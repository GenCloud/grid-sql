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

import java.util.Collections;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Crude ANALYZE hints: table row count + per-column index fan-out.
 *
 * @author: GenCloud
 * @date: 2025/07
 * @since: 1.0
 */
public final class TableAnalyzeStats {
	/** Sidecar file suffix under catalog/stats/. */
	public static final String FILE_SUFFIX = ".stats";
	/** Line prefix for row cardinality. */
	public static final String KEY_ROW_COUNT = "rowCount=";
	/** Line prefix for column fan-out ({@code fanout.col=N}). */
	public static final String KEY_FANOUT_PREFIX = "fanout.";
	/** Frequency histogram line prefix ({@code hist.col.base64=N}). */
	public static final String KEY_HISTOGRAM_PREFIX = "hist.";

	private final long rowCount;
	private final Map<String, Long> columnFanOut;
	private final Map<String, List<FrequencyBucket>> columnHistograms;

	/**
	 * One wire-value frequency bucket.
	 *
	 * @author: GenCloud
	 * @date: 2025/07
	 * @since: 1.0
	 */
	public record FrequencyBucket(byte[] wireValue, long frequency) {
		public FrequencyBucket {
			wireValue = Arrays.copyOf(wireValue, wireValue.length);
			frequency = Math.max(1L, frequency);
		}

		@Override
		public byte[] wireValue() {
			return Arrays.copyOf(wireValue, wireValue.length);
		}
	}

	public TableAnalyzeStats(long rowCount, Map<String, Long> columnFanOut) {
		this(rowCount, columnFanOut, Map.of());
	}

	public TableAnalyzeStats(
			long rowCount,
			Map<String, Long> columnFanOut,
			Map<String, List<FrequencyBucket>> columnHistograms
	) {
		this.rowCount = Math.max(0L, rowCount);
		if (columnFanOut == null || columnFanOut.isEmpty()) {
			this.columnFanOut = Map.of();
		} else {
			final Map<String, Long> copy = new LinkedHashMap<>();
			for (Map.Entry<String, Long> e : columnFanOut.entrySet()) {
				if (e.getKey() == null || e.getValue() == null) {
					continue;
				}
				copy.put(e.getKey().toLowerCase(Locale.ROOT), Math.max(1L, e.getValue()));
			}
			this.columnFanOut = Collections.unmodifiableMap(copy);
		}
		if (columnHistograms == null || columnHistograms.isEmpty()) {
			this.columnHistograms = Map.of();
		} else {
			final Map<String, List<FrequencyBucket>> copy = new LinkedHashMap<>();
			for (Map.Entry<String, List<FrequencyBucket>> entry : columnHistograms.entrySet()) {
				copy.put(entry.getKey().toLowerCase(Locale.ROOT), List.copyOf(entry.getValue()));
			}
			this.columnHistograms = Collections.unmodifiableMap(copy);
		}
	}

	public long rowCount() {
		return rowCount;
	}

	public Map<String, Long> columnFanOut() {
		return columnFanOut;
	}

	/** Fan-out for column, or {@code 0} when unknown. */
	public long fanOut(String column) {
		if (column == null) {
			return 0L;
		}
		final Long v = columnFanOut.get(column.toLowerCase(Locale.ROOT));
		return v == null ? 0L : v;
	}

	public Map<String, List<FrequencyBucket>> columnHistograms() {
		return columnHistograms;
	}

	/** Exact frequency estimate from a frequency histogram, or {@code 0} when unavailable. */
	public long equalityEstimate(String column, byte[] wireValue) {
		if (column == null || wireValue == null) {
			return 0L;
		}
		final List<FrequencyBucket> buckets = columnHistograms.get(column.toLowerCase(Locale.ROOT));
		if (buckets == null) {
			return 0L;
		}
		for (FrequencyBucket bucket : buckets) {
			if (Arrays.equals(bucket.wireValue, wireValue)) {
				return bucket.frequency();
			}
		}
		return 0L;
	}

	/** Serialize to sidecar text lines. */
	public String toFileBody() {
		final StringBuilder sb = new StringBuilder();
		sb.append(KEY_ROW_COUNT).append(rowCount).append('\n');
		for (Map.Entry<String, Long> e : columnFanOut.entrySet()) {
			sb.append(KEY_FANOUT_PREFIX).append(e.getKey()).append('=').append(e.getValue()).append('\n');
		}
		for (Map.Entry<String, List<FrequencyBucket>> entry : columnHistograms.entrySet()) {
			for (FrequencyBucket bucket : entry.getValue()) {
				sb.append(KEY_HISTOGRAM_PREFIX).append(entry.getKey()).append('.')
						.append(Base64.getUrlEncoder().withoutPadding().encodeToString(bucket.wireValue))
						.append('=').append(bucket.frequency()).append('\n');
			}
		}
		return sb.toString();
	}

	/** Parse sidecar body written by {@link #toFileBody()}. */
	public static TableAnalyzeStats parse(String body) {
		Objects.requireNonNull(body, "body");
		long rows = 0L;
		final Map<String, Long> fan = new LinkedHashMap<>();
		final Map<String, List<FrequencyBucket>> histograms = new LinkedHashMap<>();
		for (String raw : body.split("\n")) {
			final String line = raw.trim();
			if (line.isEmpty()) {
				continue;
			}
			if (line.startsWith(KEY_ROW_COUNT)) {
				rows = Long.parseLong(line.substring(KEY_ROW_COUNT.length()).trim());
				continue;
			}
			if (line.startsWith(KEY_FANOUT_PREFIX)) {
				final int eq = line.indexOf('=');
				if (eq <= KEY_FANOUT_PREFIX.length()) {
					continue;
				}
				final String col = line.substring(KEY_FANOUT_PREFIX.length(), eq).trim();
				final long fo = Long.parseLong(line.substring(eq + 1).trim());
				fan.put(col.toLowerCase(Locale.ROOT), fo);
				continue;
			}
			if (line.startsWith(KEY_HISTOGRAM_PREFIX)) {
				final int eq = line.indexOf('=');
				final int valueSep = line.indexOf('.', KEY_HISTOGRAM_PREFIX.length());
				if (valueSep <= KEY_HISTOGRAM_PREFIX.length() || eq <= valueSep) {
					continue;
				}
				final String col = line.substring(KEY_HISTOGRAM_PREFIX.length(), valueSep)
						.toLowerCase(Locale.ROOT);
				final byte[] wireValue = Base64.getUrlDecoder().decode(line.substring(valueSep + 1, eq));
				final long frequency = Long.parseLong(line.substring(eq + 1).trim());
				histograms.computeIfAbsent(col, ignored -> new ArrayList<>())
						.add(new FrequencyBucket(wireValue, frequency));
			}
		}
		return new TableAnalyzeStats(rows, fan, histograms);
	}
}
