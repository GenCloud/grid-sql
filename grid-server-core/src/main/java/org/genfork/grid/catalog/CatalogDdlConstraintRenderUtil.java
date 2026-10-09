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

import java.util.List;
import java.util.Objects;

/**
 * Shared DDL text for FOREIGN KEY / CHECK clauses in journal, OpLog, and compaction.
 * <p>
 * FK parents are always {@link FkDef#parentCatalogKey()} so recover / peer apply under
 * session {@code public} stay session-invariant (same contract as child {@code catalogKey}).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.1
 */
public final class CatalogDdlConstraintRenderUtil {
	private static final String CONSTRAINT_KW = "CONSTRAINT ";
	private static final String FOREIGN_KEY_KW = "FOREIGN KEY (";
	private static final String REFERENCES_KW = ") REFERENCES ";
	private static final String CHECK_KW = "CHECK (";
	private static final String ON_DELETE_KW = " ON DELETE ";
	private static final String ON_UPDATE_KW = " ON UPDATE ";
	private static final char CLOSE_PAREN = ')';
	private static final String COL_SEP = ", ";

	private CatalogDdlConstraintRenderUtil() {
	}

	/**
	 * Append {@code CONSTRAINT name FOREIGN KEY (…) REFERENCES catalogKey (…) [ON DELETE|UPDATE]}.
	 */
	public static void appendForeignKeyClause(StringBuilder sb, FkDef fk) {
		Objects.requireNonNull(sb, "sb");
		Objects.requireNonNull(fk, "fk");
		sb.append(CONSTRAINT_KW).append(fk.name()).append(' ');
		sb.append(FOREIGN_KEY_KW);
		appendColumns(sb, fk.childColumns());
		sb.append(REFERENCES_KW).append(fk.parentCatalogKey()).append(" (");
		appendColumns(sb, fk.parentColumns());
		sb.append(CLOSE_PAREN);
		if (fk.onDelete() != FkAction.RESTRICT) {
			sb.append(ON_DELETE_KW).append(fk.onDelete().sqlToken());
		}
		if (fk.onUpdate() != FkAction.RESTRICT) {
			sb.append(ON_UPDATE_KW).append(fk.onUpdate().sqlToken());
		}
	}

	/**
	 * Append {@code CONSTRAINT name CHECK (expr)}.
	 */
	public static void appendCheckClause(StringBuilder sb, CheckDef check) {
		Objects.requireNonNull(sb, "sb");
		Objects.requireNonNull(check, "check");
		sb.append(CONSTRAINT_KW).append(check.name()).append(' ');
		sb.append(CHECK_KW).append(check.expressionSql()).append(CLOSE_PAREN);
	}

	private static void appendColumns(StringBuilder sb, List<String> columns) {
		for (int i = 0; i < columns.size(); i++) {
			if (i > 0) {
				sb.append(COL_SEP);
			}
			sb.append(columns.get(i));
		}
	}
}
