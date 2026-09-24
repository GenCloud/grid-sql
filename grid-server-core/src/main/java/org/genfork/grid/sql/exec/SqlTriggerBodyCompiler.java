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
package org.genfork.grid.sql.exec;

import java.time.ZoneOffset;
import java.util.Objects;

import org.genfork.grid.catalog.TriggerDef;
import org.genfork.grid.catalog.TriggerEvent;
import org.genfork.grid.catalog.TriggerGranularity;
import org.genfork.grid.catalog.TriggerTiming;
import org.genfork.grid.sql.SqlStatementParser;
import org.genfork.grid.sql.ast.Stmt;

/**
 * Compiles trigger body and WHEN SQL through the shared ANTLR parser.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlTriggerBodyCompiler {
	private static final String WHEN_SELECT_PREFIX = "DELETE FROM __trg_when WHERE (";
	private static final String WHEN_SELECT_SUFFIX = ")";

	private SqlTriggerBodyCompiler() {
	}

	public static TriggerDef compile(
			String name,
			String table,
			TriggerTiming timing,
			TriggerEvent event,
			TriggerGranularity granularity,
			String whenSql,
			String bodySql
	) {
		Objects.requireNonNull(granularity, "granularity");
		final Stmt bodyStmt = SqlStatementParser.parseTriggerBody(bodySql, ZoneOffset.UTC);
		final boolean bodyHasRowRefs = SqlTriggerBindUtil.hasRowRefs(bodyStmt);
		final String normalizedWhen = whenSql == null || whenSql.isBlank() ? null : whenSql.trim();
		final boolean whenHasRowRefs;
		if (normalizedWhen == null) {
			whenHasRowRefs = false;
		} else {
			final Stmt whenStmt = SqlStatementParser.parseTriggerBody(
					WHEN_SELECT_PREFIX + normalizedWhen + WHEN_SELECT_SUFFIX,
					ZoneOffset.UTC);
			whenHasRowRefs = SqlTriggerBindUtil.hasRowRefs(whenStmt);
		}
		if (granularity == TriggerGranularity.STATEMENT && (bodyHasRowRefs || whenHasRowRefs)) {
			throw new IllegalArgumentException("STATEMENT trigger cannot reference OLD or NEW rows");
		}
		return new TriggerDef(
				name,
				table,
				timing,
				event,
				granularity,
				normalizedWhen,
				bodySql,
				bodyStmt,
				whenHasRowRefs);
	}
}
