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
package org.genfork.grid.sql.ast;

import java.util.List;
import java.util.Map;

import org.genfork.grid.query.plan.UpdatePlan;

/**
 * INSERT / UPDATE / DELETE / MERGE / ANALYZE AST types.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class DmlAst {
	private DmlAst() {
	}

	/**
	 * {@code INSERT … [ON CONFLICT …]}.
	 *
	 * @param onConflictOrNull null = plain INSERT (reject duplicate PK)
	 *
	 * @author: GenCloud
	 * @date: 2025/11
	 * @since: 1.0
	 */
	public record InsertSql(
			String table,
			List<String> columns,
			List<List<Object>> rows,
			OnConflict onConflictOrNull,
			List<String> returning
	) implements Stmt {
		public InsertSql(String table, List<String> columns, List<List<Object>> rows) {
			this(table, columns, rows, null, List.of());
		}
	}

	/** ON CONFLICT / UPSERT action kind. */
	public enum ConflictAction {
		DO_NOTHING,
		DO_UPDATE,
		/** {@code UPSERT INTO} — overwrite existing PK from VALUES (OpLog UPSERT). */
		DO_UPSERT_VALUES
	}

	/**
	 * {@code ON CONFLICT [(cols)] DO NOTHING | DO UPDATE SET …}.
	 *
	 * @param targetColumns empty = PRIMARY KEY
	 *
	 * @author: GenCloud
	 * @date: 2025/11
	 * @since: 1.0
	 */
	public record OnConflict(
			List<String> targetColumns,
			ConflictAction action,
			Map<String, Object> updateSets
	) {
	}

	/**
	 * {@code ANALYZE table} — crude cardinality / fan-out for the planner.
	 *
	 * @author: GenCloud
	 * @date: 2025/11
	 * @since: 1.0
	 */
	public record AnalyzeSql(String table) implements Stmt {
	}

	/**
	 * {@code MERGE INTO … USING … ON … [WHEN MATCHED …] [WHEN NOT MATCHED …]}.
	 *
	 * @param sourceTableOrNull when non-null, USING table; else single VALUES row
	 * @param sourceRowOrNull   VALUES tuple when sourceTableOrNull is null
	 *
	 * @author: GenCloud
	 * @date: 2025/11
	 * @since: 1.0
	 */
	public record MergeSql(
			String targetTable,
			String sourceTableOrNull,
			List<Object> sourceRowOrNull,
			String targetOnCol,
			String sourceOnCol,
			Map<String, Object> matchedSetsOrNull,
			List<String> insertColumnsOrNull,
			List<Object> insertValuesOrNull
	) implements Stmt {
	}

	/**
	 * {@code DELETE FROM …}.
	 *
	 * @author: GenCloud
	 * @date: 2025/11
	 * @since: 1.0
	 */
	public record DeleteSql(String table, String whereSql, String pkColumnOrNull, Object pkValueOrNull) implements Stmt {
	}

	/**
	 * {@code TRUNCATE TABLE …} — fail-closed delete-all via OpLog / TX stage.
	 *
	 * @author: GenCloud
	 * @date: 2025/11
	 * @since: 1.0
	 */
	public record TruncateSql(String table) implements Stmt {
	}

	/**
	 * {@code UPDATE … SET …}.
	 *
	 * @author: GenCloud
	 * @date: 2025/11
	 * @since: 1.0
	 */
	public record UpdateSql(String table, UpdatePlan rmw, Map<String, Object> setLiterals,
	                        String whereSql, String pkColumnOrNull, Object pkValueOrNull,
	                        String sourceTableOrNull, String targetJoinColumnOrNull,
	                        String sourceJoinColumnOrNull,
	                        List<String> returning) implements Stmt {
	}

	/**
	 * Deferred {@code nextval}/{@code currval} in INSERT VALUES (resolved at DML time).
	 *
	 * @author: GenCloud
	 * @date: 2025/11
	 * @since: 1.0
	 */
	public record SequenceCallExpr(boolean nextVal, String sequenceName) {
	}
}