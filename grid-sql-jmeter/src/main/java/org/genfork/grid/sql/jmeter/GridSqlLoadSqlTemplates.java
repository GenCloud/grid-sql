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
package org.genfork.grid.sql.jmeter;

import java.util.Locale;
import java.util.Objects;

/**
 * Load-SLO SQL templates: defaults match historical hardcoded strings; overrides via
 * {@code -J} / {@link System#getProperty} or sampler args (resolved once at session open).
 * <p>
 * Placeholders: {@code ${tableA}}, {@code ${tableB}}, {@code ${id}}, {@code ${val}}, {@code ${n}}.
 * Rendering must stay outside the JMeter sample clock.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
public final class GridSqlLoadSqlTemplates {

	public static final String PROP_TABLE_A = "TABLE_A";
	public static final String PROP_TABLE_B = "TABLE_B";
	public static final String PROP_SQL_EQ_LIMIT = "SQL_EQ_LIMIT";
	public static final String PROP_SQL_UPSERT = "SQL_UPSERT";
	public static final String PROP_SQL_SHORT_TX_SELECT = "SQL_SHORT_TX_SELECT";
	public static final String PROP_SQL_SHORT_TX_UPDATE = "SQL_SHORT_TX_UPDATE";
	public static final String PROP_SQL_COUNT_JOIN = "SQL_COUNT_JOIN";
	public static final String PROP_SQL_DDL_A = "SQL_DDL_A";
	public static final String PROP_SQL_DDL_B = "SQL_DDL_B";
	public static final String PROP_SQL_INDEX_B = "SQL_INDEX_B";
	public static final String PROP_SQL_SEED_A_PREFIX = "SQL_SEED_A_PREFIX";
	public static final String PROP_SQL_SEED_B_PREFIX = "SQL_SEED_B_PREFIX";

	public static final String PH_TABLE_A = "${tableA}";
	public static final String PH_TABLE_B = "${tableB}";
	public static final String PH_ID = "${id}";
	public static final String PH_VAL = "${val}";
	public static final String PH_N = "${n}";

	/** Historical default — keep byte-identical for living SLO stamps. */
	public static final String DEFAULT_TABLE_A = "load_slo_a";
	/** Historical default — keep byte-identical for living SLO stamps. */
	public static final String DEFAULT_TABLE_B = "load_slo_b";

	public static final String DEFAULT_SQL_EQ_LIMIT =
			"SELECT id, val, n FROM " + PH_TABLE_A + " WHERE id = " + PH_ID + " LIMIT 1";
	public static final String DEFAULT_SQL_UPSERT =
			"INSERT INTO " + PH_TABLE_A
					+ " (id, val, n) VALUES (" + PH_ID + ", '" + PH_VAL + "', 1) ON CONFLICT (id) DO UPDATE SET val = '"
					+ PH_VAL + "', n = 1";
	public static final String DEFAULT_SQL_SHORT_TX_SELECT =
			"SELECT id, val FROM " + PH_TABLE_A + " WHERE id = " + PH_ID + " LIMIT 1";
	public static final String DEFAULT_SQL_SHORT_TX_UPDATE =
			"UPDATE " + PH_TABLE_A + " SET n = n + 1 WHERE id = " + PH_ID;
	public static final String DEFAULT_SQL_COUNT_JOIN =
			"SELECT COUNT(*) FROM " + PH_TABLE_A + " JOIN " + PH_TABLE_B + " ON id = a_id WHERE a_id = " + PH_ID;
	public static final String DEFAULT_SQL_DDL_A =
			"CREATE TABLE " + PH_TABLE_A + " (id INT PRIMARY KEY, val VARCHAR, n INT)";
	public static final String DEFAULT_SQL_DDL_B =
			"CREATE TABLE " + PH_TABLE_B + " (bid INT PRIMARY KEY, a_id INT, label VARCHAR)";
	public static final String DEFAULT_SQL_INDEX_B =
			"CREATE INDEX idx_" + PH_TABLE_B + "_a_id ON " + PH_TABLE_B + " (a_id)";
	public static final String DEFAULT_SQL_SEED_A_PREFIX =
			"INSERT INTO " + PH_TABLE_A + " (id, val, n) VALUES ";
	public static final String DEFAULT_SQL_SEED_B_PREFIX =
			"INSERT INTO " + PH_TABLE_B + " (bid, a_id, label) VALUES ";

	private final String tableA;
	private final String tableB;
	private final String sqlEqLimit;
	private final String sqlUpsert;
	private final String sqlShortTxSelect;
	private final String sqlShortTxUpdate;
	private final String sqlCountJoin;
	private final String sqlDdlA;
	private final String sqlDdlB;
	private final String sqlIndexB;
	private final String sqlSeedAPrefix;
	private final String sqlSeedBPrefix;

	private GridSqlLoadSqlTemplates(
			String tableA,
			String tableB,
			String sqlEqLimit,
			String sqlUpsert,
			String sqlShortTxSelect,
			String sqlShortTxUpdate,
			String sqlCountJoin,
			String sqlDdlA,
			String sqlDdlB,
			String sqlIndexB,
			String sqlSeedAPrefix,
			String sqlSeedBPrefix
	) {
		this.tableA = tableA;
		this.tableB = tableB;
		this.sqlEqLimit = sqlEqLimit;
		this.sqlUpsert = sqlUpsert;
		this.sqlShortTxSelect = sqlShortTxSelect;
		this.sqlShortTxUpdate = sqlShortTxUpdate;
		this.sqlCountJoin = sqlCountJoin;
		this.sqlDdlA = sqlDdlA;
		this.sqlDdlB = sqlDdlB;
		this.sqlIndexB = sqlIndexB;
		this.sqlSeedAPrefix = sqlSeedAPrefix;
		this.sqlSeedBPrefix = sqlSeedBPrefix;
	}

	/**
	 * Resolve templates: non-blank sampler arg wins, else system property, else historical default.
	 */
	public static GridSqlLoadSqlTemplates resolve(PropSource source) {
		final PropSource src = source == null ? PropSource.SYSTEM : source;
		final String tableA = firstNonBlank(src.get(PROP_TABLE_A), DEFAULT_TABLE_A);
		final String tableB = firstNonBlank(src.get(PROP_TABLE_B), DEFAULT_TABLE_B);
		return new GridSqlLoadSqlTemplates(
				tableA,
				tableB,
				firstNonBlank(src.get(PROP_SQL_EQ_LIMIT), DEFAULT_SQL_EQ_LIMIT),
				firstNonBlank(src.get(PROP_SQL_UPSERT), DEFAULT_SQL_UPSERT),
				firstNonBlank(src.get(PROP_SQL_SHORT_TX_SELECT), DEFAULT_SQL_SHORT_TX_SELECT),
				firstNonBlank(src.get(PROP_SQL_SHORT_TX_UPDATE), DEFAULT_SQL_SHORT_TX_UPDATE),
				firstNonBlank(src.get(PROP_SQL_COUNT_JOIN), DEFAULT_SQL_COUNT_JOIN),
				firstNonBlank(src.get(PROP_SQL_DDL_A), DEFAULT_SQL_DDL_A),
				firstNonBlank(src.get(PROP_SQL_DDL_B), DEFAULT_SQL_DDL_B),
				firstNonBlank(src.get(PROP_SQL_INDEX_B), DEFAULT_SQL_INDEX_B),
				firstNonBlank(src.get(PROP_SQL_SEED_A_PREFIX), DEFAULT_SQL_SEED_A_PREFIX),
				firstNonBlank(src.get(PROP_SQL_SEED_B_PREFIX), DEFAULT_SQL_SEED_B_PREFIX)
		);
	}

	public static GridSqlLoadSqlTemplates defaults() {
		return resolve(PropSource.EMPTY);
	}

	@FunctionalInterface
	public interface PropSource {
		String get(String key);

		PropSource EMPTY = key -> null;

		PropSource SYSTEM = key -> {
			final String v = System.getProperty(key);
			return v == null || v.isBlank() ? null : v;
		};

		static PropSource samplerThenSystem(SamplerProps sampler) {
			Objects.requireNonNull(sampler, "sampler");
			return key -> {
				final String fromSampler = sampler.get(key);
				if (fromSampler != null && !fromSampler.isBlank()) {
					return fromSampler.trim();
				}
				return SYSTEM.get(key);
			};
		}
	}

	@FunctionalInterface
	public interface SamplerProps {
		String get(String key);
	}

	public String tableA() {
		return tableA;
	}

	public String tableB() {
		return tableB;
	}

	public String renderEqLimit(int id) {
		return render(sqlEqLimit, id, null, null);
	}

	public String renderUpsert(int id, String val) {
		return render(sqlUpsert, id, val, null);
	}

	public String renderShortTxSelect(int id) {
		return render(sqlShortTxSelect, id, null, null);
	}

	public String renderShortTxUpdate(int id) {
		return render(sqlShortTxUpdate, id, null, null);
	}

	public String renderCountJoin(int id) {
		return render(sqlCountJoin, id, null, null);
	}

	public String renderDropA() {
		return "DROP TABLE IF EXISTS " + tableA;
	}

	public String renderDropB() {
		return "DROP TABLE IF EXISTS " + tableB;
	}

	public String renderDdlA() {
		return render(sqlDdlA, 0, null, null);
	}

	public String renderDdlB() {
		return render(sqlDdlB, 0, null, null);
	}

	public String renderIndexB() {
		return render(sqlIndexB, 0, null, null);
	}

	public String renderSeedInsertA(int fromInclusive, int toInclusive) {
		final StringBuilder sb = new StringBuilder((toInclusive - fromInclusive + 1) * 48);
		sb.append(render(sqlSeedAPrefix, 0, null, null));
		for (int i = fromInclusive; i <= toInclusive; i++) {
			if (i > fromInclusive) {
				sb.append(", ");
			}
			sb.append('(').append(i).append(", 'seed-").append(i).append("', 0)");
		}
		return sb.toString();
	}

	public String renderSeedInsertB(int fromInclusive, int toInclusive) {
		final StringBuilder sb = new StringBuilder((toInclusive - fromInclusive + 1) * 48);
		sb.append(render(sqlSeedBPrefix, 0, null, null));
		for (int i = fromInclusive; i <= toInclusive; i++) {
			if (i > fromInclusive) {
				sb.append(", ");
			}
			sb.append('(').append(i).append(", ").append(i).append(", 'b-").append(i).append("')");
		}
		return sb.toString();
	}

	private String render(String template, int id, String val, String n) {
		String out = template;
		out = replaceAllLiteral(out, PH_TABLE_A, tableA);
		out = replaceAllLiteral(out, PH_TABLE_B, tableB);
		out = replaceAllLiteral(out, PH_ID, Integer.toString(id));
		if (val != null) {
			out = replaceAllLiteral(out, PH_VAL, val);
		}
		if (n != null) {
			out = replaceAllLiteral(out, PH_N, n);
		}
		return out;
	}

	private static String replaceAllLiteral(String haystack, String needle, String replacement) {
		if (haystack == null || needle == null || needle.isEmpty()) {
			return haystack;
		}
		final String safe = replacement == null ? "" : replacement;
		int from = 0;
		StringBuilder sb = null;
		while (true) {
			final int idx = haystack.indexOf(needle, from);
			if (idx < 0) {
				if (sb == null) {
					return haystack;
				}
				sb.append(haystack, from, haystack.length());
				return sb.toString();
			}
			if (sb == null) {
				sb = new StringBuilder(haystack.length() + 16);
			}
			sb.append(haystack, from, idx);
			sb.append(safe);
			from = idx + needle.length();
		}
	}

	private static String firstNonBlank(String candidate, String fallback) {
		if (candidate == null || candidate.isBlank()) {
			return fallback;
		}
		return candidate.trim();
	}

	/**
	 * Sanity: default rendered upsert matches the historical hardcoded shape.
	 */
	public static boolean defaultsMatchHistoricalShape() {
		final GridSqlLoadSqlTemplates t = defaults();
		final String upsert = t.renderUpsert(7, "u-7-1");
		final String expected = "INSERT INTO load_slo_a (id, val, n) VALUES (7, 'u-7-1', 1) "
				+ "ON CONFLICT (id) DO UPDATE SET val = 'u-7-1', n = 1";
		return expected.equals(upsert)
				&& DEFAULT_TABLE_A.equals(t.tableA())
				&& DEFAULT_TABLE_B.equals(t.tableB())
				&& t.renderEqLimit(3).toLowerCase(Locale.ROOT).contains("limit 1");
	}
}
