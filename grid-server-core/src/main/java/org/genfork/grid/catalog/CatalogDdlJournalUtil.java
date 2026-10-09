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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Migrate-on-read helpers for {@code ddl.sql} journal lines.
 * <p>
 * Coalesces physical newlines that split one DDL statement (VIEW/MV bodies), then strips
 * illegal schema dots from CREATE INDEX names. Static util — keep out of {@link TableCatalog}.
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
public final class CatalogDdlJournalUtil {
	private static final Pattern CREATE_INDEX_NAME = Pattern.compile(
			"(?i)^(CREATE\\s+(?:(?:UNIQUE|BITMAP)\\s+)?INDEX\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?)([^\\s]+)\\s+(ON\\b.*)$");
	private static final char NEWLINE = '\n';
	private static final char CARRIAGE_RETURN = '\r';
	private static final char SPACE = ' ';
	private static final String CREATE_KW = "create";
	private static final String DROP_KW = "drop";
	private static final String ALTER_KW = "alter";
	private static final String GRANT_KW = "grant";
	private static final String REVOKE_KW = "revoke";
	private static final String REFRESH_KW = "refresh";

	private CatalogDdlJournalUtil() {
	}

	/**
	 * Replace CR/LF inside a DDL statement with spaces so {@code ddl.sql} stays one statement per line.
	 */
	public static String flattenNewlines(String sql) {
		if (sql == null || sql.isEmpty()) {
			return sql;
		}
		if (sql.indexOf(NEWLINE) < 0 && sql.indexOf(CARRIAGE_RETURN) < 0) {
			return sql;
		}
		final StringBuilder sb = new StringBuilder(sql.length());
		for (int i = 0; i < sql.length(); i++) {
			final char c = sql.charAt(i);
			if (c == NEWLINE || c == CARRIAGE_RETURN) {
				sb.append(SPACE);
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}

	/**
	 * Sanitize journal lines: coalesce multi-line statements, then CREATE INDEX name leaf sanitize.
	 */
	public static List<String> sanitizeLines(List<String> lines) {
		if (lines == null || lines.isEmpty()) {
			return lines == null ? List.of() : lines;
		}
		final List<String> coalesced = coalescePhysicalLines(lines);
		final ArrayList<String> out = new ArrayList<>(coalesced.size());
		for (String line : coalesced) {
			out.add(sanitizeLine(line));
		}
		return List.copyOf(out);
	}

	/**
	 * Join physical file lines that continue a prior DDL statement (VIEW/MV / TABLE bodies with newlines).
	 */
	public static List<String> coalescePhysicalLines(List<String> lines) {
		if (lines == null || lines.isEmpty()) {
			return lines == null ? List.of() : lines;
		}
		final ArrayList<String> out = new ArrayList<>(lines.size());
		StringBuilder current = null;
		for (String raw : lines) {
			if (raw == null) {
				continue;
			}
			final String trimmed = raw.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			if (isStatementStart(trimmed)) {
				if (current != null) {
					out.add(current.toString());
				}
				current = new StringBuilder(trimmed);
			} else if (current != null) {
				current.append(SPACE).append(trimmed);
			} else {
				out.add(trimmed);
			}
		}
		if (current != null) {
			out.add(current.toString());
		}
		return List.copyOf(out);
	}

	/**
	 * Sanitize one journal line (null/blank unchanged).
	 */
	public static String sanitizeLine(String line) {
		if (line == null || line.isBlank()) {
			return line;
		}
		final String trimmed = flattenNewlines(line).trim();
		final Matcher m = CREATE_INDEX_NAME.matcher(trimmed);
		if (!m.matches()) {
			return trimmed;
		}
		final String indexName = m.group(2);
		if (indexName.indexOf('.') < 0) {
			return trimmed;
		}
		final String leaf = CatalogPersistUtil.sanitizeIndexName(indexName);
		return m.group(1) + leaf + " " + m.group(3);
	}

	private static boolean isStatementStart(String trimmed) {
		final String lower = trimmed.toLowerCase(Locale.ROOT);
		return startsWithKeyword(lower, CREATE_KW)
				|| startsWithKeyword(lower, DROP_KW)
				|| startsWithKeyword(lower, ALTER_KW)
				|| startsWithKeyword(lower, GRANT_KW)
				|| startsWithKeyword(lower, REVOKE_KW)
				|| startsWithKeyword(lower, REFRESH_KW);
	}

	private static boolean startsWithKeyword(String lower, String keyword) {
		if (!lower.startsWith(keyword)) {
			return false;
		}
		if (lower.length() == keyword.length()) {
			return true;
		}
		final char next = lower.charAt(keyword.length());
		return next == SPACE || next == '\t';
	}
}
