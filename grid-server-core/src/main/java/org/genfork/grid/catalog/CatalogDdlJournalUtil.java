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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Migrate-on-read helpers for {@code ddl.sql} journal lines (object-local index names).
 * <p>
 * Does not replace ANTLR parse — only strips illegal schema dots from CREATE INDEX names
 * so recover can parse. Static util — keep out of {@link TableCatalog} / {@link SqlEngine}.
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
public final class CatalogDdlJournalUtil {
	private static final Pattern CREATE_INDEX_NAME = Pattern.compile(
			"(?i)^(CREATE\\s+(?:(?:UNIQUE|BITMAP)\\s+)?INDEX\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?)([^\\s]+)\\s+(ON\\b.*)$");

	private CatalogDdlJournalUtil() {
	}

	/**
	 * Sanitize each DDL line: CREATE INDEX names with {@code .} become object-local leaf names.
	 */
	public static List<String> sanitizeLines(List<String> lines) {
		if (lines == null || lines.isEmpty()) {
			return lines == null ? List.of() : lines;
		}
		final ArrayList<String> out = new ArrayList<>(lines.size());
		for (String line : lines) {
			out.add(sanitizeLine(line));
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
		final String trimmed = line.trim();
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
}