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
package org.genfork.grid.sql.util;

import org.genfork.grid.catalog.CatalogDdlJournalUtil;
import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.sql.SqlStatementParser;
import org.genfork.grid.sql.SqlStatementTag;
import org.genfork.grid.sql.SqlStatementTagger;
import org.genfork.grid.sql.ast.Stmt;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Apply writer compacted {@code ddl.sql} snapshot as missing CREATE* diff only.
 * <p>
 * Never re-applies DROP/ALTER from a snapshot (destructive under holes). Used when
 * {@code _catalog} OpLog prefix was sealed away and peer object-set lags the writer.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
public final class CatalogJournalReconcileUtil {
	private CatalogJournalReconcileUtil() {
	}

	/**
	 * Execute CREATE* lines whose catalog effect is not yet present.
	 *
	 * @param catalog  peer live catalog (probe missing effects)
	 * @param applyOne invokes {@code SqlEngine.applyReplicatedDdl} (or equivalent) with a fresh epoch
	 * @return number of CREATE* statements executed
	 */
	public static int applyMissingCreates(
			TableCatalog catalog,
			List<String> compactLines,
			long baseEpochExclusive,
			Consumer<DdlApply> applyOne
	) {
		Objects.requireNonNull(catalog, "catalog");
		Objects.requireNonNull(applyOne, "applyOne");
		if (compactLines == null || compactLines.isEmpty()) {
			return 0;
		}
		final List<String> lines = CatalogDdlJournalUtil.sanitizeLines(compactLines);
		int applied = 0;
		long epoch = Math.max(0L, baseEpochExclusive);
		for (String line : lines) {
			if (line == null || line.isBlank()) {
				continue;
			}
			final String trimmed = line.trim();
			if (!CatalogDdlEffectPresentUtil.isCreatelikeHealCandidate(trimmed)) {
				continue;
			}
			if (CatalogDdlEffectPresentUtil.effectPresent(catalog, trimmed)) {
				continue;
			}
			epoch++;
			applyOne.accept(new DdlApply(trimmed, epoch));
			applied++;
		}
		return applied;
	}

	/**
	 * Filter helper: {@code true} when line is CREATE* (ANTLR tag).
	 */
	public static boolean isCreateLine(String ddlSql) {
		return CatalogDdlEffectPresentUtil.isCreatelikeHealCandidate(ddlSql);
	}

	/**
	 * One CREATE* apply request (epoch already allocated above peer floor).
	 */
	public record DdlApply(String ddlSql, long schemaEpoch) {
		public DdlApply {
			Objects.requireNonNull(ddlSql, "ddlSql");
		}
	}

	/**
	 * Parse tag for diagnostics (ANTLR only).
	 */
	public static SqlStatementTag tagOrNull(String ddlSql) {
		try {
			final Stmt stmt = SqlStatementParser.parse(ddlSql.trim());
			return SqlStatementTagger.tagOf(stmt);
		} catch (RuntimeException ex) {
			return null;
		}
	}
}
