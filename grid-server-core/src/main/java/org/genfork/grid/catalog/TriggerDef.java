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

import java.util.Objects;

import org.genfork.grid.sql.ast.Stmt;

/**
 * Catalog trigger definition with an ANTLR-compiled body.
 * <p>
 * DDL persistence retains the original body and optional WHEN SQL while the catalog keeps
 * the parsed body for allocation-free dispatch at fire time.
 *
 * @author: GenCloud
 * @date: 2025/07
 * @since: 1.0
 */
public record TriggerDef(
		String name,
		String table,
		TriggerTiming timing,
		TriggerEvent event,
		TriggerGranularity granularity,
		String whenSqlOrNull,
		String bodySql,
		Stmt bodyStmt,
		boolean whenHasRowRefs
) {
	public TriggerDef {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(table, "table");
		Objects.requireNonNull(timing, "timing");
		Objects.requireNonNull(event, "event");
		Objects.requireNonNull(granularity, "granularity");
		Objects.requireNonNull(bodySql, "bodySql");
		Objects.requireNonNull(bodyStmt, "bodyStmt");
		if (name.isBlank() || table.isBlank() || bodySql.isBlank()) {
			throw new IllegalArgumentException("TRIGGER name, table, and body are required");
		}
		if (whenSqlOrNull != null && whenSqlOrNull.isBlank()) {
			whenSqlOrNull = null;
		}
	}
}
